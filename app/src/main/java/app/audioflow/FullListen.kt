package app.audioflow

// Count actual media progress; waiting, paused time and seeks never stand in for listening.
internal class FullListen {
    private var id: String? = null
    private var valid = false
    private var position = 0L
    private var time = 0L
    private var playing = false
    private var speed = 1f
    private var covered = 0L
    fun sample(video: String, at: Long, now: Long, active: Boolean, rate: Float) {
        if (id != video) {
            id=video; valid=at in 0..250; covered=0; playing=false
        } else {
            val advance=at-position
            if (advance < -250 || advance > ((now-time).coerceAtLeast(0)*speed + 750)) valid=false
            if (playing && advance > 0) covered += advance
        }
        position=at; time=now; playing=active; speed=rate
    }
    fun invalidate() { valid=false }
    fun finish(video: String, at: Long, duration: Long, now: Long): Boolean {
        if (id != video) return false
        sample(video,at,now,false,speed)
        val tolerance=minOf(1000L,duration/100)
        val complete=valid && duration>0 && at>=duration-250 && covered>=duration-tolerance
        id=null; valid=false
        return complete
    }
}
