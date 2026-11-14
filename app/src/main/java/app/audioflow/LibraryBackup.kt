package app.audioflow

import org.json.JSONArray
import org.json.JSONObject

data class LibraryBackup(val favorites: List<Track>, val playlists: List<BackupPlaylist>,
    val settings: Map<String, String> = emptyMap(), val history: List<SavedTrack> = emptyList(), val queue: QueueState? = null)

data class BackupPlaylist(val name: String, val isDefault: Boolean, val tracks: List<Track>)

data class BackupPreview(
    val additions: LibraryBackup,
    val duplicateTracks: Int,
    val duplicatePlaylists: Int,
) {
    val newTracks get() = additions.favorites.size + additions.playlists.sumOf { it.tracks.size }
    val newPlaylists get() = additions.playlists.count { !it.isDefault }
    val hasChanges get() = newTracks > 0 || newPlaylists > 0 || additions.settings.isNotEmpty() || additions.history.isNotEmpty() || additions.queue != null
}

internal fun previewBackup(current: LibraryBackup, incoming: LibraryBackup): BackupPreview {
    val favoriteIds = current.favorites.mapTo(hashSetOf()) { it.id }
    val favorites = incoming.favorites.filter { favoriteIds.add(it.id) }
    val knownLists = current.playlists.filterNot { it.isDefault }
        .mapTo(hashSetOf()) { it.name to it.tracks.map(Track::id) }
    val defaultIds = current.playlists.filter { it.isDefault }.flatMap { it.tracks }.mapTo(hashSetOf()) { it.id }
    var duplicateTracks = incoming.favorites.size - favorites.size
    var duplicateLists = 0
    val playlists = incoming.playlists.mapNotNull { list ->
        val unique = list.tracks.distinctBy(Track::id)
        duplicateTracks += list.tracks.size - unique.size
        if (list.isDefault) {
            val added = unique.filter { defaultIds.add(it.id) }
            duplicateTracks += unique.size - added.size
            list.copy(tracks = added).takeIf { added.isNotEmpty() }
        } else if (knownLists.add(list.name to unique.map(Track::id))) {
            list.copy(tracks = unique)
        } else {
            duplicateLists++
            duplicateTracks += unique.size
            null
        }
    }
    return BackupPreview(LibraryBackup(favorites, playlists, incoming.settings, incoming.history, incoming.queue), duplicateTracks, duplicateLists)
}

internal const val BACKUP_BYTES = 4 * 1024 * 1024
private const val BACKUP_TRACKS = 20_000
private const val BACKUP_LISTS = 1_000

private fun backupChannel(raw: String) = (YoutubeLink.fromText(raw) as? YoutubeLink.Channel)?.url.orEmpty()

internal fun LibraryBackup.encode(): ByteArray {
    require(playlists.size <= BACKUP_LISTS && favorites.size.toLong() + playlists.sumOf { it.tracks.size.toLong() } + history.size + (queue?.tracks?.size ?: 0) <= BACKUP_TRACKS)
    decodeSettingsSnapshot(settings)
    fun tracks(items: List<Track>) = JSONArray().apply {
        items.forEach { track ->
            put(JSONObject().put("id", track.id).put("title", track.title.take(500))
                .put("channel", track.channel.take(300)).put("seconds", track.seconds)
                .put("channelUrl", backupChannel(track.channelUrl)))
        }
    }
    val lists = JSONArray().apply {
        playlists.forEach { list ->
            put(JSONObject().put("name", list.name).put("default", list.isDefault).put("tracks", tracks(list.tracks)))
        }
    }
    return JSONObject().put("format", "audioflow-library").put("version", 2)
        .put("favorites", tracks(favorites)).put("playlists", lists).put("settings", JSONObject(settings))
        .put("history", JSONArray().apply { history.forEach { row -> put(JSONObject().put("track", tracks(listOf(row.track)).getJSONObject(0))
            .put("at", row.playedAt).put("position", row.positionMs)) } })
        .put("queue", queue?.let { q -> JSONObject().put("tracks", tracks(q.tracks)).put("index", q.index).put("position", q.position)
            .put("speed", q.speed).put("repeat", q.repeat).put("shuffle", q.shuffle) })
        .toString(2).toByteArray(Charsets.UTF_8)
        .also { require(it.size <= BACKUP_BYTES) }
}

internal fun decodeBackup(bytes: ByteArray): LibraryBackup {
    require(bytes.size <= BACKUP_BYTES)
    val text = bytes.toString(Charsets.UTF_8)
    validateJsonDepth(text)
    val root = JSONObject(text)
    require(root.optString("format") == "audioflow-library" && root.optInt("version") in 1..2)
    var count = 0
    fun tracks(array: JSONArray): List<Track> {
        count += array.length()
        require(count <= BACKUP_TRACKS)
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            val id = item.getString("id")
            require(YoutubeLink.isVideoId(id))
            val title = item.getString("title").trim().take(500)
            require(title.isNotEmpty())
            Track(id, title, item.optString("channel").take(300),
                "https://i.ytimg.com/vi/$id/hqdefault.jpg", item.optLong("seconds").coerceIn(0, 31_536_000),
                backupChannel(item.optString("channelUrl")))
        }
    }
    val favorites = tracks(root.getJSONArray("favorites"))
    val lists = root.getJSONArray("playlists")
    require(lists.length() <= BACKUP_LISTS)
    val playlists = List(lists.length()) { index ->
        val list = lists.getJSONObject(index)
        val name = list.getString("name").trim().take(70)
        require(name.isNotEmpty())
        BackupPlaylist(name, list.optBoolean("default"), tracks(list.getJSONArray("tracks")))
    }
    val settings = root.optJSONObject("settings")?.let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }.orEmpty()
    decodeSettingsSnapshot(settings)
    val history = root.optJSONArray("history")?.let { array ->
        require(array.length() <= 1000)
        List(array.length()) { index -> val item = array.getJSONObject(index)
            SavedTrack(tracks(JSONArray().put(item.getJSONObject("track"))).single(),
                playedAt = item.getLong("at").also { require(it in 0..4_102_444_800_000L) },
                positionMs = item.optLong("position").also { require(it in 0..31_536_000_000L) })
        }
    }.orEmpty()
    val queue = root.optJSONObject("queue")?.let { q ->
        val entries = tracks(q.getJSONArray("tracks")); require(entries.size <= 200)
        QueueState(entries, q.optInt("index").coerceIn(0, entries.lastIndex.coerceAtLeast(0)), q.optLong("position").coerceIn(0,31_536_000_000L),
            normalizedPlaybackSpeed(q.optDouble("speed",1.0).toFloat()), q.optInt("repeat").coerceIn(0,2), q.optBoolean("shuffle"))
    }
    return LibraryBackup(favorites, playlists, settings, history, queue)
}
