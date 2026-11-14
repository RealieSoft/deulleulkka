package app.deulleulkka.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 경유하면 얼마나 더 걸리는지 어림한다.
 *
 * 경유지를 넣기 전에 정확한 값을 알려 주는 내비게이션 API가 없어서, 묻는 시점에는 이 어림값을 쓴다.
 * 넣은 뒤에는 내비게이션이 다시 계산한 값으로 확인한다([MomentJudge.exceedsAllowance]).
 */
object DetourEstimator {
    /** 직선거리를 도로 거리로 바꾸는 보정값의 출발값. 쓰면서 [RoadFactorCalibrator]가 이 차량의 내비게이션 값에 맞춘다. */
    const val ROAD_FACTOR = 1.3

    /** 평균 속도를 알 수 없거나 너무 낮을 때 쓰는 하한(km/h). 정체 중에 어림값이 터무니없이 커지는 것을 막는다. */
    const val MIN_AVERAGE_KPH = 20.0

    private const val EARTH_RADIUS_KM = 6371.0
    private const val MINUTES_PER_HOUR = 60.0

    /** 목적지가 없으면 돌아가는 정도를 알 수 없으므로 null. */
    fun estimateMinutes(ctx: DriveContext, place: Place, roadFactor: Double = ROAD_FACTOR): Double? {
        val dest = ctx.destination ?: return null
        val direct = distanceKm(ctx.lat, ctx.lon, dest.lat, dest.lon)
        val via = distanceKm(ctx.lat, ctx.lon, place.lat, place.lon) +
            distanceKm(place.lat, place.lon, dest.lat, dest.lon)
        val extraRoadKm = (via - direct).coerceAtLeast(0.0) * roadFactor
        return extraRoadKm / averageKph(ctx) * MINUTES_PER_HOUR
    }

    /** 내비게이션이 알려 주는 남은 거리와 남은 시간으로 구한 평균 속도. 없으면 현재 속도를 쓴다. */
    fun averageKph(ctx: DriveContext): Double {
        val km = ctx.remainingKm
        val min = ctx.remainingMin
        val kph = if (km != null && min != null && min > 0.0) km / min * MINUTES_PER_HOUR else ctx.speedKph
        return kph.coerceAtLeast(MIN_AVERAGE_KPH)
    }

    /** 두 좌표 사이의 직선거리(km). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }
}
