package app.deulleulkka.engine

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 경유하면 얼마나 더 걸리는지 어림한다.
 *
 * 경유지를 넣기 전에 추가 시간을 알려 주는 내비게이션 API가 없어서, 묻는 시점에는 이 어림값을 쓴다.
 * 넣은 뒤에는 내비게이션이 다시 계산한 값으로 확인한다([MomentJudge.exceedsAllowance]).
 *
 * 찜까지의 도로 시간([DriveContext.tripMinToPlace])을 받았으면 그 값으로, 못 받았으면 직선거리로 어림한다.
 * 직선거리 어림은 가는 길에 가까운 찜일수록 0에 가까워져, 나들목으로만 드나드는 길이나 옆길로 들어가는 곳에서
 * 실제보다 크게 짧았다(실제 경로 20건 비교, README). 그래서 도로 시간이 없으면 묻지 않고 보류하며([MomentJudge]),
 * 직선거리 어림은 묻는 판단에 쓰이지 않는다. 지우지 않고 남겨 둔 것은 실제 경로 비교의 처음 방식이라,
 * 실차에서 도로 시간을 받지 못하는 일이 잦으면 대안을 다시 견줄 기준으로 쓰기 위해서다.
 */
object DetourEstimator {
    /** 직선거리를 도로 거리로 바꾸는 보정값의 출발값. 직선거리 어림에만 쓴다([RoadFactorCalibrator]). */
    const val ROAD_FACTOR = 1.3

    /**
     * 찜까지 도로 시간으로 어림할 때 곱하는 값. 찜까지 가는 동안 목적지 쪽으로 나아가지 못한 시간을
     * 돌아 나올 때 한 번 더 쓴다고 본다. 실제 경로 12건을 보고 정한 뒤 다른 8건으로 확인한 값이며, 보정하지 않는다.
     */
    const val TRIP_FACTOR = 2.0

    /** 평균 속도를 알 수 없거나 너무 낮을 때 쓰는 하한(km/h). 정체 중에 어림값이 터무니없이 커지는 것을 막는다. */
    const val MIN_AVERAGE_KPH = 20.0

    private const val EARTH_RADIUS_KM = 6371.0
    private const val MINUTES_PER_HOUR = 60.0

    /**
     * 목적지가 없으면 돌아가는 정도를 알 수 없으므로 null.
     * [roadFactor]는 직선거리로 어림할 때만 쓴다([usesTrip]이 false일 때).
     */
    fun estimateMinutes(ctx: DriveContext, place: Place, roadFactor: Double = ROAD_FACTOR): Double? {
        val dest = ctx.destination ?: return null
        val tripMin = ctx.tripMinToPlace[place.id]
        val remainingMin = ctx.remainingMin
        if (tripMin != null && remainingMin != null) {
            // 찜까지 가는 동안 목적지에 가까워지는 몫(직선으로 본 비율)을 남은 시간에서 빼고 남는 시간
            val directToDest = distanceKm(ctx.lat, ctx.lon, dest.lat, dest.lon)
            val placeToDest = distanceKm(place.lat, place.lon, dest.lat, dest.lon)
            val progress = if (directToDest > 0.0) (directToDest - placeToDest).coerceAtLeast(0.0) / directToDest else 0.0
            return TRIP_FACTOR * (tripMin - progress * remainingMin).coerceAtLeast(0.0)
        }
        val direct = distanceKm(ctx.lat, ctx.lon, dest.lat, dest.lon)
        val via = distanceKm(ctx.lat, ctx.lon, place.lat, place.lon) +
            distanceKm(place.lat, place.lon, dest.lat, dest.lon)
        val extraRoadKm = (via - direct).coerceAtLeast(0.0) * roadFactor
        return extraRoadKm / averageKph(ctx) * MINUTES_PER_HOUR
    }

    /** 이 찜의 어림에 찜까지의 도로 시간을 쓰는지. 아니면 [MomentJudge]가 묻지 않고 보류한다. */
    fun usesTrip(ctx: DriveContext, place: Place): Boolean =
        ctx.destination != null && ctx.tripMinToPlace[place.id] != null && ctx.remainingMin != null

    /** 내비게이션이 알려 주는 남은 거리와 남은 시간으로 구한 평균 속도. 없으면 현재 속도, 그것도 없으면 하한을 쓴다. */
    fun averageKph(ctx: DriveContext): Double {
        val km = ctx.remainingKm
        val min = ctx.remainingMin
        val kph = (if (km != null && min != null && min > 0.0) km / min * MINUTES_PER_HOUR else ctx.speedKph) ?: MIN_AVERAGE_KPH
        return kph.coerceAtLeast(MIN_AVERAGE_KPH)
    }

    /** 첫 좌표에서 둘째 좌표를 바라본 방향(북쪽에서 시계 방향으로 잰 도, 0 이상 360 미만). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + FULL_TURN_DEG) % FULL_TURN_DEG
    }

    private const val FULL_TURN_DEG = 360.0

    /** 두 좌표 사이의 직선거리(km). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }
}
