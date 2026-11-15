package app.deulleulkka

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.deulleulkka.core.Turn
import app.deulleulkka.core.Turns
import app.deulleulkka.engine.Destination

/**
 * debug 빌드에만 있는 시험 입력 받개. DUMP 권한으로 막아 adb(셸)만 보낼 수 있다.
 * 예) adb shell am broadcast -a app.deulleulkka.SIM -p app.deulleulkka --ef speed 40 --ei turn 1200 --es turnType TURN_LEFT
 *     --ei remainSec 5400 --ei remainM 30000 --ei tripSec 198 --ei waypoints 0 --ez parked false --ez turnSignal false
 *     --ez otherAudio false --ez cameraClear true --es dest 37.6,127.5
 *     --es answer yes(probe/yes.pcm을 인식기에 넣음) --es say 들를게(인식 결과로 바로 넣음) --ez clear true
 */
class SimReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val x = intent.extras ?: return
        if (x.getBoolean("clear", false)) SimOverrides.clear()
        if (x.containsKey("speed")) SimOverrides.speedKph = x.getFloat("speed").toDouble()
        if (x.containsKey("turn")) {
            val type = x.getString("turnType") ?: "TURN_LEFT"
            SimOverrides.nextTurn = Turn(x.getInt("turn").toDouble(), Turns.isInterchange(type))
        }
        if (x.containsKey("remainSec")) SimOverrides.remainSeconds = x.getInt("remainSec")
        if (x.containsKey("remainM")) SimOverrides.remainMeters = x.getInt("remainM")
        if (x.containsKey("tripSec")) SimOverrides.tripSeconds = x.getInt("tripSec")
        if (x.containsKey("waypoints")) SimOverrides.waypointCount = x.getInt("waypoints")
        if (x.containsKey("parked")) SimOverrides.parked = x.getBoolean("parked")
        if (x.containsKey("turnSignal")) SimOverrides.turnSignalOn = x.getBoolean("turnSignal")
        if (x.containsKey("otherAudio")) SimOverrides.otherAudioPlaying = x.getBoolean("otherAudio")
        if (x.containsKey("cameraClear")) SimOverrides.cameraClear = x.getBoolean("cameraClear")
        x.getString("dest")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 2 }?.let {
            SimOverrides.destination = Destination(it[0], it[1])
        }
        val runner = FlowRunner.current
        if (x.getBoolean("resetHistory", false)) runner?.simulateResetHistory()
        if (x.getBoolean("restoreVisited", false)) runner?.simulateRestoreVisited()
        if (x.getBoolean("visit", false)) runner?.simulateVisited()
        if (x.containsKey("guidance")) GuidanceTone.play(x.getInt("guidance"))
        if (x.getBoolean("newTrip", false)) runner?.simulateNewTrip()
        x.getString("say")?.let { runner?.simulateHeard(it) }
        x.getString("answer")?.let { name ->
            runCatching { context.assets.open("probe/$name.pcm").use { it.readBytes() } }
                .onSuccess { pcm -> runner?.simulateAudio(pcm) }
                .onFailure { Log.w(FlowRunner.TAG, "시험 대답 파일 없음: $name") }
        }
        Log.i(FlowRunner.TAG, "SIM active=${SimOverrides.active}")
    }
}
