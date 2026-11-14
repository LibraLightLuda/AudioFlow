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
data class LibrarySearchHit(@Embedded val saved: SavedTrack, val locations: String?)

@Dao
abstract class LibraryDao {
    @Query("""SELECT tracks.*, GROUP_CONCAT(DISTINCT playlists.name) AS locations FROM tracks
        LEFT JOIN playlist_tracks ON tracks.id=playlist_tracks.trackId LEFT JOIN playlists ON playlists.id=playlist_tracks.playlistId
        WHERE (playedAt>0 OR favoriteAt>0 OR playlist_tracks.trackId IS NOT NULL)
        AND (instr(lower(title),lower(:query))>0 OR instr(lower(channel),lower(:query))>0 OR instr(lower(playlists.name),lower(:query))>0)
        GROUP BY tracks.id ORDER BY favoriteAt DESC, playedAt DESC LIMIT 300""")
    abstract fun searchLibrary(query: String): Flow<List<LibrarySearchHit>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId=:listId AND trackId=:trackId")
    abstract suspend fun playlistEntry(listId: Long, trackId: String): PlaylistEntry?

    @Query("UPDATE tracks SET playedAt=:at, positionMs=:position WHERE id=:id AND playedAt=0")
    protected abstract suspend fun restoreHistoryFields(id: String, at: Long, position: Long)

    @Transaction
    open suspend fun restoreHistory(row: SavedTrack) { insert(row); restoreHistoryFields(row.track.id, row.playedAt, row.positionMs) }

    @Transaction
    open suspend fun restoreFavorite(row: SavedTrack) { insert(row); markFavorite(row.track.id, row.favoriteAt) }
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
    @Query("SELECT * FROM tracks WHERE playedAt>0 ORDER BY playedAt DESC LIMIT 1000")
    abstract suspend fun backupHistory(): List<SavedTrack>

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
        backup.history.forEach { row ->
            val previous = track(row.track.id)
            insert(row)
            if (previous != null && row.playedAt > previous.playedAt) updatePosition(row.track.id, row.positionMs, row.playedAt)
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
private val Context.queuePlayback by preferencesDataStore("audioflow-position")

class LocalStore(context: Context) {
    val dao = Room.databaseBuilder(context, AudioFlowDatabase::class.java, "audioflow.db")
        .addMigrations(LibraryMigration1To2)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(CREATE_DEFAULT_PLAYLIST)
            }
        }).build().library()
    private val settings = context.settings
    private val positionStore = context.queuePlayback
    private val queueKey = stringPreferencesKey("queue")
    private val positionKey = stringPreferencesKey("position")
    private var persistedTracks: List<Track>? = null
    private var queueToken: String? = null
    private val queueWrite = Mutex()
    private val darkKey = booleanPreferencesKey("dark")
    private val colorThemeKey = stringPreferencesKey("color_theme")
    private val autoplayKey = booleanPreferencesKey("autoplay")
    private val preloadKey = booleanPreferencesKey("preload")
    private val soundKey = stringPreferencesKey("sound_profile")
    private val soundPresetsKey = stringSetPreferencesKey("sound_presets")
    private val releaseNotesSeenKey = longPreferencesKey("release_notes_seen")
    private val listeningKey = stringPreferencesKey("listening")
    private val blockedTracksKey = stringSetPreferencesKey("blocked_tracks")
    private val blockedChannelsKey = stringSetPreferencesKey("blocked_channels")
    private val versionChoicesKey = stringSetPreferencesKey("version_choices")
    private val data = settings.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    val dark = data.map { it[darkKey] ?: true }.distinctUntilChanged()
    internal val colorTheme = data.map { AppColorTheme.decode(it[colorThemeKey]) }.distinctUntilChanged()
    val autoplay = data.map { it[autoplayKey] ?: false }.distinctUntilChanged()
    val preload = data.map { it[preloadKey] ?: false }.distinctUntilChanged()
    internal val listening = data.map { ListeningOptions.decode(it[listeningKey]) }.distinctUntilChanged()
    internal val blockedTracks = data.map { it[blockedTracksKey].orEmpty() }.distinctUntilChanged()
    internal val blockedChannels = data.map { it[blockedChannelsKey].orEmpty() }.distinctUntilChanged()
    internal val versionChoices = data.map { it[versionChoicesKey].orEmpty().associate { entry -> entry.substringBefore('|') to entry.substringAfter('|') } }.distinctUntilChanged()
    internal suspend fun chooseVersion(key: String, id: String) {
        require(key.matches(Regex("[0-9a-f]{64}")) && YoutubeLink.isVideoId(id))
        settings.edit { prefs -> prefs[versionChoicesKey] = ((prefs[versionChoicesKey].orEmpty().filterNot { it.startsWith("$key|") } + "$key|$id").takeLast(500)).toSet() }
    }
    internal suspend fun setListening(options: ListeningOptions) { settings.edit { it[listeningKey] = options.encode() } }
    internal suspend fun updateListening(change: (ListeningOptions) -> ListeningOptions) {
        settings.edit { it[listeningKey] = change(ListeningOptions.decode(it[listeningKey])).encode() }
    }
    internal suspend fun blockTrack(id: String, block: Boolean) {
        require(YoutubeLink.isVideoId(id))
        settings.edit { it[blockedTracksKey] = ((it[blockedTracksKey].orEmpty() - id) + if (block) setOf(id) else emptySet()).also { value -> require(value.size <= 5000) } }
    }
    internal suspend fun blockChannel(channel: String, block: Boolean) {
        require(channel.isNotBlank() && channel.length <= 500)
        settings.edit { it[blockedChannelsKey] = ((it[blockedChannelsKey].orEmpty() - channel) + if (block) setOf(channel) else emptySet()).also { value -> require(value.size <= 1000) } }
    }
    internal suspend fun clearRecommendationBlocks() { settings.edit { it.remove(blockedTracksKey); it.remove(blockedChannelsKey) } }
    private fun trackKey(prefix: String, id: String): String { require(YoutubeLink.isVideoId(id)); return "${prefix}_$id" }
    internal fun trackGain(id: String) = data.map { (it[intPreferencesKey(trackKey("gain", id))] ?: 0).coerceIn(-12, 6) }.distinctUntilChanged()
    internal suspend fun setTrackGain(id: String, db: Int) { require(db in -12..6); settings.edit { it[intPreferencesKey(trackKey("gain", id))] = db } }
    internal fun bookmarks(id: String) = data.map { it[stringSetPreferencesKey(trackKey("bookmarks", id))].orEmpty()
        .mapNotNull(String::toLongOrNull).filter { value -> value >= 0 }.sorted() }.distinctUntilChanged()
    internal suspend fun toggleBookmark(id: String, ms: Long) {
        require(ms >= 0)
        val key = stringSetPreferencesKey(trackKey("bookmarks", id))
        settings.edit { prefs -> val old = prefs[key].orEmpty(); prefs[key] = if (ms.toString() in old) old - ms.toString()
            else (old + ms.toString()).also { require(it.size <= 100) { "곡당 북마크는 100개까지 저장할 수 있어요." } } }
    }
    internal fun lyrics(id: String) = data.map { it[stringPreferencesKey(trackKey("lyrics", id))] }.distinctUntilChanged()
    internal suspend fun setLyrics(id: String, text: String?) {
        if (text != null) require(parseLrc(text).isNotEmpty()) { "시간 정보가 있는 LRC 가사를 선택해 주세요." }
        settings.edit { prefs ->
            val key = stringPreferencesKey(trackKey("lyrics", id))
            if (text == null) prefs.remove(key) else prefs[key] = text
            require(prefs.asMap().filterKeys { it.name.startsWith("lyrics_") }.values.sumOf { it.toString().toByteArray().size } <= 1024 * 1024) {
                "로컬 가사 저장 한도는 1 MiB예요. 기존 가사를 지운 후 다시 시도해 주세요." }
        }
    }
    internal fun lyricOffset(id: String) = data.map { it[longPreferencesKey(trackKey("offset", id))] ?: 0L }.distinctUntilChanged()
    internal suspend fun setLyricOffset(id: String, ms: Long) { settings.edit { it[longPreferencesKey(trackKey("offset", id))] = ms.coerceIn(-60_000, 60_000) } }
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
        val state = kotlinx.coroutines.withContext(Dispatchers.Default) { runCatching {
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
        }.getOrDefault(QueueState()) }
        persistedTracks = state.tracks
        queueToken = queueDigest(raw)
        val saved = positionStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()[positionKey]
        runCatching {
            val info = JSONObject(saved ?: return@runCatching state)
            if (info.optString("token") != queueToken) state else state.copy(
                index = info.optInt("index").coerceIn(0, state.tracks.lastIndex.coerceAtLeast(0)),
                position = info.optLong("position").coerceAtLeast(0),
                speed = normalizedPlaybackSpeed(info.optDouble("speed", 1.0).toFloat()),
                repeat = info.optInt("repeat").coerceIn(0, 2), shuffle = info.optBoolean("shuffle"))
        }.getOrDefault(state)
    }

    suspend fun saveQueue(state: QueueState) = queueWrite.withLock {
        if (state.tracks != persistedTracks || queueToken == null) {
            val raw = kotlinx.coroutines.withContext(Dispatchers.Default) { encodeQueue(state) }
            settings.edit { it[queueKey] = raw }
            persistedTracks = state.tracks
            queueToken = queueDigest(raw)
        }
        val info = JSONObject().put("token", queueToken).put("index", state.index).put("position", state.position)
            .put("speed", normalizedPlaybackSpeed(state.speed)).put("repeat", state.repeat).put("shuffle", state.shuffle).toString()
        positionStore.edit { it[positionKey] = info }
    }

    private fun queueDigest(raw: String) = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    internal suspend fun exportConfiguration(): Map<String, String> = data.first().asMap()
        .filterKeys { backupSettingAllowed(it.name) }.mapKeys { it.key.name }.mapValues { (_, value) ->
            if (value is Set<*>) JSONArray(value.toList()).toString() else value.toString()
        }

    internal suspend fun restoreConfiguration(snapshot: Map<String, String>) {
        val values = decodeSettingsSnapshot(snapshot)
        settings.edit { prefs -> values.forEach { (name, value) -> when (value) {
            is Boolean -> prefs[booleanPreferencesKey(name)] = value
            is Int -> prefs[intPreferencesKey(name)] = value
            is Long -> prefs[longPreferencesKey(name)] = value
            is String -> prefs[stringPreferencesKey(name)] = value
            is Set<*> -> prefs[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
        } } }
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
    internal val sleepAtEnd = MutableStateFlow(false)
    internal val practice = MutableStateFlow(PracticeLoop())
    internal val playbackHealth = MutableStateFlow("준비됨")
    internal val cacheBytes = MutableStateFlow(0L)
    internal val diagnostic = MutableStateFlow("아직 기록된 오류가 없어요.")
    internal val issues = MutableSharedFlow<String>(extraBufferCapacity = 1)
    internal fun reportFailure(stage: String, failure: Throwable) {
        val detail = "$stage · ${safeFailureTypes(failure)}\n${safeFailureLocation(failure)}"
        if (diagnostic.value != detail) issues.tryEmit("$stage 작업을 완료하지 못했어요. 설정의 진단 정보를 확인해 주세요.")
        diagnostic.value = detail
    }
    internal fun saveLocal(stage: String, action: suspend () -> Unit) {
        scope.launch {
            try { action() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { reportFailure(stage, failure) }
        }
    }
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
