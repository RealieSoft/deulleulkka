package app.deulleulkka.engine

import app.deulleulkka.engine.replay.Scenarios
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 기획서의 판단 표와 시나리오 A, C, D를 그대로 옮긴 테스트. */
class MomentJudgeTest {
    private val judge = MomentJudge()
    private val bakery = Scenarios.bakery

    /** 토요일 오전 11:12, 동승자가 있고 직선 구간인 장면. */
    private val calm = Scenarios.saturdayOuting.last().ctx
    private val today = 0L

    private fun verdict(ctx: DriveContext, history: AskHistory = AskHistory(), place: Place = bakery): Verdict =
        judge.judge(ctx, place, history, today).verdict

    @Test
    fun `시나리오 A 동승자가 있고 직선 구간이면 약 6분 우회를 묻는다`() {
        val v = verdict(calm)
        assertIs<Verdict.Ask>(v)
        assertEquals(6, v.detourMin.roundToInt())
    }

    @Test
    fun `시나리오 C 목적지가 회사면 묻지 않는다`() {
        val decision = judge.judge(Scenarios.mondayCommute.single().ctx, bakery, AskHistory(), today)
        assertEquals(Verdict.Skip("목적지가 회사"), decision.verdict)
    }

    @Test
    fun `시나리오 C 묻지 않기로 한 뒤의 항목은 보지 않는다`() {
        val checks = judge.judge(Scenarios.mondayCommute.single().ctx, bakery, AskHistory(), today).checks
        assertEquals(
            listOf(Outcome.PASS, Outcome.SKIP) + List(5) { Outcome.NOT_CHECKED },
            checks.map { it.outcome },
        )
    }

    @Test
    fun `시나리오 D 우회전 300m 전이고 방향지시등이 켜져 있으면 보류했다가 직선 구간에서 묻는다`() {
        val (beforeTurn, afterTurn) = Scenarios.saturdayOuting.map { it.ctx }
        assertIs<Verdict.Hold>(verdict(beforeTurn))
        assertIs<Verdict.Ask>(verdict(afterTurn))
    }

    @Test
    fun `목적지가 없으면 묻지 않는다`() {
        assertEquals(Verdict.Skip("목적지 없음"), verdict(Scenarios.noDestination.single().ctx))
    }

    @Test
    fun `혼자 탄 이동이면 허용 우회 시간이 절반이라 6분 우회는 묻지 않는다`() {
        val solo = calm.copy(passengerPresent = false)
        assertEquals(5.0, judge.allowedDetourMin(solo))
        assertIs<Verdict.Skip>(verdict(solo))
    }

    @Test
    fun `착좌 정보가 없는 차량에서는 허용 시간을 줄이지 않는다`() {
        val unknown = calm.copy(passengerPresent = null)
        assertEquals(10.0, judge.allowedDetourMin(unknown))
        assertIs<Verdict.Ask>(verdict(unknown))
    }

    @Test
    fun `우회 시간이 허용 범위를 넘으면 묻지 않는다`() {
        val farPlace = bakery.copy(lat = 37.7600)
        assertIs<Verdict.Skip>(verdict(calm, place = farPlace))
    }

    @Test
    fun `잔량이 20퍼센트 아래면 묻지 않는다`() {
        assertIs<Verdict.Skip>(verdict(calm.copy(energyPercent = 19)))
        assertIs<Verdict.Ask>(verdict(calm.copy(energyPercent = 20)))
    }

    @Test
    fun `경유지가 이미 있으면 묻지 않는다`() {
        assertIs<Verdict.Skip>(verdict(calm.copy(waypointCount = 1)))
        assertIs<Verdict.Ask>(verdict(calm.copy(waypointCount = 0)))
    }

    @Test
    fun `멈춰 있어도 방향지시등이 켜져 있거나 회전이 100m 안이면 보류한다`() {
        val stopped = calm.copy(speedKph = 0.0)
        assertIs<Verdict.Hold>(verdict(stopped.copy(turnSignalOn = true)))
        assertIs<Verdict.Hold>(verdict(stopped.copy(nextTurnMeters = 80)))
        assertIs<Verdict.Ask>(verdict(stopped.copy(nextTurnMeters = 150)))
    }

    @Test
    fun `안전 신호를 확인할 수 없는 차종은 완전히 멈춘 때만 묻는다`() {
        for (unknown in listOf(calm.copy(turnSignalOn = null), calm.copy(braking = null), calm.copy(otherAudioPlaying = null))) {
            assertIs<Verdict.Hold>(verdict(unknown))
            assertIs<Verdict.Ask>(verdict(unknown.copy(speedKph = 0.0)))
        }
    }

    @Test
    fun `찜까지 1분 주행 거리보다 가까우면 급하게 빠지지 않도록 보류한다`() {
        // 찜에서 동쪽으로 약 500m 떨어진 곳
        val near = calm.copy(lat = bakery.lat, lon = bakery.lon + 0.0057)
        assertIs<Verdict.Hold>(verdict(near.copy(speedKph = 60.0))) // 1분이면 1km
        assertIs<Verdict.Ask>(verdict(near.copy(speedKph = 20.0))) // 1분이면 약 333m
        assertIs<Verdict.Hold>(verdict(near.copy(lon = bakery.lon + 0.0020, speedKph = 0.0))) // 약 180m는 멈춰 있어도 보류
    }

    @Test
    fun `달리는 중에 브레이크를 밟고 있으면 보류한다`() {
        assertIs<Verdict.Hold>(verdict(calm.copy(braking = true)))
    }

    @Test
    fun `완전히 멈춘 때는 브레이크를 밟고 있어도 여유 구간이다`() {
        assertIs<Verdict.Ask>(verdict(calm.copy(speedKph = 0.0, braking = true)))
    }

    @Test
    fun `통화 중이거나 안내 음성이 나오면 멈춰 있어도 기다린다`() {
        assertIs<Verdict.Hold>(verdict(calm.copy(speedKph = 0.0, otherAudioPlaying = true)))
    }

    @Test
    fun `단속 카메라나 주의 지점 안내 구간에서는 보류한다`() {
        assertIs<Verdict.Hold>(verdict(calm.copy(cautionAhead = true)))
    }

    @Test
    fun `나들목 진출입은 1km 앞에서도 보류한다`() {
        assertIs<Verdict.Hold>(verdict(calm.copy(nextTurnMeters = 1000, nextTurnIsInterchange = true)))
        assertIs<Verdict.Ask>(verdict(calm.copy(nextTurnMeters = 1000, nextTurnIsInterchange = false)))
    }

    @Test
    fun `속도가 빠르면 더 멀리서부터 조작 구간으로 본다`() {
        assertIs<Verdict.Hold>(verdict(calm.copy(speedKph = 100.0, nextTurnMeters = 700)))
        assertIs<Verdict.Ask>(verdict(calm.copy(speedKph = 62.0, nextTurnMeters = 700)))
    }

    @Test
    fun `한 번의 이동에 두 번까지만 묻는다`() {
        assertIs<Verdict.Ask>(verdict(calm, AskHistory(askedThisTrip = listOf("a"))))
        assertIs<Verdict.Skip>(verdict(calm, AskHistory(askedThisTrip = listOf("a", "b"))))
    }

    @Test
    fun `이번 이동에서 이미 물은 곳은 다시 묻지 않는다`() {
        assertIs<Verdict.Skip>(verdict(calm, AskHistory(askedThisTrip = listOf(bakery.id))))
    }

    @Test
    fun `경유지를 넣은 뒤 실제 값이 허용 범위를 넘으면 다시 묻는다`() {
        assertFalse(judge.exceedsAllowance(6.0, calm))
        assertTrue(judge.exceedsAllowance(12.0, calm))
        assertTrue(judge.exceedsAllowance(6.0, calm.copy(passengerPresent = false)))
    }

    @Test
    fun `통과한 곳이 여럿이면 먹을 곳은 식사 시간대에 볼 곳은 해 지기 전에 먼저 묻는다`() {
        val lookout = Place("lookout", "해오름 전망대", PlaceKind.SEE, lat = 37.6700, lon = 127.2600)
        val places = listOf(bakery, lookout)
        val afternoon = calm.copy(minuteOfDay = 15 * 60 + 30) // 식사 시간대가 아니고 해 지기 전
        val evening = calm.copy(minuteOfDay = 18 * 60) // 식사 시간대이고 해가 진 뒤
        assertEquals(lookout, judge.pickFirst(afternoon, places))
        assertEquals(bakery, judge.pickFirst(evening, places))
        // 둘 다 좋은 때면 덜 돌아가는 곳부터
        assertEquals(bakery, judge.pickFirst(calm, places))
    }
}
