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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 판단 엔진의 안전 규칙을 무작위로 만든 상황 [RUNS]개씩에서 확인한다.
 *
 * 규칙 검사는 "다른 조건은 모두 묻기 좋고, 찜은 2km 안 아무 방향"인 순간에서 규칙 하나만 바꿔 본다.
 * 모든 값을 무작위로 두면 묻는 경우가 거의 나오지 않아, 규칙이 빠져도 검사가 통과하기 때문이다.
 * 그래서 검사마다 그 규칙이 막아야 할 경우가 충분히 나왔는지도 센다.
 * 실패하면 kotest가 가장 작은 반례와 시드를 알려 준다. 찾은 반례는 고정 단위 테스트로 옮겨 둔다.
 */
class SafetyPropertyTest {
    private val judge = MomentJudge()

    private fun lat() = Arb.double(37.40, 37.70, includeNonFiniteEdgeCases = false)
    private fun lon() = Arb.double(126.80, 127.40, includeNonFiniteEdgeCases = false)
    private fun deg() = Arb.double(0.0, 359.9, includeNonFiniteEdgeCases = false)

    /** (lat, lon)에서 [bearing] 방향으로 [km] 간 좌표(좁은 범위라 평면으로 어림). */
    private fun offset(lat: Double, lon: Double, km: Double, bearing: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearing)
        return (lat + km * cos(b) / KM_PER_DEG_LAT) to (lon + km * sin(b) / (KM_PER_DEG_LAT * cos(Math.toRadians(lat))))
    }

    /** 다른 조건은 모두 묻기 좋게 두고(신호 꺼짐, 동승자 있음, 잔량 충분, 경유지 없음), 찜은 2km 안 아무 방향에 둔 순간. */
    private val calmMoment: Arb<Pair<DriveContext, Place>> = arbitrary {
        val lat = lat().bind()
        val lon = lon().bind()
        val (pLat, pLon) = offset(lat, lon, Arb.double(0.3, 2.0, includeNonFiniteEdgeCases = false).bind(), deg().bind())
        val destKm = Arb.double(5.0, 40.0, includeNonFiniteEdgeCases = false).bind()
        val (dLat, dLon) = offset(lat, lon, destKm, deg().bind())
        val roadKm = destKm * DetourEstimator.ROAD_FACTOR
        val averageKph = Arb.double(20.0, 80.0, includeNonFiniteEdgeCases = false).bind()
        // 찜까지의 도로 시간도 같은 평균 속도로 넣는다(없으면 묻지 않고 보류하므로)
        val toPlaceMin = DetourEstimator.distanceKm(lat, lon, pLat, pLon) * DetourEstimator.ROAD_FACTOR / averageKph * 60
        val ctx = DriveContext(
            minuteOfDay = Arb.int(0..1439).bind(),
            sunsetMinuteOfDay = 17 * 60 + 20,
            lat = lat,
            lon = lon,
            speedKph = Arb.double(0.0, 130.0, includeNonFiniteEdgeCases = false).bind(),
            headingDeg = deg().orNull(0.2).bind(),
            destination = Destination(dLat, dLon),
            remainingKm = roadKm,
            remainingMin = roadKm / averageKph * 60,
            tripMinToPlace = mapOf("p" to toPlaceMin),
            nextTurnMeters = Arb.int(0..5000).orNull().bind(),
            passengerPresent = true,
            energyPercent = 60,
        )
        ctx to Place("p", "찜한 곳", Arb.enum<PlaceKind>().bind(), pLat, pLon)
    }

    /** 모든 값이 무작위인 순간. 차종이 지원하지 않는 신호(null)도 섞는다. 판단 구조처럼 값과 무관한 성질에 쓴다. */
    private val anyMoment: Arb<DriveContext> = arbitrary {
        DriveContext(
            minuteOfDay = Arb.int(0..1439).bind(),
            sunsetMinuteOfDay = Arb.int(17 * 60..20 * 60).bind(),
            lat = lat().bind(),
            lon = lon().bind(),
            speedKph = Arb.double(0.0, 130.0, includeNonFiniteEdgeCases = false).bind(),
            headingDeg = deg().orNull().bind(),
            destination = arbitrary { Destination(lat().bind(), lon().bind(), Arb.boolean().bind()) }.orNull(0.1).bind(),
            remainingKm = Arb.double(0.0, 300.0, includeNonFiniteEdgeCases = false).orNull().bind(),
            remainingMin = Arb.double(0.0, 300.0, includeNonFiniteEdgeCases = false).orNull().bind(),
            tripMinToPlace = Arb.double(0.0, 30.0, includeNonFiniteEdgeCases = false).orNull().bind()?.let { mapOf("p" to it) } ?: emptyMap(),
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

    /** 검사가 비어 있지 않은지: 규칙이 막아야 할 경우가 [RUNS]의 [MIN_SHARE] 이상 나왔는지. */
    private fun assertEnough(count: Int, what: String) =
        assertTrue(count >= RUNS * MIN_SHARE, "$what 경우가 ${count}번뿐이라 검사가 약함")

    @Test
    fun `묻기 좋은 순간을 만드는 생성기에서는 묻는 경우가 충분히 나온다`() = runBlocking {
        var asked = 0
        checkAll(RUNS, calmMoment) { (ctx, place) -> if (asks(ctx, place)) asked++ }
        assertEnough(asked, "묻는")
    }

    @Test
    fun `목적지가 회사면 다른 값이 무엇이든 묻지 않는다`() = runBlocking {
        var wouldAsk = 0
        checkAll(RUNS, calmMoment) { (ctx, place) ->
            if (asks(ctx, place)) wouldAsk++
            val dest = checkNotNull(ctx.destination)
            assertFalse(asks(ctx.copy(destination = dest.copy(isOffice = true)), place))
        }
        assertEnough(wouldAsk, "회사가 아니었다면 물었을")
    }

    @Test
    fun `통화 중이거나 안내 음성이 나오면 다른 값이 무엇이든 말을 걸지 않는다`() = runBlocking {
        var wouldAsk = 0
        checkAll(RUNS, calmMoment) { (ctx, place) ->
            if (asks(ctx, place)) wouldAsk++
            assertIs<Verdict.Hold>(judge.judge(ctx.copy(otherAudioPlaying = true), place, AskHistory(), 0L).verdict)
        }
        assertEnough(wouldAsk, "음성이 없었다면 물었을")
    }

    @Test
    fun `확인할 수 없는 안전 신호가 하나라도 있으면 완전히 멈춘 때만 묻는다`() = runBlocking {
        var wouldAsk = 0
        checkAll(RUNS, calmMoment, Arb.int(0..2)) { (ctx, place), which ->
            val unknown = when (which) {
                0 -> ctx.copy(turnSignalOn = null)
                1 -> ctx.copy(braking = null)
                else -> ctx.copy(otherAudioPlaying = null)
            }
            if (checkNotNull(ctx.speedKph) >= MomentJudge.STOPPED_KPH) {
                if (asks(ctx, place)) wouldAsk++
                assertFalse(asks(unknown, place))
            }
        }
        assertEnough(wouldAsk, "신호를 알았다면 물었을")
    }

    @Test
    fun `이미 경유지가 있거나 잔량이 기준 아래면 묻지 않는다`() = runBlocking {
        var wouldAsk = 0
        checkAll(RUNS, calmMoment, Arb.int(1..3), Arb.int(0 until Settings().minEnergyPercent)) { (ctx, place), waypoints, energy ->
            if (asks(ctx, place)) wouldAsk++
            assertFalse(asks(ctx.copy(waypointCount = waypoints), place))
            assertFalse(asks(ctx.copy(energyPercent = energy), place))
        }
        assertEnough(wouldAsk, "경유지·잔량이 괜찮았다면 물었을")
    }

    @Test
    fun `진행 방향 뒤쪽의 찜은 다른 값이 무엇이든 묻지 않는다`() = runBlocking {
        var wouldAsk = 0
        checkAll(RUNS, calmMoment, deg()) { (ctx, place), heading ->
            val toPlace = DetourEstimator.bearingDeg(ctx.lat, ctx.lon, place.lat, place.lon)
            // 같은 순간에 찜을 앞쪽에 두는 진행 방향과 뒤쪽에 두는 진행 방향을 견준다
            val ahead = ctx.copy(headingDeg = toPlace)
            val behind = ctx.copy(headingDeg = (toPlace + 90.5 + heading % 179) % 360)
            if (asks(ahead, place)) wouldAsk++
            assertTrue(angleBetween(toPlace, checkNotNull(behind.headingDeg)) > MomentJudge.AHEAD_MAX_DEG)
            assertFalse(asks(behind, place))
        }
        assertEnough(wouldAsk, "앞쪽이었다면 물었을")
    }

    @Test
    fun `시속 90km 이상이면 다른 값이 무엇이든 묻지 않는다`() = runBlocking {
        var wouldAsk = 0
        checkAll(RUNS, calmMoment, Arb.double(MomentJudge.HIGH_SPEED_KPH, 130.0, includeNonFiniteEdgeCases = false)) { (ctx, place), fast ->
            // 같은 순간을 시속 60km로 달렸다면 물었을 경우를 센다
            if (asks(ctx.copy(speedKph = 60.0), place)) wouldAsk++
            assertFalse(asks(ctx.copy(speedKph = fast), place))
        }
        assertEnough(wouldAsk, "느렸다면 물었을")
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
        var askedAtLarge = 0
        checkAll(RUNS, calmMoment, anyFactor, anyFactor) { (ctx, place), a, b ->
            val (small, large) = if (a <= b) a to b else b to a
            if (asks(ctx, place, large)) {
                askedAtLarge++
                assertTrue(asks(ctx, place, small))
            }
        }
        assertEnough(askedAtLarge, "큰 보정값에서도 물은")
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

    private fun angleBetween(a: Double, b: Double): Double = abs(((a - b) % 360 + 540) % 360 - 180)

    companion object {
        /** 성질 하나마다 만드는 무작위 상황의 수. */
        const val RUNS = 10_000

        /** 규칙이 막아야 할 경우가 적어도 이 비율은 나와야 검사가 비어 있지 않다고 본다. */
        const val MIN_SHARE = 0.05

        private const val KM_PER_DEG_LAT = 111.195
    }
}
