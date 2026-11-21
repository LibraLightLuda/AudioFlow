package app.audioflow

internal fun playbackSelection(tracks: List<Track>, index: Int, all: Boolean = false): Pair<List<Track>,Int>? {
    if(index !in tracks.indices) return null
    if(!all) return listOf(tracks[index]) to 0
    val start=index/200*200
    return tracks.drop(start).take(200) to (index-start)
}
