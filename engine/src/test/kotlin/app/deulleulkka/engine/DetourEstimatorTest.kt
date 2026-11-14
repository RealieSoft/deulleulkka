package app.deulleulkka.engine

import app.deulleulkka.engine.replay.Scenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetourEstimatorTest {
    private val calm = Scenarios.saturdayOuting.last().ctx

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
}
