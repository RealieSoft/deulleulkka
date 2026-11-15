package app.deulleulkka.core

import app.deulleulkka.engine.AskHistory
import app.deulleulkka.engine.Destination
import app.deulleulkka.engine.MomentJudge
import app.deulleulkka.engine.Place
import app.deulleulkka.engine.Verdict
import app.deulleulkka.engine.replay.Scenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SDK 값을 엔진 입력으로 바꾸는 규칙(단위, 모르는 값, 카메라 경보)과 후보·속도·다녀온 곳 규칙. */
class SignalsTest {
    private val full = Signals(
        lat = 37.6000, lon = 127.2000, headingDeg = 90.0, speedKph = 62.0,
        destination = Destination(37.6000, 127.5000),
        remainSeconds = 90 * 60, remainMeters = 30_000,
        nextTurn = Turn(1400.0, interchange = false), waypointCount = 0,
        tripSecondsTo = mapOf(Scenarios.bakery.id to 198), cameraChannelSeen = true,
        turnSignalOn = false, braking = false, parked = false, energyPercent = 71, passengerPresent = true, otherAudioPlaying = false,
    )

    @Test
    fun `SDK의 초와 m를 엔진의 분과 km로 바꾸고 시나리오 A와 같은 판단을 낸다`() {
        val ctx = assertTrueNotNull(ContextBuilder.build(full))
        assertEquals(90.0, ctx.remainingMin)
        assertEquals(30.0, ctx.remainingKm)
        assertEquals(3.3, ctx.tripMinToPlace.getValue(Scenarios.bakery.id), 1e-9)
        assertEquals(1400, ctx.nextTurnMeters)
        assertEquals(false, ctx.cautionAhead)
        val verdict = MomentJudge().judge(ctx, Scenarios.bakery, AskHistory(), 0L).verdict
        assertEquals(MomentJudge().judge(Scenarios.saturdayOuting.last().ctx, Scenarios.bakery, AskHistory(), 0L).verdict, verdict)
    }

    @Test
    fun `위치를 모르면 판단하지 않는다`() {
        assertNull(ContextBuilder.build(full.copy(lat = null)))
        assertNull(ContextBuilder.build(full.copy(lon = Double.NaN)))
    }

    @Test
    fun `경유지 수를 받지 못하면 엔진에 모름으로 넣어 묻지 않는다`() {
        val ctx = assertTrueNotNull(ContextBuilder.build(full.copy(waypointCount = null)))
        assertNull(ctx.waypointCount)
        assertIs<Verdict.Hold>(MomentJudge().judge(ctx, Scenarios.bakery, AskHistory(), 0L).verdict)
    }

    @Test
    fun `카메라 경보는 받은 적이 있어야 경보 없음을 카메라 없음으로 믿는다`() {
        assertNull(assertTrueNotNull(ContextBuilder.build(full.copy(cameraChannelSeen = false))).cautionAhead)
        assertEquals(false, assertTrueNotNull(ContextBuilder.build(full)).cautionAhead)
        assertEquals(true, assertTrueNotNull(ContextBuilder.build(full.copy(cameraMeters = 400))).cautionAhead)
        assertEquals(false, assertTrueNotNull(ContextBuilder.build(full.copy(cameraMeters = 900))).cautionAhead)
    }

    @Test
    fun `초 단위 남은 시간을 그대로 넣어도 엔진이 거리와 맞지 않는 값으로 보고 보류한다`() {
        // 앱이 단위를 빼먹은 경우(초를 분 자리에 둠)를 흉내: 5400초를 분으로 → 남은 30km에 5400분
        val wrong = assertTrueNotNull(ContextBuilder.build(full)).copy(remainingMin = 5400.0)
        assertIs<Verdict.Hold>(MomentJudge().judge(wrong, Scenarios.bakery, AskHistory(), 0L).verdict)
    }

    @Test
    fun `나들목 진출입 회전만 나들목으로 보고 모르는 종류는 모름으로 둔다`() {
        assertEquals(true, Turns.isInterchange("TURN_ENTER_IC_R"))
        assertEquals(true, Turns.isInterchange("TURN_EXIT_IC_L"))
        assertEquals(false, Turns.isInterchange("TURN_LEFT"))
        assertNull(Turns.isInterchange("TURN_UNKNOWN"))
    }

    @Test
    fun `가까운 찜부터 2점5km 안의 것만 판단하고 도로 시간은 판단 반경 안이면서 값이 없는 곳만 묻는다`() {
        val here = 37.6 to 127.2
        val p1 = Place("p1", "가까운 곳", 37.6090, 127.2) // 약 1.0km
        val p2 = Place("p2", "반경 밖 감시", 37.6200, 127.2) // 약 2.2km
        val p3 = Place("p3", "먼 곳", 37.6400, 127.2) // 약 4.4km
        assertEquals(listOf(p1, p2), Candidates.near(here.first, here.second, listOf(p3, p2, p1)))
        assertEquals(listOf(p1), Candidates.needsTrip(here.first, here.second, listOf(p1, p2), known = emptySet()))
        assertEquals(emptyList(), Candidates.needsTrip(here.first, here.second, listOf(p1, p2), known = setOf("p1")))
    }

    @Test
    fun `잇단 위치로 속도를 어림하고 간격이 맞지 않거나 위치가 튀면 버린다`() {
        val speed = SpeedEstimator()
        assertNull(speed.update(37.6, 127.2, 0L))
        // 1초에 약 16.7m 북쪽 → 약 시속 60km
        val kph = assertTrueNotNull(speed.update(37.6 + 16.67 / 111_195.0, 127.2, 1_000L))
        assertTrue(kph in 59.0..61.0, "$kph")
        assertNull(speed.update(37.6 + 33.0 / 111_195.0, 127.2, 1_100L)) // 0.1초 간격
        assertNull(speed.update(37.7, 127.2, 3_000L)) // 2초에 11km(위치가 튐)
    }

    @Test
    fun `달리는데 새 위치가 오래 오지 않으면 위치를 믿지 않는다`() {
        // 터널 등에서 위치가 멈춘 채 차가 달리면, 멈춘 위치로 찜까지 거리를 재지 않도록 모름으로 본다
        assertTrue(LocationGuard.usable(fixAgeMs = 3_000L, carSpeedKph = 60.0))
        assertTrue(!LocationGuard.usable(fixAgeMs = LocationGuard.STALE_WHILE_MOVING_MS + 1, carSpeedKph = 60.0))
        // 서 있거나 차 속도를 모르면 위치가 그대로인 것이 자연스러워 그대로 믿는다(신호 대기 중 묻기)
        assertTrue(LocationGuard.usable(fixAgeMs = 120_000L, carSpeedKph = 0.0))
        assertTrue(LocationGuard.usable(fixAgeMs = 120_000L, carSpeedKph = null))
        // 아직 한 번도 새 위치를 받지 못했으면(나이 모름) 달릴 때 믿지 않는다
        assertTrue(!LocationGuard.usable(fixAgeMs = null, carSpeedKph = 60.0))
        assertTrue(LocationGuard.usable(fixAgeMs = null, carSpeedKph = 0.0))
    }

    private fun <T : Any> assertTrueNotNull(value: T?): T {
        assertTrue(value != null)
        return value
    }
}
