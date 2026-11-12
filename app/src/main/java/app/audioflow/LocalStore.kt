package app.audioflow

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_PLAYLIST_ID = -1L
private const val CREATE_DEFAULT_PLAYLIST =
    "INSERT OR IGNORE INTO playlists(id, name) VALUES (-1, '기본 플레이리스트')"

internal val LibraryMigration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN channelUrl TEXT NOT NULL DEFAULT ''")
        db.execSQL(CREATE_DEFAULT_PLAYLIST)
    }
}

@Entity(tableName = "tracks", primaryKeys = ["id"])
data class SavedTrack(
    @Embedded val track: Track,
    val playedAt: Long = 0,
    val positionMs: Long = 0,
    val favoriteAt: Long = 0,
)

@Entity(tableName = "playlists")
data class LocalPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [ForeignKey(
        entity = LocalPlaylist::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("trackId")],
)
data class PlaylistEntry(val playlistId: Long, val trackId: String, val addedAt: Long)

data class BackupEntryRow(val playlistId: Long, @Embedded val track: Track)

@Dao
abstract class LibraryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(row: SavedTrack)

    @Query("UPDATE tracks SET title=:title, channel=:channel, thumbnail=:thumbnail, seconds=:seconds, channelUrl=CASE WHEN :channelUrl='' THEN channelUrl ELSE :channelUrl END WHERE id=:id")
    abstract suspend fun refresh(id: String, title: String, channel: String, thumbnail: String, seconds: Long, channelUrl: String)

    @Query("SELECT * FROM tracks WHERE id=:id")
    abstract suspend fun track(id: String): SavedTrack?

    @Query("UPDATE tracks SET playedAt=:at, positionMs=:position WHERE id=:id")
    abstract suspend fun updatePosition(id: String, position: Long, at: Long)

    @Query("UPDATE tracks SET positionMs=:position WHERE id=:id AND playedAt>0")
    abstract suspend fun checkpointPosition(id: String, position: Long)

    @Query("UPDATE tracks SET favoriteAt=CASE WHEN favoriteAt=0 THEN :now ELSE 0 END WHERE id=:id")
    abstract suspend fun flipFavorite(id: String, now: Long)

    @Query("UPDATE tracks SET favoriteAt=:now WHERE id=:id AND favoriteAt=0")
    protected abstract suspend fun markFavorite(id: String, now: Long)

    @Query("SELECT id FROM tracks WHERE playedAt>0")
    abstract suspend fun playedIds(): List<String>

    @Query("""
        SELECT DISTINCT tracks.* FROM tracks JOIN playlist_tracks AS entry ON tracks.id=entry.trackId
        WHERE entry.playlistId IN (
            SELECT playlistId FROM playlist_tracks WHERE trackId=:id AND playlistId != -1
        ) AND tracks.playedAt=0 AND tracks.id != :id
        ORDER BY entry.addedAt, entry.trackId LIMIT 200
    """)
    abstract suspend fun relatedPlaylistTracks(id: String): List<SavedTrack>

    @Query("SELECT * FROM tracks WHERE playedAt>0 ORDER BY playedAt DESC LIMIT 100")
    abstract fun history(): Flow<List<SavedTrack>>

    @Query("SELECT * FROM tracks WHERE favoriteAt>0 ORDER BY favoriteAt DESC")
    abstract fun favorites(): Flow<List<SavedTrack>>

    @Query("UPDATE tracks SET playedAt=0, positionMs=0 WHERE playedAt>0")
    abstract suspend fun clearHistory()

    @Query("UPDATE tracks SET playedAt=0, positionMs=0 WHERE id=:id")
    abstract suspend fun removeHistory(id: String)

    @Query("SELECT * FROM playlists ORDER BY id")
    abstract fun playlists(): Flow<List<LocalPlaylist>>

    @Query("SELECT * FROM playlists ORDER BY id")
    protected abstract suspend fun allPlaylists(): List<LocalPlaylist>

    @Query("SELECT * FROM tracks WHERE favoriteAt>0 ORDER BY favoriteAt DESC")
    protected abstract suspend fun allFavorites(): List<SavedTrack>

    @Query("SELECT playlistId, tracks.* FROM playlist_tracks JOIN tracks ON tracks.id=trackId ORDER BY playlistId, addedAt, trackId")
    protected abstract suspend fun allBackupEntries(): List<BackupEntryRow>

    @Query("SELECT tracks.* FROM tracks JOIN playlist_tracks ON tracks.id=trackId WHERE playlistId=:id ORDER BY addedAt, trackId")
    abstract fun playlistTracks(id: Long): Flow<List<SavedTrack>>

    @Insert
    abstract suspend fun createPlaylist(playlist: LocalPlaylist): Long

    @Query("UPDATE playlists SET name=:name WHERE id=:id AND id != -1")
    abstract suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id=:id AND id != -1")
    abstract suspend fun deletePlaylist(id: Long)

    @Query(CREATE_DEFAULT_PLAYLIST)
    abstract suspend fun ensureDefaultPlaylist()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPlaylistEntry(entry: PlaylistEntry)

    @Query("DELETE FROM playlist_tracks WHERE playlistId=:listId AND trackId=:trackId")
    abstract suspend fun removeFromPlaylist(listId: Long, trackId: String)

    @Query("DELETE FROM playlist_tracks WHERE trackId=:trackId")
    abstract suspend fun unlinkTrack(trackId: String)

    @Transaction
    open suspend fun removeFromLibrary(trackId: String) {
        unlinkTrack(trackId)
        removeHistory(trackId)
    }

    @Transaction
    open suspend fun removeDuplicates(listId: Long?, trackIds: List<String>) {
        trackIds.forEach { id ->
            if (listId == null) removeHistory(id) else removeFromPlaylist(listId, id)
        }
    }

    private suspend fun remember(track: Track) {
        insert(SavedTrack(track))
        refresh(track.id, track.title, track.channel, track.thumbnail, track.seconds, track.channelUrl)
    }

    @Transaction
    open suspend fun played(track: Track, position: Long) {
        remember(track)
        updatePosition(track.id, position.coerceAtLeast(0), System.currentTimeMillis())
    }

    @Transaction
    open suspend fun toggleFavorite(track: Track) {
        remember(track)
        flipFavorite(track.id, System.currentTimeMillis())
    }

    @Transaction
    open suspend fun addToPlaylist(id: Long, track: Track) {
        appendTracks(id, listOf(track))
    }

    @Transaction
    open suspend fun saveDefault(track: Track) {
        ensureDefaultPlaylist()
        addToPlaylist(DEFAULT_PLAYLIST_ID, track)
    }

    @Transaction
    open suspend fun importPlaylist(name: String, tracks: List<Track>) {
        require(tracks.isNotEmpty())
        appendTracks(createPlaylist(LocalPlaylist(name = name)), tracks)
    }

    @Query("SELECT COALESCE(MAX(addedAt), 0) FROM playlist_tracks WHERE playlistId=:id")
    protected abstract suspend fun lastAddedAt(id: Long): Long

    private suspend fun appendTracks(id: Long, tracks: List<Track>, refreshExisting: Boolean = true) {
        val start = maxOf(System.currentTimeMillis(), lastAddedAt(id) + 1)
        tracks.distinctBy { it.id }.forEachIndexed { index, track ->
            if (refreshExisting) remember(track) else insert(SavedTrack(track))
            insertPlaylistEntry(PlaylistEntry(id, track.id, start + index))
        }
    }

    @Transaction
    open suspend fun backup(): LibraryBackup {
        val entries = allBackupEntries().groupBy(BackupEntryRow::playlistId, BackupEntryRow::track)
        return LibraryBackup(allFavorites().map { it.track }, allPlaylists().map { list ->
            BackupPlaylist(list.name, list.id == DEFAULT_PLAYLIST_ID, entries[list.id].orEmpty())
        })
    }

    @Transaction
    open suspend fun mergeBackup(backup: LibraryBackup): BackupPreview {
        val preview = previewBackup(backup(), backup)
        val now = System.currentTimeMillis()
        preview.additions.favorites.forEachIndexed { index, track ->
            insert(SavedTrack(track))
            markFavorite(track.id, now - index)
        }
        preview.additions.playlists.forEach { list ->
            if (list.isDefault) {
                ensureDefaultPlaylist()
                appendTracks(DEFAULT_PLAYLIST_ID, list.tracks, refreshExisting = false)
            } else {
                appendTracks(createPlaylist(LocalPlaylist(name = list.name)), list.tracks, refreshExisting = false)
            }
        }
        return preview
    }
}

@Database(entities = [SavedTrack::class, LocalPlaylist::class, PlaylistEntry::class], version = 2, exportSchema = false)
abstract class AudioFlowDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
}

data class QueueState(
    val tracks: List<Track> = emptyList(),
    val index: Int = 0,
    val position: Long = 0,
    val speed: Float = 1f,
    val repeat: Int = 0,
    val shuffle: Boolean = false,
)

private val Context.settings by preferencesDataStore("audioflow")

class LocalStore(context: Context) {
    val dao = Room.databaseBuilder(context, AudioFlowDatabase::class.java, "audioflow.db")
        .addMigrations(LibraryMigration1To2)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_DEFAULT_PLAYLIST)
            }
        }).build().library()
    private val settings = context.settings
    private val queueKey = stringPreferencesKey("queue")
    private val queueWrite = Mutex()
    private val darkKey = booleanPreferencesKey("dark")
    private val colorThemeKey = stringPreferencesKey("color_theme")
    private val autoplayKey = booleanPreferencesKey("autoplay")
    private val preloadKey = booleanPreferencesKey("preload")
    private val soundKey = stringPreferencesKey("sound_profile")
    private val soundPresetsKey = stringSetPreferencesKey("sound_presets")
    private val releaseNotesSeenKey = longPreferencesKey("release_notes_seen")
    private val data = settings.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val dark = data.map { it[darkKey] ?: true }.distinctUntilChanged()
    internal val colorTheme = data.map { AppColorTheme.decode(it[colorThemeKey]) }.distinctUntilChanged()
    val autoplay = data.map { it[autoplayKey] ?: false }.distinctUntilChanged()
    val preload = data.map { it[preloadKey] ?: false }.distinctUntilChanged()
    internal val sound = data.map { SoundProfile.decode(it[soundKey]) }.distinctUntilChanged()
    internal val soundPresets = data.map { prefs ->
        prefs[soundPresetsKey].orEmpty().mapNotNull { encoded ->
            val name = encoded.substringBefore('|')
            if (name.isBlank() || '|' !in encoded) null
            else SoundPreset(name, SoundProfile.decode(encoded.substringAfter('|')))
        }.sortedBy { it.name }
    }.distinctUntilChanged()

    suspend fun setDark(dark: Boolean) {
        settings.edit { it[darkKey] = dark }
    }

    internal suspend fun setColorTheme(theme: AppColorTheme) { settings.edit { it[colorThemeKey] = theme.name } }

    suspend fun setAutoplay(enabled: Boolean) { settings.edit { it[autoplayKey] = enabled } }
    suspend fun setPreload(enabled: Boolean) { settings.edit { it[preloadKey] = enabled } }

    internal suspend fun setSound(profile: SoundProfile) { settings.edit { it[soundKey] = profile.encode() } }

    internal suspend fun lastSeenReleaseNotes(): Long? = data.first()[releaseNotesSeenKey]

    internal suspend fun markReleaseNotesSeen(versionCode: Long) {
        settings.edit { prefs ->
            if (versionCode > (prefs[releaseNotesSeenKey] ?: 0L)) prefs[releaseNotesSeenKey] = versionCode
        }
    }

    internal suspend fun saveSoundPreset(name: String, profile: SoundProfile): Boolean {
        require(name.isNotBlank() && name.length <= 28 && '|' !in name)
        var saved = false
        settings.edit { prefs ->
            val existing = prefs[soundPresetsKey].orEmpty().filterNot { it.substringBefore('|').equals(name, true) }
            if (existing.size < MAX_SOUND_PRESETS) {
                prefs[soundPresetsKey] = existing.toSet() + "$name|${profile.encode()}"
                saved = true
            }
        }
        return saved
    }

    internal suspend fun removeSoundPreset(name: String) {
        settings.edit { prefs ->
            prefs[soundPresetsKey] = prefs[soundPresetsKey].orEmpty().filterNot { it.substringBefore('|') == name }.toSet()
        }
    }

    suspend fun readQueue(): QueueState = queueWrite.withLock {
        val raw = data.first()[queueKey] ?: return@withLock QueueState()
        runCatching {
            val json = JSONObject(raw)
            val tracks = json.getJSONArray("tracks")
            QueueState(
                tracks = List(tracks.length()) { i ->
                    val item = tracks.getJSONObject(i)
                    Track(item.getString("id"), item.getString("title"), item.optString("channel"),
                        item.optString("thumbnail"), item.optLong("seconds"), item.optString("channelUrl"))
                },
                index = json.optInt("index"),
                position = json.optLong("position").coerceAtLeast(0),
                speed = normalizedPlaybackSpeed(json.optDouble("speed", 1.0).toFloat()),
                repeat = json.optInt("repeat").coerceIn(0, 2),
                shuffle = json.optBoolean("shuffle"),
            )
        }.getOrDefault(QueueState())
    }

    suspend fun saveQueue(state: QueueState) = queueWrite.withLock {
        val raw = kotlinx.coroutines.withContext(Dispatchers.Default) { encodeQueue(state) }
        settings.edit { it[queueKey] = raw }
    }

    private fun encodeQueue(state: QueueState): String {
        val tracks = JSONArray()
        state.tracks.forEach { track ->
            tracks.put(JSONObject().put("id", track.id).put("title", track.title)
                .put("channel", track.channel).put("thumbnail", track.thumbnail).put("seconds", track.seconds)
                .put("channelUrl", track.channelUrl))
        }
        return JSONObject().put("tracks", tracks).put("index", state.index).put("position", state.position)
            .put("speed", normalizedPlaybackSpeed(state.speed)).put("repeat", state.repeat).put("shuffle", state.shuffle).toString()
    }
}

class AudioFlowApp : Application(), coil.ImageLoaderFactory {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF)
        androidx.media3.common.util.Log.setLogStackTraces(false)
        scope.launch {
            val stored = store.sound.first()
            if (!soundEdited) sound.value = stored
        }
    }

    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient(PrivateNetwork.images)
        .diskCache(null)
        .logger(null)
        .build()

    val store by lazy { LocalStore(this) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sleepDeadline = MutableStateFlow(0L)
    val autoplayTrack = MutableStateFlow<Pair<String, String>?>(null)
    internal val radio = MutableStateFlow(RadioUi())
    internal val sound = MutableStateFlow(SoundProfile())
    private var soundEdited = false

    internal fun changeSound(profile: SoundProfile, persist: Boolean) {
        soundEdited = true
        sound.value = profile
        if (persist) saveSound()
    }

    internal fun saveSound() {
        if (!soundEdited) return
        val profile = sound.value
        scope.launch {
            runCatching { store.setSound(profile) }
                .onFailure { android.util.Log.w("AudioFlow", "Could not save sound settings") }
        }
    }
}
