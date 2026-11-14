package app.deulleulkka.engine

import app.deulleulkka.engine.replay.Scenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetourEstimatorTest {
    /** 직선거리 어림을 보는 테스트가 많아 찜까지의 도로 시간은 비워 둔다. 도로 시간으로 어림하는 경우는 [routeCase]로 넣는다. */
    private val calm = Scenarios.saturdayOuting.last().ctx.copy(tripMinToPlace = emptyMap())

    @Test
    fun `서울시청에서 부산시청까지 직선거리는 약 325km다`() {
        val km = DetourEstimator.distanceKm(37.5663, 126.9779, 35.1798, 129.0750)
        assertTrue(km in 320.0..330.0, "계산값 $km")
    }

    @Test
    fun `가는 길 위에 있는 곳은 더 걸리는 시간이 거의 없다`() {
        val onRoute = Scenarios.bakery.copy(lat = 37.6000, lon = 127.3500)
        val minutes = assertNotNull(DetourEstimator.estimateMinutes(calm, onRoute))
        assertTrue(minutes < 0.5, "계산값 $minutes")
    }

    @Test
    fun `목적지가 없으면 어림할 수 없다`() {
        assertNull(DetourEstimator.estimateMinutes(calm.copy(destination = null), Scenarios.bakery))
    }

    @Test
    fun `평균 속도는 남은 거리와 남은 시간으로 구한다`() {
        assertEquals(60.0, DetourEstimator.averageKph(calm.copy(remainingKm = 30.0, remainingMin = 30.0)))
    }

    @Test
    fun `정체로 평균 속도가 아주 낮으면 하한을 쓴다`() {
        assertEquals(
            DetourEstimator.MIN_AVERAGE_KPH,
            DetourEstimator.averageKph(calm.copy(remainingKm = 5.0, remainingMin = 60.0)),
        )
    }

    @Test
    fun `남은 거리와 시간을 모르면 현재 속도를 쓴다`() {
        assertEquals(62.0, DetourEstimator.averageKph(calm.copy(remainingKm = null, remainingMin = null)))
    }

    /**
     * 실제 경로 비교(README)의 검증용 경우를 그대로 넣는다. 좌표는 도로에 붙인 값,
     * 남은 거리·시간과 찜까지의 도로 시간은 길찾기 값, 기대값은 비교 스크립트가 계산한 값이다.
     */
    private fun routeCase(
        a: Pair<Double, Double>, p: Pair<Double, Double>, b: Pair<Double, Double>,
        remainingKm: Double, remainingMin: Double, tripMin: Double?,
    ): Pair<DriveContext, Place> {
        val place = Scenarios.bakery.copy(lat = p.first, lon = p.second)
        val ctx = calm.copy(
            lat = a.first, lon = a.second,
            destination = Destination(b.first, b.second),
            remainingKm = remainingKm, remainingMin = remainingMin,
            tripMinToPlace = if (tripMin == null) emptyMap() else mapOf(place.id to tripMin),
        )
        return ctx to place
    }

    // 경주 대릉원(시내): 실제로는 2.85분 더 걸림
    private val gyeongju = Triple(35.840585 to 129.232388, 35.838245 to 129.211509, 35.840144 to 129.100185)

    @Test
    fun `찜까지의 도로 시간을 받으면 실제 경로 비교와 같은 값을 낸다`() {
        val (a, p, b) = gyeongju
        val (ctx, place) = routeCase(a, p, b, 15.8578, 17.740000000000002, 4.241666666666666)
        assertTrue(DetourEstimator.usesTrip(ctx, place))
        assertEquals(2.886730676986467, assertNotNull(DetourEstimator.estimateMinutes(ctx, place)), 1e-9)

        // 담양 메타세쿼이아길(고속도로에서 나들목으로 빠짐): 실제 29.55분, 이 어림 51.45분
        val (ctx2, place2) = routeCase(
            35.30985 to 127.00197, 35.325905 to 126.994802, 35.37431 to 127.137291,
            16.0654, 13.895000000000001, 25.935,
        )
        assertEquals(51.45038030785543, assertNotNull(DetourEstimator.estimateMinutes(ctx2, place2)), 1e-9)
    }

    @Test
    fun `찜까지 가는 동안 목적지에 그만큼 가까워지면 더 걸리는 시간은 0이다`() {
        // 여수 이순신광장(시내): 실제로는 2.91분 더 걸렸는데 0으로 나온 경우
        val (ctx, place) = routeCase(
            34.752714 to 127.727488, 34.738134 to 127.738332, 34.591888 to 127.803546,
            27.2457, 32.67, 3.0266666666666664,
        )
        assertEquals(0.0, assertNotNull(DetourEstimator.estimateMinutes(ctx, place)))
    }

    @Test
    fun `찜까지의 도로 시간이 없으면 직선거리로 어림한다`() {
        val (a, p, b) = gyeongju
        val (ctx, place) = routeCase(a, p, b, 15.8578, 17.740000000000002, null)
        assertFalse(DetourEstimator.usesTrip(ctx, place))
        assertEquals(0.029365807208530055, assertNotNull(DetourEstimator.estimateMinutes(ctx, place)), 1e-9)
    }

    @Test
    fun `남은 시간을 모르면 찜까지의 도로 시간이 있어도 직선거리로 어림한다`() {
        val (a, p, b) = gyeongju
        val (withTrip, place) = routeCase(a, p, b, 15.8578, 17.740000000000002, 4.241666666666666)
        val noRemaining = withTrip.copy(remainingKm = null, remainingMin = null)
        assertFalse(DetourEstimator.usesTrip(noRemaining, place))
        assertEquals(
            DetourEstimator.estimateMinutes(noRemaining.copy(tripMinToPlace = emptyMap()), place),
            DetourEstimator.estimateMinutes(noRemaining, place),
        )
    }

    @Test
    fun `찜까지의 도로 시간으로 어림할 때는 도로 보정값을 쓰지 않는다`() {
        val (a, p, b) = gyeongju
        val (ctx, place) = routeCase(a, p, b, 15.8578, 17.740000000000002, 4.241666666666666)
        assertEquals(
            DetourEstimator.estimateMinutes(ctx, place),
            DetourEstimator.estimateMinutes(ctx, place, roadFactor = RoadFactorCalibrator.MAX_FACTOR),
        )
    }
}
