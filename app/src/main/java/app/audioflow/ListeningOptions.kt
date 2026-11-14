package app.audioflow

internal enum class AudioQuality(val label: String, val maxKbps: Int) {
    Saver("데이터 절약", 80), Balanced("균형", 144), High("고음질", Int.MAX_VALUE),
}

internal data class ListeningOptions(
    val quality: AudioQuality = AudioQuality.High,
    val allowVideo: Boolean = true,
    val recoverNetwork: Boolean = true,
    val cacheMiB: Int = 24,
    val preloadSeconds: Int = 3,
    val fadeSeconds: Int = 10,
) {
    fun encode() = listOf(quality.name, allowVideo, recoverNetwork, cacheMiB, preloadSeconds, fadeSeconds).joinToString("|")
    companion object {
        fun decode(raw: String?): ListeningOptions = runCatching {
            val fields = requireNotNull(raw).split('|')
            require(fields.size == 6)
            ListeningOptions(AudioQuality.valueOf(fields[0]), fields[1].toBooleanStrict(), fields[2].toBooleanStrict(),
                fields[3].toInt().also { require(it in listOf(24, 64, 128)) },
                fields[4].toInt().also { require(it in listOf(3, 6, 10)) },
                fields[5].toInt().also { require(it in listOf(0, 10, 20, 30)) })
        }.getOrDefault(ListeningOptions())
    }
}

internal data class PracticeLoop(val videoId: String = "", val a: Long = 0, val b: Long? = null) {
    fun contains(id: String, position: Long) = videoId == id && b != null && position >= b
}

internal data class LyricLine(val timeMs: Long, val text: String)

internal fun validateJsonDepth(text: String, maxDepth: Int = 32) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> { depth++; require(depth <= maxDepth) { "JSON 구조가 너무 깊어요." } }
            '}', ']' -> { depth--; require(depth >= 0) }
        }
    }
    require(depth == 0 && !quoted)
}

internal fun recoverableNetworkFailure(error: Throwable, code: Int): Boolean = !radioAccessLimited(error) &&
    (code in setOf(2001, 2002) || generateSequence(error) { it.cause }.take(12).any {
        it is java.net.UnknownHostException || it is java.net.SocketTimeoutException || it is java.net.ConnectException || it is java.net.SocketException
    })

internal fun parseLrc(text: String): List<LyricLine> {
    require(text.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "가사 파일은 256 KiB 이하여야 해요." }
    val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE).find(text)
        ?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-60_000, 60_000) ?: 0
    return text.lineSequence().flatMap { line ->
        val matches = stamp.findAll(line).toList()
        val words = line.substring(matches.lastOrNull()?.range?.last?.plus(1) ?: line.length).trim()
        matches.asSequence().mapNotNull { match ->
            val (minutes, seconds, fraction) = match.destructured
            if (seconds.toInt() >= 60 || words.isBlank()) null else {
                val time = minutes.toLong() * 60_000 + seconds.toLong() * 1000 + fraction.padEnd(3, '0').take(3).toLong() + offset
                LyricLine(time.coerceAtLeast(0), words.take(1000))
            }
        }
    }.distinct().sortedBy { it.timeMs }.take(6000).toList()
}

internal fun songVersionKey(track: Track): String = java.text.Normalizer.normalize(track.title, java.text.Normalizer.Form.NFKC)
    .lowercase(java.util.Locale.ROOT)
    .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
    .replace(Regex("\\b(official|music|video|audio|lyrics?|live|remix|mv)\\b|뮤직비디오|공식|라이브"), " ")
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
