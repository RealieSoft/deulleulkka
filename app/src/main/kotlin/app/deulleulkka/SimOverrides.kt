package app.deulleulkka

import app.deulleulkka.core.Signals
import app.deulleulkka.core.Turn
import app.deulleulkka.engine.Destination

/**
 * 에뮬레이터 시험용으로 넣은 값. 에뮬레이터 내비는 실제 도로 값을 주지 않고 위치를 옮겨도 따라오지 않으며, 속도 API도 없다(README).
 * 그래서 시험에서는 이 값으로 덮어 쓴다. 값을 넣는 받개(SimReceiver)는 debug 빌드에만 있어 출시 앱에서는 늘 비어 있다.
 * 화면에는 "시험 입력" 표시를 띄워 실제 SDK 값과 가린다(기획서 5번 시연 방식).
 */
object SimOverrides {
    @Volatile var speedKph: Double? = null
    @Volatile var destination: Destination? = null
    @Volatile var remainSeconds: Int? = null
    @Volatile var remainMeters: Int? = null
    @Volatile var nextTurn: Turn? = null
    @Volatile var tripSeconds: Int? = null
    @Volatile var waypointCount: Int? = null
    @Volatile var parked: Boolean? = null
    @Volatile var turnSignalOn: Boolean? = null
    @Volatile var otherAudioPlaying: Boolean? = null
    @Volatile var cameraClear: Boolean = false

    val active: Boolean
        get() = listOf(speedKph, destination, remainSeconds, remainMeters, nextTurn, tripSeconds, waypointCount, parked, turnSignalOn, otherAudioPlaying)
            .any { it != null } || cameraClear

    fun clear() {
        speedKph = null
        destination = null
        remainSeconds = null
        remainMeters = null
        nextTurn = null
        tripSeconds = null
        waypointCount = null
        parked = null
        turnSignalOn = null
        otherAudioPlaying = null
        cameraClear = false
    }

    /** [candidateIds]는 도로 시간을 덮어 쓸 찜(지금 판단하는 곳). */
    fun apply(s: Signals, candidateIds: Collection<String>): Signals {
        if (!active) return s
        val trip = tripSeconds
        return s.copy(
            speedKph = speedKph ?: s.speedKph,
            destination = destination ?: s.destination,
            remainSeconds = remainSeconds ?: s.remainSeconds,
            remainMeters = remainMeters ?: s.remainMeters,
            nextTurn = nextTurn ?: s.nextTurn,
            tripSecondsTo = if (trip != null) s.tripSecondsTo + candidateIds.associateWith { trip } else s.tripSecondsTo,
            waypointCount = waypointCount ?: s.waypointCount,
            parked = parked ?: s.parked,
            turnSignalOn = turnSignalOn ?: s.turnSignalOn,
            otherAudioPlaying = otherAudioPlaying ?: s.otherAudioPlaying,
            cameraChannelSeen = s.cameraChannelSeen || cameraClear,
        )
    }
}
