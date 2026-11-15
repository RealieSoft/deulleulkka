package app.deulleulkka.engine

import app.deulleulkka.engine.replay.Scenarios
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 기획서의 판단 표와 시나리오 A, C, D를 그대로 옮긴 테스트. */
class MomentJudgeTest {
    private val judge = MomentJudge()
    private val bakery = Scenarios.bakery

    /** 토요일 오전 11:12, 동승자가 있고 직선 구간인 장면. */
    private val calm = Scenarios.saturdayOuting.last().ctx
    private val today = 0L

    /**
     * 차에서 정북으로 약 1.6km 떨어진 찜. 차를 북쪽으로 달리게 두면([towardNorth]) 찜이 정면이고,
     * 목적지(동쪽)에서는 차보다 멀어 찜까지 가는 동안 목적지 쪽으로 나아가는 몫이 0이다.
     * 그래서 어림한 우회 시간이 찜까지 도로 시간의 정확히 두 배가 되어, 경계값을 그대로 넣을 수 있다.
     */
    private val north = Place("north", "북쪽 찜", lat = calm.lat + 0.0144, lon = calm.lon)

    private fun towardNorth(tripMin: Double, passengerPresent: Boolean? = true) =
        calm.copy(headingDeg = 0.0, tripMinToPlace = mapOf(north.id to tripMin), passengerPresent = passengerPresent)

    private fun verdict(ctx: DriveContext, history: AskHistory = AskHistory(), place: Place = bakery): Verdict =
        judge.judge(ctx, place, history, today).verdict

    private fun row(ctx: DriveContext, question: String, place: Place = bakery, j: MomentJudge = judge): Check =
        j.judge(ctx, place, AskHistory(), today).checks.single { it.question == question }

    @Test
    fun `시나리오 A 동승자가 있고 직선 구간이면 약 6분 우회를 묻는다`() {
        val v = verdict(calm)
        assertIs<Verdict.Ask>(v)
        assertEquals(6, v.detourMin.roundToInt())
    }

    @Test
    fun `찜까지의 도로 시간으로 어림한 값이 허용을 넘으면 묻지 않는다`() {
        val far = calm.copy(tripMinToPlace = mapOf(bakery.id to 30.0))
        assertTrue(DetourEstimator.usesTrip(far, bakery))
        val decision = judge.judge(far, bakery, AskHistory(), today)
        assertIs<Verdict.Skip>(decision.verdict)
        assertEquals(Outcome.SKIP, decision.checks.single { it.question == "얼마나 더 걸리나" }.outcome)
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
            listOf(Outcome.PASS, Outcome.SKIP) + List(4) { Outcome.NOT_CHECKED },
            checks.map { it.outcome },
        )
    }

    @Test
    fun `시나리오 D 우회전 300m 전이고 방향지시등이 켜져 있으면 보류했다가 직선 구간에서 묻는다`() {
        val (beforeTurn, afterTurn) = Scenarios.saturdayOuting.map { it.ctx }
        assertEquals(Verdict.Hold("방향지시등 켜짐, 300m 앞 회전"), verdict(beforeTurn))
        assertIs<Verdict.Ask>(verdict(afterTurn))
    }

    @Test
    fun `시나리오 D 보류한 사이 반경을 벗어났다면 그 이동에서는 묻지 않는다`() {
        val (beforeTurn, afterTurn) = Scenarios.saturdayOuting.map { it.ctx }
        val held = AskHistory().recordVerdict(bakery.id, assertIs<Verdict.Hold>(verdict(beforeTurn)))
        val left = held.recordRadiusExit(bakery.id)
        assertEquals(Verdict.Skip("보류한 사이 반경을 벗어났던 곳 (이번 이동에서는 묻지 않음)"), verdict(afterTurn, left))
        // 벗어나지 않았으면 직선 구간에서 묻고, 다음 이동에서는 다시 묻는다
        assertIs<Verdict.Ask>(verdict(afterTurn, held))
        assertIs<Verdict.Ask>(verdict(afterTurn, left.startNewTrip()))
    }

    @Test
    fun `찜까지 정확히 2km면 판단 반경 안이고 조금이라도 멀면 밖이다`() {
        // 적도 위에서 경도만 다른 두 점. 이 경도 차는 거리 계산이 정확히 2000.0m가 되는 값이다
        val here = calm.copy(lat = 0.0, lon = 0.0)
        val at2km = bakery.copy(lat = 0.0, lon = 0.017986432118374612)
        assertEquals(2000.0, DetourEstimator.distanceKm(0.0, 0.0, 0.0, at2km.lon) * 1000)
        assertTrue(judge.inTriggerRadius(here, at2km))
        assertFalse(judge.inTriggerRadius(here, at2km.copy(lon = Math.nextUp(at2km.lon))))
    }

    @Test
    fun `보류된 적 없이 반경을 벗어났던 곳은 다시 들어오면 판단한다`() {
        assertIs<Verdict.Ask>(verdict(calm, AskHistory().recordRadiusExit(bakery.id)))
    }

    @Test
    fun `보류했다가 지나친 곳으로 묻지 않은 뒤 반경을 벗어났다면 굽은 길로 다시 들어올 때 판단한다`() {
        val (beforeTurn, afterTurn) = Scenarios.saturdayOuting.map { it.ctx }
        var history = AskHistory().recordVerdict(bakery.id, assertIs<Verdict.Hold>(verdict(beforeTurn)))
        // 찜을 동쪽으로 약 1.5km 지나친 자리: 보류가 아니라 '이미 지나친 곳'으로 묻지 않는다
        val passed = verdict(afterTurn.copy(lat = bakery.lat, lon = bakery.lon + 0.0170), history)
        assertEquals(Verdict.Skip("이미 지나친 곳 (되돌아가야 함)"), passed)
        history = history.recordVerdict(bakery.id, passed).recordRadiusExit(bakery.id)
        // 보류 중에 벗어난 것이 아니므로, 굽은 길로 앞쪽에서 다시 들어오면 다시 판단한다(기획서 판단 표의 지나친 찜)
        assertIs<Verdict.Ask>(verdict(afterTurn, history))
    }

    @Test
    fun `속도가 NaN이나 무한대면 읽지 못한 것으로 보고 보류한다`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(Verdict.Hold("속도 모름"), verdict(calm.copy(speedKph = bad)), "속도 $bad")
        }
    }

    @Test
    fun `음수 속도(후진)는 멈춘 것이 아니라 속도를 읽지 못한 것과 같이 보류한다`() {
        assertEquals(Verdict.Hold("속도 모름"), verdict(calm.copy(speedKph = -8.0)))
        assertEquals(Verdict.Hold("속도 모름"), verdict(calm.copy(speedKph = -0.5)))
        // -0.0은 0과 같이 완전히 멈춘 것이다
        assertIs<Verdict.Ask>(verdict(calm.copy(speedKph = -0.0)))
    }

    @Test
    fun `묻는 말의 분은 판단 근거 문구의 분과 같다`() {
        // 설정 7분에 혼자 탄 이동이면 허용 3.5분. 어림 3.5분은 반올림하면 4분이라 근거에는 3.5분으로 적는다
        val seven = MomentJudge(Settings(maxDetourMin = 7.0))
        val ctx = towardNorth(tripMin = 1.75, passengerPresent = false)
        val ask = assertIs<Verdict.Ask>(seven.judge(ctx, north, AskHistory(), today).verdict)
        val allowed = seven.allowedDetourMin(ctx)
        assertEquals("약 3.5분 더 걸림 (3.5분까지)", row(ctx, "얼마나 더 걸리나", north, seven).seen)
        assertEquals(
            "찜해 둔 북쪽 찜이 가는 길 근처에 있어요. 약 3.5분 더 걸리는데 들를까요?",
            MomentJudge.question(north, ask.detourMin, allowed),
        )
        // 허용 시간이 정수 분이면 둘 다 반올림한 정수 분이다
        assertEquals("약 6분 더 걸림 (10분까지)", row(calm, "얼마나 더 걸리나").seen)
        val calmAsk = assertIs<Verdict.Ask>(verdict(calm))
        assertEquals(
            "찜해 둔 밀밭 베이커리가 가는 길 근처에 있어요. 약 6분 더 걸리는데 들를까요?",
            MomentJudge.question(bakery, calmAsk.detourMin, judge.allowedDetourMin(calm)),
        )
    }

    @Test
    fun `찜까지 도로 시간이나 남은 시간이 NaN·무한대·음수면 받지 못한 것으로 보고 보류한다`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertEquals(
                Verdict.Hold("찜까지 도로 시간 없음 (받으면 다시 판단)"),
                verdict(calm.copy(tripMinToPlace = mapOf(bakery.id to bad))),
                "도로 시간 $bad",
            )
            assertEquals(
                Verdict.Hold("남은 시간 정보 없음 (받으면 다시 판단)"),
                verdict(calm.copy(remainingMin = bad)),
                "남은 시간 $bad",
            )
        }
    }

    @Test
    fun `단속 카메라·주의 지점이나 다음 회전의 종류를 확인할 수 없으면 완전히 멈춘 때만 묻는다`() {
        val noCaution = calm.copy(cautionAhead = null)
        assertEquals(Verdict.Hold("단속 카메라·주의 지점 확인 불가 (완전히 멈춘 때만 물음)"), verdict(noCaution))
        assertIs<Verdict.Ask>(verdict(noCaution.copy(speedKph = 0.0)))
        val noKind = calm.copy(nextTurnIsInterchange = null)
        assertEquals(Verdict.Hold("다음 회전 종류 확인 불가 (완전히 멈춘 때만 물음)"), verdict(noKind))
        assertIs<Verdict.Ask>(verdict(noKind.copy(speedKph = 0.0)))
        // 다음 회전이 없으면(목적지 없음) 그 종류는 보지 않는다
        val noDestination = Scenarios.noDestination.single().ctx.copy(nextTurnIsInterchange = null)
        assertEquals(Verdict.Skip("목적지 없음"), verdict(noDestination))
    }

    @Test
    fun `찜이 판단 반경 2km 밖이면 다른 조건이 좋아도 묻지 않는다`() {
        val far = north.copy(lat = calm.lat + 0.0200) // 정북 약 2.2km
        val ctx = towardNorth(tripMin = 1.0).copy(tripMinToPlace = mapOf(far.id to 1.0))
        assertFalse(judge.inTriggerRadius(ctx, far))
        assertEquals(Verdict.Skip("찜까지 2.2km (판단 반경 2km 밖)", outsideTriggerRadius = true), verdict(ctx, place = far))
        assertIs<Verdict.Ask>(verdict(towardNorth(tripMin = 1.0), place = north))
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
        val over = towardNorth(tripMin = 6.0) // 어림 12분
        assertEquals(Verdict.Skip("약 12분 더 걸림 (10분까지)"), verdict(over, place = north))
        assertEquals(Outcome.SKIP, row(over, "얼마나 더 걸리나", north).outcome)
    }

    @Test
    fun `허용 시간과 같으면 묻고 조금이라도 넘으면 묻지 않는다`() {
        assertEquals(Verdict.Ask(10.0), verdict(towardNorth(tripMin = 5.0), place = north))
        assertIs<Verdict.Skip>(verdict(towardNorth(tripMin = 5.0 + 1e-9), place = north))
        // 혼자 탄 이동은 5분이 경계다
        assertEquals(Verdict.Ask(5.0), verdict(towardNorth(tripMin = 2.5, passengerPresent = false), place = north))
        assertIs<Verdict.Skip>(verdict(towardNorth(tripMin = 2.5 + 1e-9, passengerPresent = false), place = north))
    }

    @Test
    fun `근거 문구의 분은 반올림 때문에 판정과 어긋나 보이지 않는다`() {
        // 반올림하면 허용 시간과 같아지는데 묻지 않는 경우는 소수 첫째 자리까지(올려서) 보인다
        assertEquals(
            Verdict.Skip("약 5.4분 더 걸림 (5분까지)"),
            verdict(towardNorth(tripMin = 2.7, passengerPresent = false), place = north),
        )
        assertEquals(Verdict.Skip("약 10.3분 더 걸림 (10분까지)"), verdict(towardNorth(tripMin = 5.15), place = north))
        assertEquals(Verdict.Skip("약 10.1분 더 걸림 (10분까지)"), verdict(towardNorth(tripMin = 5.0 + 1e-9), place = north))
        // 반올림해도 어긋나지 않으면 묻는 말과 같은 정수 분으로 보인다
        assertEquals(Verdict.Skip("약 11분 더 걸림 (10분까지)"), verdict(towardNorth(tripMin = 5.3), place = north))
        assertEquals("약 10분 더 걸림 (10분까지)", row(towardNorth(tripMin = 4.8), "얼마나 더 걸리나", north).seen)
        assertEquals("약 6분 더 걸림 (10분까지)", row(calm, "얼마나 더 걸리나").seen)
        // 허용 시간이 정수 분이 아니면(설정 7분, 혼자) 반올림하면 넘어 보이는 통과는 소수 첫째 자리까지(내려서) 보인다
        val seven = MomentJudge(Settings(maxDetourMin = 7.0))
        val solo35 = row(towardNorth(tripMin = 1.75, passengerPresent = false), "얼마나 더 걸리나", north, seven)
        assertEquals("약 3.5분 더 걸림 (3.5분까지)" to Outcome.PASS, solo35.seen to solo35.outcome)
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
    fun `안전 신호를 확인할 수 없는 차종은 완전히 멈춘 때만 묻고 방향지시등을 모르면 멈춰도 묻지 않는다`() {
        for (unknown in listOf(calm.copy(braking = null), calm.copy(otherAudioPlaying = null))) {
            assertIs<Verdict.Hold>(verdict(unknown))
            assertIs<Verdict.Ask>(verdict(unknown.copy(speedKph = 0.0)))
        }
        // 방향지시등은 멈춘 때에도 보는 신호다. 읽지 못한 값을 '꺼짐'으로 보지 않는다
        val noSignal = calm.copy(turnSignalOn = null)
        assertIs<Verdict.Hold>(verdict(noSignal))
        assertEquals(Verdict.Hold("멈춰 있지만 방향지시등 확인 불가"), verdict(noSignal.copy(speedKph = 0.0)))
    }

    @Test
    fun `길 안내 중인데 다음 회전 정보가 없으면 멈춰 있어도 묻지 않는다`() {
        val noTurn = calm.copy(nextTurnMeters = null)
        assertEquals(Verdict.Hold("다음 회전 확인 불가 (완전히 멈춘 때만 물음)"), verdict(noTurn))
        // 다음 회전은 멈춘 때에도 보는 신호다(100m 안이면 보류). 읽지 못한 값을 '회전 없음'으로 보지 않는다
        assertEquals(Verdict.Hold("멈춰 있지만 다음 회전 확인 불가"), verdict(noTurn.copy(speedKph = 0.0)))
        // 목적지가 없으면 안내가 없어 회전 정보가 없는 것이 맞다. 그때 묻지 않는 까닭은 목적지 없음이다
        val noDestination = Scenarios.noDestination.single().ctx
        assertNull(noDestination.nextTurnMeters)
        assertEquals(Outcome.PASS, row(noDestination, "지금 말 걸어도 되나").outcome)
        assertEquals(Verdict.Skip("목적지 없음"), verdict(noDestination))
    }

    @Test
    fun `달리는 중에는 방향지시등만 켜져도 보류한다`() {
        // 회전은 1.4km 앞이라 회전 규칙으로는 막히지 않는다
        assertEquals(Verdict.Hold("방향지시등 켜짐, 1.4km 앞 회전"), verdict(calm.copy(turnSignalOn = true)))
    }

    @Test
    fun `속도가 낮아도 다음 회전이 500m 안이면 보류한다`() {
        // 시속 30km의 30초는 250m라 최소 500m가 정한다
        assertIs<Verdict.Hold>(verdict(calm.copy(speedKph = 30.0, nextTurnMeters = 499)))
        assertIs<Verdict.Ask>(verdict(calm.copy(speedKph = 30.0, nextTurnMeters = 500)))
    }

    @Test
    fun `다음 회전까지 30초 안이면 보류하고 정확히 30초면 묻는다`() {
        // 시속 72km는 초속 20m라 30초는 600m
        assertIs<Verdict.Hold>(verdict(calm.copy(speedKph = 72.0, nextTurnMeters = 599)))
        assertIs<Verdict.Ask>(verdict(calm.copy(speedKph = 72.0, nextTurnMeters = 600)))
    }

    @Test
    fun `나들목 진출입은 1점5km 안이면 보류한다`() {
        assertIs<Verdict.Hold>(verdict(calm.copy(nextTurnMeters = 1499, nextTurnIsInterchange = true)))
        assertIs<Verdict.Ask>(verdict(calm.copy(nextTurnMeters = 1500, nextTurnIsInterchange = true)))
    }

    @Test
    fun `멈춰 있으면 회전이 100m 안일 때만 보류한다`() {
        val stopped = calm.copy(speedKph = 0.0)
        assertIs<Verdict.Hold>(verdict(stopped.copy(nextTurnMeters = 99)))
        assertIs<Verdict.Ask>(verdict(stopped.copy(nextTurnMeters = 100)))
    }

    @Test
    fun `찜까지 1분 주행 거리보다 가까우면 급하게 빠지지 않도록 보류한다`() {
        // 찜에서 서쪽으로 약 500m 떨어진 곳에서 동쪽(찜 쪽)으로 달린다
        val near = calm.copy(lat = bakery.lat, lon = bakery.lon - 0.0057)
        assertIs<Verdict.Hold>(verdict(near.copy(speedKph = 60.0))) // 1분이면 1km
        assertIs<Verdict.Ask>(verdict(near.copy(speedKph = 20.0))) // 1분이면 약 333m
        assertIs<Verdict.Hold>(verdict(near.copy(lon = bakery.lon - 0.0020, speedKph = 0.0))) // 약 180m는 멈춰 있어도 보류
    }

    @Test
    fun `이미 지나친 찜은 되돌아가야 하므로 묻지 않는다`() {
        // 찜에서 동쪽으로 약 1.5km 지나 동쪽으로 계속 달린다
        val passed = calm.copy(lat = bakery.lat, lon = bakery.lon + 0.0170)
        assertEquals(Verdict.Skip("이미 지나친 곳 (되돌아가야 함)"), verdict(passed))
        // 같은 자리라도 찜 쪽(서쪽)으로 달리고 있으면 앞쪽이다
        assertIs<Verdict.Ask>(verdict(passed.copy(headingDeg = 270.0, destination = Destination(bakery.lat, bakery.lon - 0.3))))
    }

    @Test
    fun `진행 방향을 모르면 목적지 쪽을 앞으로 본다`() {
        assertIs<Verdict.Ask>(verdict(calm.copy(headingDeg = null)))
        val passed = calm.copy(lat = bakery.lat, lon = bakery.lon + 0.0170, headingDeg = null)
        assertIs<Verdict.Skip>(verdict(passed))
    }

    @Test
    fun `진행 방향과 정확히 90도 벌어진 찜은 앞쪽이고 그보다 벌어지면 지나친 곳이다`() {
        // 찜은 정북(방위 0도)이다
        assertFalse(judge.isBehind(calm.copy(headingDeg = 90.0), north))
        assertFalse(judge.isBehind(calm.copy(headingDeg = 270.0), north))
        assertFalse(judge.isBehind(calm.copy(headingDeg = 89.9), north))
        assertTrue(judge.isBehind(calm.copy(headingDeg = 90.1), north))
        assertTrue(judge.isBehind(calm.copy(headingDeg = 269.9), north))
    }

    @Test
    fun `진행 방향이 0~360 밖이어도 같은 방향으로 보고 NaN이나 무한대는 모름으로 본다`() {
        // 찜은 정북이라 0도 쪽이면 앞쪽, 180도 쪽이면 뒤쪽이다
        for (h in listOf(0.0, 360.0, 720.0, -360.0, -720.0, 45.0 + 360.0, -45.0)) {
            assertFalse(judge.isBehind(calm.copy(headingDeg = h), north), "진행 방향 $h")
        }
        for (h in listOf(180.0, 540.0, -180.0, -540.0)) {
            assertTrue(judge.isBehind(calm.copy(headingDeg = h), north), "진행 방향 $h")
        }
        // 지나친 자리에서 NaN·무한대는 모름(null)처럼 목적지 쪽을 앞으로 보아 지나친 곳으로 본다
        val passed = calm.copy(lat = bakery.lat, lon = bakery.lon + 0.0170)
        assertTrue(judge.isBehind(passed.copy(headingDeg = null), bakery))
        for (h in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertTrue(judge.isBehind(passed.copy(headingDeg = h), bakery), "진행 방향 $h")
        }
    }

    @Test
    fun `시속 90km 이상 고속 주행 중에는 보류한다`() {
        assertEquals(Verdict.Hold("고속 주행 중 (시속 90km 이상)"), verdict(calm.copy(speedKph = 90.0)))
        assertIs<Verdict.Ask>(verdict(calm.copy(speedKph = 80.0)))
    }

    @Test
    fun `혼자 탄 이동의 절반 규칙은 설정에서 끌 수 있다`() {
        val solo = calm.copy(passengerPresent = false)
        val off = MomentJudge(Settings(halveForSolo = false))
        assertEquals(10.0, off.allowedDetourMin(solo))
        assertIs<Verdict.Ask>(off.judge(solo, bakery, AskHistory(), today).verdict)
    }

    @Test
    fun `1분이 안 걸리는 곳은 약 0분이라 하지 않고 분을 빼고 묻는다`() {
        assertEquals("찜해 둔 밀밭 베이커리가 가는 길에 있어요. 들를까요?", MomentJudge.question(bakery, 0.4))
        assertEquals(
            "찜해 둔 밀밭 베이커리가 가는 길 근처에 있어요. 약 6분 더 걸리는데 들를까요?",
            MomentJudge.question(bakery, 5.85),
        )
    }

    @Test
    fun `반올림해 0분이 되는 0점5분 미만만 분을 빼고 묻는다`() {
        assertEquals("찜해 둔 밀밭 베이커리가 가는 길에 있어요. 들를까요?", MomentJudge.question(bakery, 0.49))
        assertEquals(
            "찜해 둔 밀밭 베이커리가 가는 길 근처에 있어요. 약 1분 더 걸리는데 들를까요?",
            MomentJudge.question(bakery, 0.5),
        )
    }

    @Test
    fun `찜까지의 도로 시간이 없으면 보류하고, 그 뒤 도로 시간이 오면 묻는다`() {
        val noTrip = calm.copy(tripMinToPlace = emptyMap())
        val decision = judge.judge(noTrip, bakery, AskHistory(), today)
        assertEquals(Verdict.Hold("찜까지 도로 시간 없음 (받으면 다시 판단)"), decision.verdict)
        assertEquals(Outcome.HOLD, decision.checks.single { it.question == "얼마나 더 걸리나" }.outcome)
        // 남은 시간을 모를 때도 도로 시간으로 어림할 수 없으므로 보류한다
        assertEquals(Verdict.Hold("남은 시간 정보 없음 (받으면 다시 판단)"), verdict(calm.copy(remainingMin = null)))

        val withTrip = assertIs<Verdict.Ask>(verdict(noTrip.copy(tripMinToPlace = mapOf(bakery.id to 3.3))))
        assertEquals(6, withTrip.detourMin.roundToInt())
    }

    @Test
    fun `속도를 읽지 못하면 묻지 않고 보류한다`() {
        assertEquals(Verdict.Hold("속도 모름"), verdict(calm.copy(speedKph = null)))
    }

    @Test
    fun `공개 길찾기 경로 값을 넣은 경주 대릉원 장면에서는 묻고, 어림은 비교 스크립트와 같다`() {
        val frame = Scenarios.gyeongjuMeasured.single()
        assertTrue(judge.inTriggerRadius(frame.ctx, Scenarios.daereungwon))
        val ask = assertIs<Verdict.Ask>(judge.judge(frame.ctx, Scenarios.daereungwon, AskHistory(), today).verdict)
        assertEquals(2.886730676986467, ask.detourMin, 1e-9)
    }

    @Test
    fun `이름의 마지막 글자에 받침이 있으면 이, 없으면 가를 붙인다`() {
        assertEquals("장터 국밥이", MomentJudge.withSubjectParticle("장터 국밥"))
        assertEquals("은행나무길이", MomentJudge.withSubjectParticle("은행나무길"))
        assertEquals("밀밭 베이커리가", MomentJudge.withSubjectParticle("밀밭 베이커리"))
        assertEquals("카페 B가", MomentJudge.withSubjectParticle("카페 B"))
        assertEquals(
            "찜해 둔 장터 국밥이 가는 길 근처에 있어요. 약 3분 더 걸리는데 들를까요?",
            MomentJudge.question(bakery.copy(name = "장터 국밥"), 3.2),
        )
    }

    @Test
    fun `이름이 숫자나 영문으로 끝나면 읽는 소리에 맞춰 조사를 붙인다`() {
        // 숫자는 끝자리를 읽는 소리로: 1(일)·3(삼)·0(십·백·영)은 받침이 있고, 2(이)·4(사)·5(오)·9(구)는 없다
        assertEquals("본점 1이", MomentJudge.withSubjectParticle("본점 1"))
        assertEquals("카페 3이", MomentJudge.withSubjectParticle("카페 3"))
        assertEquals("주차장 10이", MomentJudge.withSubjectParticle("주차장 10"))
        assertEquals("카페 2가", MomentJudge.withSubjectParticle("카페 2"))
        assertEquals("카페 4가", MomentJudge.withSubjectParticle("카페 4"))
        // 영문 한 글자는 글자 이름으로: L(엘)·M(엠)·N(엔)·R(알)은 받침이 있다
        assertEquals("B동 L이", MomentJudge.withSubjectParticle("B동 L"))
        assertEquals("카페 B가", MomentJudge.withSubjectParticle("카페 B"))
        // 영문 낱말은 끝 글자로 어림한다: l·m·n·ng로 끝나면 받침이 있다고 본다
        assertEquals("Bread Garden이", MomentJudge.withSubjectParticle("Bread Garden"))
        assertEquals("Cafe가", MomentJudge.withSubjectParticle("Cafe"))
        // 끝의 괄호·문장부호는 건너뛰고 읽는 글자로 정한다
        assertEquals("밀밭 베이커리(본점)이", MomentJudge.withSubjectParticle("밀밭 베이커리(본점)"))
    }

    @Test
    fun `근거 문구의 소수점은 기기 언어와 상관없이 점으로 쓴다`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("여유 구간, 1.4km 앞 회전", row(calm, "지금 말 걸어도 되나").seen)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `설정값은 정한 범위를 벗어날 수 없다`() {
        // 이동당 묻는 횟수는 0~2번(기획서), 잔량 기준은 0~100%, 허용 시간과 쉬는 날수는 0 이상
        for (bad in listOf(3, -1)) assertFailsWith<IllegalArgumentException> { Settings(maxAsksPerTrip = bad) }
        for (bad in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { Settings(maxDetourMin = bad) }
        }
        for (bad in listOf(-1, 101)) assertFailsWith<IllegalArgumentException> { Settings(minEnergyPercent = bad) }
        assertFailsWith<IllegalArgumentException> { Settings(snoozeDays = -1) }
        // 범위의 끝 값은 받는다
        Settings(maxAsksPerTrip = 0)
        Settings(maxAsksPerTrip = 2)
        Settings(maxDetourMin = 0.0)
        Settings(minEnergyPercent = 0)
        Settings(minEnergyPercent = 100)
        Settings(snoozeDays = 0)
    }

    @Test
    fun `시나리오의 모든 장면은 판단을 시작하는 반경 2km 안이다`() {
        val frames = Scenarios.saturdayOuting + Scenarios.mondayCommute + Scenarios.noDestination
        assertTrue(frames.all { judge.inTriggerRadius(it.ctx, bakery) })
        assertFalse(judge.inTriggerRadius(calm.copy(lat = bakery.lat - 0.0200), bakery)) // 약 2.2km
        // 시나리오 D의 두 장면은 서로 다른 자리다(1분 동안 움직임)
        val (before, after) = Scenarios.saturdayOuting.map { it.ctx }
        assertTrue(DetourEstimator.distanceKm(before.lat, before.lon, after.lat, after.lon) > 0.3)
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
        // 시속 75km의 30초는 625m, 62km는 약 517m
        assertIs<Verdict.Hold>(verdict(calm.copy(speedKph = 75.0, nextTurnMeters = 600)))
        assertIs<Verdict.Ask>(verdict(calm.copy(speedKph = 62.0, nextTurnMeters = 600)))
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
    fun `통과한 곳이 여럿이면 덜 돌아가는 곳부터 묻는다`() {
        // 전망대: 차에서 동북동으로 약 1.5km(진행 방향에서 약 22도)
        val lookout = Place("lookout", "해오름 전망대", lat = 37.6050, lon = 127.2160)
        fun detourOf(ctx: DriveContext, place: Place) = assertIs<Verdict.Ask>(verdict(ctx, place = place)).detourMin
        // 전망대까지 도로로 8.5분이면 빵집이 덜 돌아가고, 5분이면 전망대가 덜 돌아간다
        val bakeryNearer = calm.copy(tripMinToPlace = mapOf(bakery.id to 3.3, lookout.id to 8.5))
        val lookoutNearer = calm.copy(tripMinToPlace = mapOf(bakery.id to 3.3, lookout.id to 5.0))
        assertTrue(detourOf(bakeryNearer, bakery) < detourOf(bakeryNearer, lookout))
        assertTrue(detourOf(lookoutNearer, lookout) < detourOf(lookoutNearer, bakery))
        // 후보를 넘기는 순서와도 상관없다
        for (places in listOf(listOf(bakery, lookout), listOf(lookout, bakery))) {
            assertEquals(bakery, judge.pickFirst(bakeryNearer, places, AskHistory(), today))
            assertEquals(lookout, judge.pickFirst(lookoutNearer, places, AskHistory(), today))
        }
    }

    @Test
    fun `먼저 물을 곳은 판단을 통과한 곳 가운데서만 고른다`() {
        // 서쪽으로 약 1km 지나친 곳은 덜 돌아가 보여도 고르지 않는다
        val passed = Place("passed", "지나친 곳", lat = calm.lat, lon = calm.lon - 0.0120)
        val ctx = calm.copy(tripMinToPlace = calm.tripMinToPlace + (passed.id to 0.5))
        assertEquals(Verdict.Skip("이미 지나친 곳 (되돌아가야 함)"), verdict(ctx, place = passed))
        assertEquals(bakery, judge.pickFirst(ctx, listOf(passed, bakery), AskHistory(), today))
        assertNull(judge.pickFirst(ctx, listOf(passed), AskHistory(), today))
        // 도로 시간이 없어 보류되는 곳도 고르지 않는다
        assertNull(judge.pickFirst(calm.copy(tripMinToPlace = emptyMap()), listOf(bakery), AskHistory(), today))
    }

    @Test
    fun `남은 시간이 남은 거리와 맞지 않으면 받지 못한 값으로 보고 보류한다`() {
        // calm은 남은 30km에 90분(평균 시속 20km). 초 단위 값이 분으로 들어온 것처럼 60배가 되면 시속 0.3km다
        assertIs<Verdict.Ask>(verdict(calm))
        val minutes = checkNotNull(calm.remainingMin)
        assertEquals(
            Verdict.Hold("남은 시간이 남은 거리와 맞지 않음 (받으면 다시 판단)"),
            verdict(calm.copy(remainingMin = minutes * 60)),
        )
        // 남은 거리를 모르거나 값이 이상하면 목적지까지 직선거리(약 26km)로 아래 끝을 본다
        assertIs<Verdict.Ask>(verdict(calm.copy(remainingKm = null)))
        for (km in listOf(null, Double.NaN, -1.0)) {
            assertEquals(
                Verdict.Hold("남은 시간이 남은 거리와 맞지 않음 (받으면 다시 판단)"),
                verdict(calm.copy(remainingKm = km, remainingMin = minutes * 60)),
                "남은 거리 $km",
            )
        }
        // 경계: 30km를 900분(시속 2km)·9분(시속 200km)은 받고, 그 밖은 받지 않는다
        assertEquals(900.0, DetourEstimator.remainingMinOf(calm.copy(remainingMin = 900.0)))
        assertNull(DetourEstimator.remainingMinOf(calm.copy(remainingMin = 900.5)))
        assertEquals(9.0, DetourEstimator.remainingMinOf(calm.copy(remainingMin = 9.0)))
        assertNull(DetourEstimator.remainingMinOf(calm.copy(remainingMin = 8.9)))
    }

    @Test
    fun `찜까지 도로 시간이 거리와 맞지 않으면 보류한다`() {
        // north는 약 1.6km. 0.1분(6초)이면 평균 시속 960km
        assertEquals(
            Verdict.Hold("찜까지 도로 시간이 거리와 맞지 않음 (받으면 다시 판단)"),
            verdict(towardNorth(tripMin = 0.1), place = north),
        )
        assertIs<Verdict.Ask>(verdict(towardNorth(tripMin = 3.0), place = north))
        // 경계: 직선거리로 본 시속 200km는 받고 넘으면 받지 않는다. 길게 잡히는 쪽(시속 2km 아래)은 거부하지 않는다
        val km = DetourEstimator.distanceKm(calm.lat, calm.lon, north.lat, north.lon)
        val at200 = km / 200.0 * 60.0
        assertTrue(DetourEstimator.tripMinTo(towardNorth(tripMin = at200 * 1.0001), north) != null)
        assertNull(DetourEstimator.tripMinTo(towardNorth(tripMin = at200 * 0.999), north))
        assertTrue(DetourEstimator.tripMinTo(towardNorth(tripMin = 60.0), north) != null)
        // 1km보다 가까운 찜도 0분이나 터무니없이 짧은 값은 받지 않는다(시속 20km라 앞질러 볼 거리 333m 밖의 600m)
        val near = Place("near600", "600m 찜", lat = calm.lat + 600.0 / 111_195.0, lon = calm.lon)
        for (tripMin in listOf(0.0, 0.01)) {
            val ctx = calm.copy(speedKph = 20.0, headingDeg = 0.0, tripMinToPlace = mapOf(near.id to tripMin))
            assertEquals(Verdict.Hold("찜까지 도로 시간이 거리와 맞지 않음 (받으면 다시 판단)"), verdict(ctx, place = near), "도로 시간 $tripMin")
        }
    }

    @Test
    fun `방향을 정할 수 없으면 지나친 곳으로 보고 묻지 않는다`() {
        val dest = checkNotNull(calm.destination)
        val unknownWay = calm.copy(headingDeg = null, destination = dest.copy(lat = Double.NaN))
        assertTrue(judge.isBehind(unknownWay, bakery))
        assertTrue(judge.directionUnknown(unknownWay, bakery))
        assertEquals(Verdict.Skip("진행 방향을 정할 수 없음"), verdict(unknownWay))
    }

    @Test
    fun `우회 시간이 같으면 가까운 곳부터 묻고 후보를 넘기는 순서와 상관없다`() {
        // 둘 다 차의 정북이라 목적지(동쪽) 쪽으로 나아가는 몫이 0이고, 도로 시간이 같아 우회 시간이 같다(6분)
        // 가까운 쪽 id를 사전 순으로 뒤에 둬, id 순으로 골라서는 통과하지 못하게 한다
        val nearer = Place("z-near", "가까운 찜", lat = calm.lat + 0.0108, lon = calm.lon)
        val ctx = calm.copy(headingDeg = 0.0, tripMinToPlace = mapOf(north.id to 3.0, nearer.id to 3.0))
        assertEquals(verdict(ctx, place = north), verdict(ctx, place = nearer))
        for (order in listOf(listOf(north, nearer), listOf(nearer, north))) {
            assertEquals(nearer, judge.pickFirst(ctx, order, AskHistory(), today))
        }
        // 자리도 같으면 id 순
        val a = Place("a", "같은 자리 가", lat = north.lat, lon = north.lon)
        val z = Place("z", "같은 자리 나", lat = north.lat, lon = north.lon)
        val twins = calm.copy(headingDeg = 0.0, tripMinToPlace = mapOf(a.id to 3.0, z.id to 3.0))
        for (order in listOf(listOf(a, z), listOf(z, a))) {
            assertEquals(a, judge.pickFirst(twins, order, AskHistory(), today))
        }
    }

    @Test
    fun `경유지 수를 받지 못했거나 음수로 오면 묻지 않고 기다린다`() {
        for (count in listOf(null, -1)) {
            val held = assertIs<Verdict.Hold>(verdict(calm.copy(waypointCount = count)))
            assertTrue(held.reason.endsWith("경유지 수 모름"), held.reason)
        }
    }

    @Test
    fun `찜 이름이 비어 있으면 찜해 둔 곳으로 묻는다`() {
        val unnamed = Place("unnamed", "", lat = 0.0, lon = 0.0)
        assertEquals("찜해 둔 곳이 가는 길 근처에 있어요. 약 6분 더 걸리는데 들를까요?", MomentJudge.question(unnamed, 6.0))
        assertEquals("찜해 둔 곳이 가는 길에 있어요. 들를까요?", MomentJudge.question(unnamed.copy(name = "  "), 0.2))
        // 이름 앞뒤의 빈칸은 조사 앞에 남기지 않는다
        assertEquals("찜해 둔 빵집이 가는 길 근처에 있어요. 약 6분 더 걸리는데 들를까요?", MomentJudge.question(unnamed.copy(name = " 빵집 "), 6.0))
    }

    @Test
    fun `완전히 멈춘 때 찜까지 300m보다 가까우면 급하게 빠지게 되므로 보류한다`() {
        // 멈춘 때 1분 주행 거리는 0이라 하한 300m가 기준이다. 위도 1도는 이 엔진의 지구 반지름으로 약 111,195m
        val stopped = calm.copy(speedKph = 0.0)
        fun placeAt(meters: Double) = Place("p$meters", "가까운 찜", lat = calm.lat + meters / 111_195.0, lon = calm.lon)
        val inside = placeAt(299.0)
        val outside = placeAt(301.0)
        assertTrue(DetourEstimator.distanceKm(calm.lat, calm.lon, inside.lat, inside.lon) * 1000 < 300.0)
        assertTrue(DetourEstimator.distanceKm(calm.lat, calm.lon, outside.lat, outside.lon) * 1000 > 300.0)
        val held = assertIs<Verdict.Hold>(verdict(stopped, place = inside))
        assertTrue(held.reason.startsWith("찜까지 299m (300m보다 가까우면"), held.reason)
        assertEquals(Outcome.PASS, row(stopped, "지금 말 걸어도 되나", place = outside).outcome)
    }
}
