package app.deulleulkka.core

import app.deulleulkka.engine.DetourEstimator
import app.deulleulkka.engine.Destination
import app.deulleulkka.engine.DriveContext
import app.deulleulkka.engine.MomentJudge
import app.deulleulkka.engine.Place
import kotlin.math.roundToInt

/** 다음 회전. [interchange]는 나들목 진출입인지(종류를 모르면 null). */
data class Turn(val meters: Double, val interchange: Boolean?)

/**
 * SDK에서 읽어 둔 값. 앱이 콜백마다 고쳐 두고, 판단할 때 [ContextBuilder]가 엔진의 입력으로 바꾼다.
 * 받지 못한 값은 null로 둔다(엔진이 "모름"으로 보고 더 조심한다).
 * 시간·거리는 SDK 단위 그대로 둔다: 남은 시간·찜까지 도로 시간은 초, 거리는 m(navi-data TripInfo·DestinationInfo).
 */
data class Signals(
    val lat: Double? = null,
    val lon: Double? = null,
    val headingDeg: Double? = null,
    val speedKph: Double? = null,
    val destination: Destination? = null,
    val remainSeconds: Int? = null,
    val remainMeters: Int? = null,
    /** 길 안내 중 받은 다음 회전. 길 안내 중인데 아직 받지 못했으면 null. */
    val nextTurn: Turn? = null,
    /** 내비가 알려 준 경유지 수. 받지 못했으면 null(엔진이 모름으로 보고 묻지 않는다). */
    val waypointCount: Int? = null,
    /** 찜 id별 찜까지 도로 시간(초). requestTrip으로 받는다. */
    val tripSecondsTo: Map<String, Int> = emptyMap(),
    /** 가장 가까운 단속 카메라까지 남은 거리(m). 경보가 없으면 null. */
    val cameraMeters: Int? = null,
    /** 이번 이동에서 카메라 경보를 한 번이라도 받았는지. 받은 적이 있어야 "경보 없음"을 "카메라 없음"으로 믿는다. */
    val cameraChannelSeen: Boolean = false,
    val turnSignalOn: Boolean? = null,
    val braking: Boolean? = null,
    val parked: Boolean? = null,
    val energyPercent: Int? = null,
    val passengerPresent: Boolean? = null,
    val otherAudioPlaying: Boolean? = null,
)

object ContextBuilder {
    /** 이 거리 안의 단속 카메라는 "단속 카메라·주의 지점 안내 구간"으로 본다(m). 안내가 시작되는 거리에 맞춤[추정]. */
    const val CAMERA_AHEAD_METERS = 600

    private const val SECONDS_PER_MINUTE = 60.0
    private const val METERS_PER_KM = 1000.0

    /** 위치를 모르면 판단할 수 없어 null. */
    fun build(s: Signals): DriveContext? {
        val lat = s.lat?.takeIf { it.isFinite() } ?: return null
        val lon = s.lon?.takeIf { it.isFinite() } ?: return null
        return DriveContext(
            lat = lat,
            lon = lon,
            speedKph = s.speedKph,
            headingDeg = s.headingDeg,
            destination = s.destination,
            remainingKm = s.remainMeters?.let { it / METERS_PER_KM },
            remainingMin = s.remainSeconds?.let { it / SECONDS_PER_MINUTE },
            tripMinToPlace = s.tripSecondsTo.mapValues { (_, seconds) -> seconds / SECONDS_PER_MINUTE },
            nextTurnMeters = s.nextTurn?.meters?.takeIf { it.isFinite() }?.roundToInt(),
            nextTurnIsInterchange = s.nextTurn?.interchange,
            turnSignalOn = s.turnSignalOn,
            braking = s.braking,
            cautionAhead = when {
                s.cameraMeters != null -> s.cameraMeters < CAMERA_AHEAD_METERS
                s.cameraChannelSeen -> false
                else -> null
            },
            otherAudioPlaying = s.otherAudioPlaying,
            passengerPresent = s.passengerPresent,
            energyPercent = s.energyPercent,
            waypointCount = s.waypointCount,
        )
    }
}

/**
 * 위치를 믿을 수 있는지. 위치 SDK는 서 있을 때 새 위치를 보내지 않기도 해서(에뮬레이터) 오래된 위치만으로는 버리지 않는다.
 * 차가 달리는데([MOVING_KPH] 이상, 차량 속도로 봄) 새 위치가 [STALE_WHILE_MOVING_MS] 넘게 오지 않으면(터널 등) 위치가 멈춘 것이라
 * 모름으로 본다. 그러면 찜까지 거리를 낡은 자리로 재서 묻지 않는다.
 */
object LocationGuard {
    const val MOVING_KPH = 10.0
    const val STALE_WHILE_MOVING_MS = 15_000L

    /** [fixAgeMs]는 마지막으로 새 위치(측위 시각이 바뀐 것)를 받은 뒤 지난 시간, 받은 적이 없으면 null. */
    fun usable(fixAgeMs: Long?, carSpeedKph: Double?): Boolean {
        val moving = carSpeedKph != null && carSpeedKph >= MOVING_KPH
        if (!moving) return true
        return fixAgeMs != null && fixAgeMs <= STALE_WHILE_MOVING_MS
    }
}

object Turns {
    /** navi-data TurnType 이름으로 나들목 진출입인지. 모르는 종류(TURN_UNKNOWN)면 null. */
    fun isInterchange(turnTypeName: String): Boolean? = when {
        turnTypeName == "TURN_UNKNOWN" -> null
        turnTypeName.startsWith("TURN_ENTER_IC") || turnTypeName.startsWith("TURN_EXIT_IC") -> true
        else -> false
    }
}

object Candidates {
    /**
     * 판단할 찜: 지금 자리에서 이 거리 안. 판단 반경(2km)보다 넓게 잡아, 반경을 벗어난 찜이 한동안 "반경 밖"으로 판단되게 한다
     * (그래야 보류 중이던 곳의 반경 이탈이 기록된다, [app.deulleulkka.engine.AskHistory.recordVerdict]).
     */
    const val WATCH_RADIUS_METERS = 2_500.0

    /** 가까운 곳부터 이만큼만 판단한다. 찜까지 도로 시간을 묻는 요청 수도 이 수로 묶인다. */
    const val MAX_CANDIDATES = 8

    private const val METERS_PER_KM = 1000.0

    fun near(lat: Double, lon: Double, places: List<Place>): List<Place> = places
        .map { it to DetourEstimator.distanceKm(lat, lon, it.lat, it.lon) * METERS_PER_KM }
        .filter { (_, meters) -> meters <= WATCH_RADIUS_METERS }
        .sortedWith(compareBy({ (_, meters) -> meters }, { (place, _) -> place.id }))
        .take(MAX_CANDIDATES)
        .map { (place, _) -> place }

    /** 찜까지 도로 시간을 물어 둘 곳: 판단 반경 안이면서 아직 값이 없는 곳. */
    fun needsTrip(lat: Double, lon: Double, candidates: List<Place>, known: Set<String>): List<Place> =
        candidates.filter { it.id !in known && DetourEstimator.distanceKm(lat, lon, it.lat, it.lon) * METERS_PER_KM <= MomentJudge.TRIGGER_RADIUS_METERS }
}

/**
 * 위치 SDK는 속도를 주지 않아(LocationData에 속도 없음), 차량 속도 속성을 읽지 못할 때 잇단 위치로 속도를 어림한다.
 * 시간 간격이 너무 짧거나 길면 쓰지 않고, 차가 낼 수 없는 속도(위치가 튐)는 버린다.
 */
class SpeedEstimator {
    private var last: Fix? = null

    private data class Fix(val lat: Double, val lon: Double, val atMs: Long)

    /** 새 위치를 넣고 어림한 속도(km/h)를 돌려준다. 어림할 수 없으면 null. */
    fun update(lat: Double, lon: Double, atMs: Long): Double? {
        val prev = last
        last = Fix(lat, lon, atMs)
        if (prev == null) return null
        val dtMs = atMs - prev.atMs
        if (dtMs < MIN_INTERVAL_MS || dtMs > MAX_INTERVAL_MS) return null
        val km = DetourEstimator.distanceKm(prev.lat, prev.lon, lat, lon)
        val kph = km / (dtMs / MS_PER_HOUR)
        return kph.takeIf { it.isFinite() && it <= MAX_PLAUSIBLE_KPH }
    }

    companion object {
        const val MIN_INTERVAL_MS = 500L
        const val MAX_INTERVAL_MS = 10_000L
        const val MAX_PLAUSIBLE_KPH = 250.0
        private const val MS_PER_HOUR = 3_600_000.0
    }
}
