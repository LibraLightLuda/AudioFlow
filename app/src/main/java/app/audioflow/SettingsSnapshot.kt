package app.audioflow

import org.json.JSONArray

internal val backupSettingNames = setOf("dark", "color_theme", "autoplay", "preload", "sound_profile", "sound_presets",
    "listening", "blocked_tracks", "blocked_channels", "version_choices")

internal fun backupSettingAllowed(name: String) = name in backupSettingNames ||
    Regex("(?:gain|offset|bookmarks|lyrics)_[A-Za-z0-9_-]{11}").matches(name)

internal fun decodeSettingsSnapshot(snapshot: Map<String, String>): Map<String, Any> {
    require(snapshot.size <= 1000)
    fun stringSet(value: String, max: Int): Set<String> {
        validateJsonDepth(value, 4)
        val array = JSONArray(value); require(array.length() <= max)
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }
    return snapshot.mapValues { (name, value) ->
        require(backupSettingAllowed(name)) { "지원하지 않는 설정이에요." }
        when {
            name in setOf("dark", "autoplay", "preload") -> value.toBooleanStrict()
            name == "color_theme" -> AppColorTheme.valueOf(value).name
            name == "listening" -> ListeningOptions.decode(value).also { require(it.encode() == value) }.encode()
            name == "sound_profile" -> SoundProfile.decode(value).also {
                require(it.encode() == value || (value.startsWith("1|") && it.encode().removeSuffix("|0").replaceFirst("2|", "1|") == value))
            }.encode()
            name == "sound_presets" -> stringSet(value, MAX_SOUND_PRESETS).onEach {
                require(it.substringBefore('|').let { n -> n.isNotBlank() && n.length <= 28 })
                decodeSettingsSnapshot(mapOf("sound_profile" to it.substringAfter('|')))
            }
            name == "blocked_tracks" -> stringSet(value, 5000).onEach { require(YoutubeLink.isVideoId(it)) }
            name == "blocked_channels" -> stringSet(value, 1000).onEach { require(it.isNotBlank() && it.length <= 500) }
            name == "version_choices" -> stringSet(value, 500).onEach {
                require(it.substringBefore('|').matches(Regex("[0-9a-f]{64}")) && YoutubeLink.isVideoId(it.substringAfter('|')))
            }
            name.startsWith("gain_") -> value.toInt().also { require(it in -12..6) }
            name.startsWith("offset_") -> value.toLong().also { require(it in -60_000..60_000) }
            name.startsWith("bookmarks_") -> stringSet(value, 100).onEach { require(it.toLong() in 0..31_536_000_000L) }
            name.startsWith("lyrics_") -> value.also { require(parseLrc(it).isNotEmpty()) }
            else -> error("Unknown setting")
        }
    }.also { values -> require(values.filterKeys { it.startsWith("lyrics_") }.values.sumOf { it.toString().toByteArray().size } <= 1024 * 1024) }
}
