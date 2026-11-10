package app.audioflow

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

internal enum class RadioPhase { Off, Finding, On, Waiting, Stalled }
internal data class RadioUi(val phase: RadioPhase = RadioPhase.Off, val detail: String? = null)

private val officialMarker = Regex(
    "\\bofficial\\b|공식\\s*(영상|음원|뮤직비디오)|\\bm/?v\\b", RegexOption.IGNORE_CASE,
)
private val nonSong = Regex(
    "\\b(reaction|karaoke|tutorial|interview|review|trailer|teaser|playlist|cover)\\b|리액션|노래방|인터뷰|뮤비해석|모음집|커버",
    RegexOption.IGNORE_CASE,
)
private val titleSuffix = Regex(
    "\\s*[\\[(]\\s*(official(?:\\s+(?:music\\s+)?(?:video|audio))?|m/?v|lyrics?|공식\\s*(?:영상|음원|뮤직비디오))\\s*[\\])]",
    RegexOption.IGNORE_CASE,
)
private val separators = Regex("[^\\p{L}\\p{N}]+")
private val common = setOf("official", "music", "video", "audio", "lyrics", "topic", "뮤직비디오", "공식", "음원")

private fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    .replace(separators, " ").trim()
private fun words(value: String) = normalized(value).split(' ').filter { it.length > 1 && it !in common }.toSet()
private fun Track.artist() = normalized(channel.removeSuffix(" - Topic").replace(Regex("VEVO$", RegexOption.IGNORE_CASE), ""))
private fun Track.song() = normalized(title.replace(titleSuffix, "")).removePrefix(artist() + " ")

internal val radioDiscoveryQueries = listOf(
    "K-pop official music video", "한국 인디 공식 음원", "pop hits official music video",
    "한국 R&B 공식 음원", "alternative rock official music video", "J-pop official music video",
    "acoustic official audio", "한국 발라드 공식 음원", "soul funk official music video",
)

internal class RadioTaste(favorites: List<Track>, history: List<Track>) {
    private val channels = mutableMapOf<String, Int>()
    private val tokens = mutableMapOf<String, Int>()
    private val displayNames = mutableMapOf<String, String>()

    init {
        for ((weight, tracks) in listOf(3 to favorites.take(20), 1 to history.take(30))) {
            tracks.forEach { track ->
                val key = track.artist()
                if (key.isNotEmpty()) {
                    channels[key] = (channels[key] ?: 0) + weight
                    displayNames.putIfAbsent(key, track.channel.removeSuffix(" - Topic")
                        .replace(Regex("VEVO$", RegexOption.IGNORE_CASE), "").trim().take(70))
                }
                words(track.song()).forEach { word -> tokens[word] = (tokens[word] ?: 0) + weight }
            }
        }
    }

    fun queries() = channels.entries.sortedByDescending { it.value }.take(3).mapNotNull { entry ->
        displayNames[entry.key]?.takeIf { it.isNotBlank() }?.let { "$it official music video" }
    }

    fun relevance(track: Track, words: Set<String>): Double {
        val artistAffinity = ((channels[track.artist()] ?: 0) / 6.0).coerceAtMost(1.0)
        val titleAffinity = words.maxOfOrNull { ((tokens[it] ?: 0) / 6.0).coerceAtMost(1.0) } ?: 0.0
        val official = officialMarker.containsMatchIn(track.title) ||
            track.channel.endsWith(" - Topic", true) || track.channel.endsWith("VEVO", true)
        return (if (official) 0.37 else 0.22) + 0.42 * artistAffinity + 0.21 * titleAffinity
    }
}

internal fun radioTracks(result: CatalogResult): List<Track> = result.tracks.filter {
    YoutubeLink.isVideoId(it.id) && it.id !in result.shortIds && it.seconds in 60..1200 &&
        !nonSong.containsMatchIn(it.title)
}

internal fun rankRadio(
    candidates: Collection<Track>, taste: RadioTaste, recent: List<Track>, limit: Int,
): List<Track> {
    data class Entry(val track: Track, val artist: String, val song: String, val tokens: Set<String>, val relevance: Double)
    fun entry(track: Track): Entry {
        val tokens = words(track.song())
        return Entry(track, track.artist(), track.song(), tokens, taste.relevance(track, tokens))
    }
    fun similarity(a: Entry, b: Entry): Double {
        val overlap = a.tokens.count { it in b.tokens }
        val lexical = overlap.toDouble() / (a.tokens.size + b.tokens.size - overlap).coerceAtLeast(1)
        return if (a.artist.isNotEmpty() && a.artist == b.artist) maxOf(0.85, lexical) else lexical
    }
    val previous = recent.takeLast(4).map(::entry).toMutableList()
    val chosen = ArrayList<Track>(limit.coerceAtLeast(0))
    val eligible = candidates.distinctBy(Track::id).map(::entry).toMutableList()
    repeat(limit.coerceAtLeast(0)) {
        val next = eligible.maxByOrNull { candidate ->
            // MMR balances relevance against the most similar song already in the mix.
            0.68 * candidate.relevance - 0.32 * (previous.maxOfOrNull { similarity(candidate, it) } ?: 0.0)
        } ?: return chosen
        chosen.add(next.track)
        previous.add(next)
        eligible.removeAll { it.track.id == next.track.id ||
            (next.song.isNotBlank() && it.artist == next.artist && it.song == next.song) }
    }
    return chosen
}

internal class RadioMix(
    favorites: List<Track>,
    history: List<Track>,
    private val search: suspend (String) -> CatalogResult = YoutubeCatalog::radioSearch,
    discovery: List<String> = radioDiscoveryQueries.shuffled(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val taste = RadioTaste(favorites, history)
    private val sources = (taste.queries().take(1) + discovery.take(1) + taste.queries().drop(1) + discovery.drop(1)).distinct()
    private val pool = LinkedHashMap<String, Track>()
    private val searched = mutableMapOf<String, Long>()
    private val recent = ArrayDeque<Track>().apply { addAll(history.take(12).asReversed()) }

    suspend fun next(limit: Int, queued: List<Track>): List<Track> {
        val unavailable = queued.mapTo(hashSetOf(), Track::id)
        fun eligible(window: Int): List<Track> {
            val heard = recent.takeLast(window)
            val ids = heard.mapTo(hashSetOf(), Track::id)
            val songs = (heard + queued).mapTo(hashSetOf()) { it.artist() to it.song() }
            return pool.values.filter { it.id !in unavailable && it.id !in ids && (it.artist() to it.song()) !in songs }
        }
        var failure: Exception? = null
        repeat(2) {
            val available = eligible(48)
            if (available.size >= limit && available.map(Track::artist).distinct().size >= minOf(3, limit)) return@repeat
            val time = now()
            val source = sources.firstOrNull { query -> searched[query]?.let { time - it >= 900_000 } != false }
                ?: return@repeat
            searched[source] = time
            try {
                radioTracks(search(source)).take(30).forEach { pool[it.id] = it }
                while (pool.size > 140) pool.remove(pool.keys.first())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
                if (radioAccessLimited(error)) throw error
            }
        }
        val strict = eligible(48)
        val candidates = if (strict.isNotEmpty()) strict else eligible(8)
        val tracks = rankRadio(candidates, taste, (recent + queued).takeLast(4), limit)
        if (tracks.isEmpty() && failure != null) throw checkNotNull(failure)
        recent.addAll(tracks)
        while (recent.size > 48) recent.removeFirst()
        return tracks
    }
}

internal fun radioAccessLimited(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(12).any {
    (it is CatalogHttpException && it.status in setOf(403, 429)) ||
        it.javaClass.simpleName in setOf("ReCaptchaException", "SignInConfirmNotBotException")
}
