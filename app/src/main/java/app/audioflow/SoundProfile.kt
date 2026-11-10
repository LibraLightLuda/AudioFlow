package app.audioflow

internal val soundBands = listOf(80, 250, 1000, 4000, 12000)
internal const val SOUND_GAIN_LIMIT = 9
internal const val MAX_SOUND_PRESETS = 12

internal enum class SoundSpace(val label: String) {
    Off("끄기"), Wide("스테레오 확장"), Headphones("헤드폰 공간감"), System("기기 공간 음향"),
}

internal data class SoundProfile(
    val equalizer: Boolean = false,
    val gains: List<Int> = List(soundBands.size) { 0 },
    val space: SoundSpace = SoundSpace.System,
    val strength: Int = 50,
) {
    fun encode() = "1|${if (equalizer) 1 else 0}|${space.name}|$strength|${gains.joinToString(",")}"

    companion object {
        fun decode(raw: String?): SoundProfile {
            val fields = raw?.split('|', limit = 5) ?: return SoundProfile()
            if (fields.size != 5 || fields[0] != "1" || fields[1] !in setOf("0", "1")) return SoundProfile()
            val space = SoundSpace.entries.firstOrNull { it.name == fields[2] } ?: return SoundProfile()
            val strength = fields[3].toIntOrNull()?.takeIf { it in 0..100 } ?: return SoundProfile()
            val gains = fields[4].split(',').mapNotNull { it.toIntOrNull()?.takeIf { gain -> gain in -SOUND_GAIN_LIMIT..SOUND_GAIN_LIMIT } }
            if (gains.size != soundBands.size || fields[4] != gains.joinToString(",")) return SoundProfile()
            return SoundProfile(fields[1] == "1", gains, space, strength)
        }
    }
}

internal data class SoundPreset(val name: String, val profile: SoundProfile)

private fun eqPreset(name: String, vararg gains: Int) =
    SoundPreset(name, SoundProfile(true, gains.toList(), SoundSpace.Off))

internal val builtInSoundPresets = listOf(
    SoundPreset("원음", SoundProfile(space = SoundSpace.Off)),
    eqPreset("저음 강조", 6, 3, 0, -1, -1),
    eqPreset("보컬 선명", -2, -1, 3, 4, 1),
    eqPreset("록", 4, 2, -2, 3, 4),
    eqPreset("재즈", 3, 1, 0, 2, 3),
    eqPreset("클래식", 2, 0, -1, 1, 4),
    eqPreset("팟캐스트", -4, -2, 3, 4, -1),
    SoundPreset("넓은 스테레오", SoundProfile(space = SoundSpace.Wide, strength = 60)),
    SoundPreset("헤드폰 공간감", SoundProfile(space = SoundSpace.Headphones, strength = 55)),
)
