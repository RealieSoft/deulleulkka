package app.deulleulkka

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log

/**
 * 위치 SDK(FusedLocation) 연결. 위치 SDK는 속도를 주지 않는다(LocationData에 속도 없음).
 * initialize()는 위치 서비스에 붙는 데 시간이 걸려, 곧바로 콜백을 걸면 걸리지 않는다(2026-11-15 에뮬레이터: 마지막 위치만 한 번 오고
 * 새 위치가 오지 않음). 그래서 [REGISTER_DELAY_MS] 뒤에 걸고, [STALE_MS] 동안 새 위치가 없으면 다시 건다.
 */
class Location(context: Context, private val main: Handler, private val sink: Sink) {

    fun interface Sink {
        /** [atMs]는 받은 때(부팅 뒤 경과), [fixMs]는 위치 SDK가 잰 때(측위 시각). 다시 넣은 마지막 위치는 [fixMs]가 바뀌지 않는다. */
        fun onLocation(lat: Double, lon: Double, headingDeg: Double?, atMs: Long, fixMs: Long)
    }

    private val fused = FusedLocation(context)
    private var started = false
    private var lastUpdateMs = 0L

    private val listener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            val d = data ?: return
            main.post {
                lastUpdateMs = SystemClock.elapsedRealtime()
                sink.onLocation(d.latitude, d.longitude, d.heading.toDouble().takeIf { it.isFinite() }, lastUpdateMs, d.timestampMillis)
            }
        }

        override fun onError(e: Exception) {
            Log.w(FlowRunner.TAG, "위치 콜백 오류 ${e::class.java.simpleName}")
        }
    }

    private val register = Runnable {
        if (!started) return@Runnable
        runCatching { fused.unregisterFusedLocationCallback(listener) }
        runCatching {
            fused.registerFusedLocationCallback(listener)
            fused.getLastKnownLocation(listener)
        }.onFailure { Log.w(FlowRunner.TAG, "위치 콜백 등록 실패 ${it::class.java.simpleName}") }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!started) return
            if (SystemClock.elapsedRealtime() - lastUpdateMs > STALE_MS) register.run()
            main.postDelayed(this, STALE_MS)
        }
    }

    /** 위치 권한이 있을 때만 부른다. 지원하지 않는 기기면 false. */
    fun start(): Boolean {
        if (started) return true
        if (!fused.isSupported()) return false
        fused.initialize()
        started = true
        lastUpdateMs = SystemClock.elapsedRealtime()
        main.postDelayed(register, REGISTER_DELAY_MS)
        main.postDelayed(watchdog, STALE_MS)
        return true
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(register)
        main.removeCallbacks(watchdog)
        runCatching { fused.unregisterFusedLocationCallback(listener) }
        runCatching { fused.release() }
    }

    private companion object {
        const val REGISTER_DELAY_MS = 1_500L
        const val STALE_MS = 10_000L
    }
}
