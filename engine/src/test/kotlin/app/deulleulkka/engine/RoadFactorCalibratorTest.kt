package app.deulleulkka.engine

import app.deulleulkka.engine.replay.Scenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RoadFactorCalibratorTest {
    private val start = DetourEstimator.ROAD_FACTOR

    /** 어림값 6분, 실제 값은 어림값의 [ratio]배인 경유지 추가를 [times]번 기록한다. */
    private fun recorded(ratio: Double, times: Int, from: RoadFactorCalibrator = RoadFactorCalibrator()) =
        (1..times).fold(from) { c, _ -> c.record(estimatedMin = 6.0, actualMin = 6.0 * ratio, factorUsed = start) }

    @Test
    fun `기록이 세 번보다 적으면 출발값 1점3을 쓴다`() {
        assertEquals(start, recorded(ratio = 2.0, times = 2).factor)
    }

    @Test
    fun `내비게이션이 늘 어림값의 1점5배를 주면 보정값은 1점3의 1점5배가 된다`() {
        assertEquals(start * 1.5, recorded(ratio = 1.5, times = 3).factor, 1e-9)
    }

    @Test
    fun `어림값이 2분보다 짧으면 기록하지 않는다`() {
        val c = RoadFactorCalibrator().record(estimatedMin = 1.0, actualMin = 5.0, factorUsed = start)
        assertEquals(emptyList(), c.samples)
    }

    @Test
    fun `튀는 값 하나는 가운데값이라 보정값을 흔들지 못한다`() {
        val c = recorded(ratio = 1.0, times = 3).record(estimatedMin = 6.0, actualMin = 60.0, factorUsed = start)
        assertEquals(start, c.factor, 1e-9)
    }

    @Test
    fun `한 번의 값은 상한과 하한 안으로 줄여 남긴다`() {
        val c = RoadFactorCalibrator()
            .record(estimatedMin = 6.0, actualMin = 60.0, factorUsed = start)
            .record(estimatedMin = 6.0, actualMin = -3.0, factorUsed = start)
        assertEquals(listOf(RoadFactorCalibrator.MAX_FACTOR, RoadFactorCalibrator.MIN_FACTOR), c.samples)
    }

    @Test
    fun `최근 10번만 남겨 도로 사정이 바뀌면 따라간다`() {
        val c = recorded(ratio = 1.5, times = 10, from = recorded(ratio = 1.0, times = 10))
        assertEquals(RoadFactorCalibrator.MAX_SAMPLES, c.samples.size)
        assertEquals(start * 1.5, c.factor, 1e-9)
    }

    @Test
    fun `보정값이 커지면 같은 곳도 더 걸린다고 보고 허용 시간을 넘으면 묻지 않는다`() {
        // 보정값은 찜까지의 도로 시간을 받지 못해 직선거리로 어림할 때만 쓴다
        val calm = Scenarios.saturdayOuting.last().ctx.copy(tripMinToPlace = emptyMap())
        val atStart = assertNotNull(DetourEstimator.estimateMinutes(calm, Scenarios.bakery))
        val atMax = assertNotNull(DetourEstimator.estimateMinutes(calm, Scenarios.bakery, RoadFactorCalibrator.MAX_FACTOR))
        assertEquals(atStart / start * RoadFactorCalibrator.MAX_FACTOR, atMax, 1e-9)

        val judge = MomentJudge()
        assertIs<Verdict.Ask>(judge.judge(calm, Scenarios.bakery, AskHistory(), 0L).verdict)
        assertIs<Verdict.Skip>(judge.judge(calm, Scenarios.bakery, AskHistory(), 0L, RoadFactorCalibrator.MAX_FACTOR).verdict)
    }
}
