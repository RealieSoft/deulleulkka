package app.deulleulkka

import android.content.Context
import app.deulleulkka.core.SavedState
import app.deulleulkka.core.SavedStateCodec

/** 찜과 묻고 답한 기록을 기기 안에만 둔다(SharedPreferences, 앱 전용). 밖으로 보내지 않는다. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): SavedState = SavedStateCodec.decode(prefs.getString(KEY, null).orEmpty())

    fun save(state: SavedState) {
        prefs.edit().putString(KEY, SavedStateCodec.encode(state)).apply()
    }

    /**
     * 앱이 마지막으로 살아 있던 때의 부팅 횟수와 두 시계(부팅 뒤 경과·깊은 잠을 뺀 경과, ms).
     * 시동을 끈 동안(재부팅 또는 깊은 잠)을 가려내는 데 쓴다.
     */
    data class Alive(val bootCount: Int, val elapsedMs: Long, val uptimeMs: Long)

    fun loadAlive(): Alive? {
        val boot = prefs.getInt(KEY_ALIVE_BOOT, -1)
        val elapsed = prefs.getLong(KEY_ALIVE_ELAPSED, -1L)
        val uptime = prefs.getLong(KEY_ALIVE_UPTIME, -1L)
        return if (elapsed < 0L || uptime < 0L) null else Alive(boot, elapsed, uptime)
    }

    fun saveAlive(alive: Alive) {
        prefs.edit()
            .putInt(KEY_ALIVE_BOOT, alive.bootCount)
            .putLong(KEY_ALIVE_ELAPSED, alive.elapsedMs)
            .putLong(KEY_ALIVE_UPTIME, alive.uptimeMs)
            .apply()
    }

    private companion object {
        const val NAME = "deulleulkka"
        const val KEY = "state"
        const val KEY_ALIVE_BOOT = "aliveBoot"
        const val KEY_ALIVE_ELAPSED = "aliveElapsed"
        const val KEY_ALIVE_UPTIME = "aliveUptime"
    }
}
