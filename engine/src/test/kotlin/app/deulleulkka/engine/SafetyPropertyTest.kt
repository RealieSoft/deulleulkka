package app.deulleulkka.engine

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 판단 엔진의 안전 규칙을 무작위로 만든 상황 [RUNS]개씩에서 확인한다.
 * 실패하면 kotest가 가장 작은 반례와 시드를 알려 준다. 찾은 반례는 고정 단위 테스트로 옮겨 둔다.
 */
class SafetyPropertyTest {
    private val judge = MomentJudge()

    private fun lat() = Arb.double(37.40, 37.70, includeNonFiniteEdgeCases = false)
    private fun lon() = Arb.double(126.80, 127.40, includeNonFiniteEdgeCases = false)

    /** 수도권 안의 아무 순간. 차종이 지원하지 않는 신호(null)도 섞는다. */
    private val anyMoment: Arb<DriveContext> = arbitrary {
        DriveContext(
            minuteOfDay = Arb.int(0..1439).bind(),
            sunsetMinuteOfDay = Arb.int(17 * 60..20 * 60).bind(),
            lat = lat().bind(),
            lon = lon().bind(),
            speedKph = Arb.double(0.0, 130.0, includeNonFiniteEdgeCases = false).bind(),
            destination = arbitrary { Destination(lat().bind(), lon().bind(), Arb.boolean().bind()) }.orNull(0.1).bind(),
            remainingKm = Arb.double(0.0, 300.0, includeNonFiniteEdgeCases = false).orNull().bind(),
            remainingMin = Arb.double(0.0, 300.0, includeNonFiniteEdgeCases = false).orNull().bind(),
            nextTurnMeters = Arb.int(0..5000).orNull().bind(),
            nextTurnIsInterchange = Arb.boolean().bind(),
            turnSignalOn = Arb.boolean().orNull().bind(),
            braking = Arb.boolean().orNull().bind(),
            cautionAhead = Arb.boolean().bind(),
            otherAudioPlaying = Arb.boolean().orNull().bind(),
            passengerPresent = Arb.boolean().orNull().bind(),
            energyPercent = Arb.int(0..100).orNull().bind(),
            waypointCount = Arb.int(0..3).bind(),
        )
    }

    private val anyPlace: Arb<Place> = arbitrary {
        Place("p", "찜한 곳", Arb.enum<PlaceKind>().bind(), lat().bind(), lon().bind())
    }

    private val anyFactor = Arb.double(RoadFactorCalibrator.MIN_FACTOR, RoadFactorCalibrator.MAX_FACTOR, includeNonFiniteEdgeCases = false)

    private fun asks(ctx: DriveContext, place: Place, roadFactor: Double = DetourEstimator.ROAD_FACTOR) =
        judge.judge(ctx, place, AskHistory(), 0L, roadFactor).verdict is Verdict.Ask

    @Test
    fun `목적지가 회사면 다른 값이 무엇이든 묻지 않는다`() = runBlocking {
        checkAll(RUNS, anyMoment, anyPlace) { ctx, place ->
            val toOffice = ctx.copy(destination = Destination(ctx.lat + 0.05, ctx.lon + 0.05, isOffice = true))
            assertFalse(asks(toOffice, place))
        }
        Unit
    }

    @Test
    fun `통화 중이거나 안내 음성이 나오면 다른 값이 무엇이든 말을 걸지 않는다`() = runBlocking {
        checkAll(RUNS, anyMoment, anyPlace) { ctx, place ->
            val v = judge.judge(ctx.copy(otherAudioPlaying = true), place, AskHistory(), 0L).verdict
            assertIs<Verdict.Hold>(v)
        }
        Unit
    }

    @Test
    fun `확인할 수 없는 안전 신호가 하나라도 있으면 완전히 멈춘 때만 묻는다`() = runBlocking {
        checkAll(RUNS, anyMoment, anyPlace) { ctx, place ->
            val unknown = ctx.turnSignalOn == null || ctx.braking == null || ctx.otherAudioPlaying == null
            if (unknown && ctx.speedKph >= MomentJudge.STOPPED_KPH) assertFalse(asks(ctx, place))
        }
        Unit
    }

    @Test
    fun `이미 경유지가 있거나 잔량이 기준 아래면 묻지 않는다`() = runBlocking {
        checkAll(RUNS, anyMoment, anyPlace) { ctx, place ->
            val energy = ctx.energyPercent
            val low = energy != null && energy < Settings().minEnergyPercent
            if (ctx.waypointCount > 0 || low) assertFalse(asks(ctx, place))
        }
        Unit
    }

    @Test
    fun `처음 막힌 곳에서 멈추고 그 뒤 항목은 보지 않는다`() = runBlocking {
        checkAll(RUNS, anyMoment, anyPlace) { ctx, place ->
            val d = judge.judge(ctx, place, AskHistory(), 0L)
            val outcomes = d.checks.map { it.outcome }
            val stop = outcomes.indexOfFirst { it != Outcome.PASS }
            assertEquals(7, outcomes.size)
            if (stop == -1) {
                assertIs<Verdict.Ask>(d.verdict)
            } else {
                assertTrue(outcomes[stop] == Outcome.HOLD || outcomes[stop] == Outcome.SKIP)
                assertTrue(outcomes.drop(stop + 1).all { it == Outcome.NOT_CHECKED })
                assertFalse(d.verdict is Verdict.Ask)
            }
        }
        Unit
    }

    @Test
    fun `보정값이 커져도 묻지 않던 곳을 묻게 되지는 않는다`() = runBlocking {
        checkAll(RUNS, anyMoment, anyPlace, anyFactor, anyFactor) { ctx, place, a, b ->
            val (small, large) = if (a <= b) a to b else b to a
            if (asks(ctx, place, large)) assertTrue(asks(ctx, place, small))
        }
        Unit
    }

    @Test
    fun `보정값은 어떤 기록이 쌓여도 상한과 하한을 벗어나지 않는다`() = runBlocking {
        val anyRecord = arbitrary {
            Triple(
                Arb.double(0.0, 60.0, includeNonFiniteEdgeCases = false).bind(),
                Arb.double(-30.0, 180.0, includeNonFiniteEdgeCases = false).bind(),
                anyFactor.bind(),
            )
        }
        checkAll(RUNS, Arb.list(anyRecord, 0..30)) { records ->
            val c = records.fold(RoadFactorCalibrator()) { acc, (est, act, used) -> acc.record(est, act, used) }
            assertTrue(c.factor in RoadFactorCalibrator.MIN_FACTOR..RoadFactorCalibrator.MAX_FACTOR, "보정값 ${c.factor}")
            assertTrue(c.samples.size <= RoadFactorCalibrator.MAX_SAMPLES)
        }
        Unit
    }

    @Test
    fun `답 없이 닫힌 횟수는 어떤 순서로 답해도 두 번 이상 남지 않는다`() = runBlocking {
        val anyAnswer = arbitrary { Arb.element("a", "b", "c").bind() to Arb.enum<Answer>().bind() }
        checkAll(RUNS, Arb.list(anyAnswer, 0..30)) { answers ->
            val h = answers.fold(AskHistory()) { acc, (id, answer) -> acc.record(id, answer, today = 0L) }
            assertTrue(h.noAnswerCount.values.all { it in 1 until AskHistory.NO_ANSWERS_TREATED_AS_LATER })
            assertEquals(answers.size, h.askedThisTrip.size)
        }
        Unit
    }

    companion object {
        /** 성질 하나마다 만드는 무작위 상황의 수. */
        const val RUNS = 10_000
    }
}
