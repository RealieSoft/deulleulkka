package app.deulleulkka.engine

import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
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

    /**
     * 다른 조건은 모두 묻기 좋게 두고(신호 꺼짐, 동승자 있음, 잔량 충분, 경유지 없음), 찜은 2km 안 아무 방향에 둔 순간.
     * 다음 회전은 늘 읽은 값으로 둔다(길 안내 중에 모르면 확인할 수 없는 안전 신호라서).
     */
    private val calmMoment: Arb<Pair<DriveContext, Place>> = arbitrary {
        val lat = lat().bind()
        val lon = lon().bind()
        val (pLat, pLon) = offset(lat, lon, Arb.double(0.3, 1.95, includeNonFiniteEdgeCases = false).bind(), deg().bind())
        val destKm = Arb.double(5.0, 40.0, includeNonFiniteEdgeCases = false).bind()
        val (dLat, dLon) = offset(lat, lon, destKm, deg().bind())
        val roadKm = destKm * DetourEstimator.ROAD_FACTOR
        val averageKph = Arb.double(20.0, 80.0, includeNonFiniteEdgeCases = false).bind()
        // 찜까지의 도로 시간도 같은 평균 속도로 넣는다(없으면 묻지 않고 보류하므로)
        val toPlaceMin = DetourEstimator.distanceKm(lat, lon, pLat, pLon) * DetourEstimator.ROAD_FACTOR / averageKph * 60
        val ctx = DriveContext(
            lat = lat,
            lon = lon,
            speedKph = Arb.double(0.0, 130.0, includeNonFiniteEdgeCases = false).bind(),
            headingDeg = deg().orNull(0.2).bind(),
            destination = Destination(dLat, dLon),
            remainingKm = roadKm,
            remainingMin = roadKm / averageKph * 60,
            tripMinToPlace = mapOf("p" to toPlaceMin),
            nextTurnMeters = Arb.int(0..5000).bind(),
            nextTurnIsInterchange = false,
            turnSignalOn = false,
            braking = false,
            cautionAhead = false,
            otherAudioPlaying = false,
            passengerPresent = true,
            energyPercent = 60,
            waypointCount = 0,
        )
        ctx to Place("p", "찜한 곳", pLat, pLon)
    }

    /** 모든 값이 무작위인 순간. 차종이 지원하지 않는 신호(null)도 섞는다. 판단 구조처럼 값과 무관한 성질에 쓴다. */
    private val anyMoment: Arb<DriveContext> = arbitrary {
        DriveContext(
            lat = lat().bind(),
            lon = lon().bind(),
            speedKph = Arb.double(0.0, 130.0, includeNonFiniteEdgeCases = false).bind(),
            headingDeg = deg().orNull().bind(),
            destination = arbitrary { Destination(lat().bind(), lon().bind(), Arb.boolean().bind()) }.orNull(0.1).bind(),
            remainingKm = Arb.double(0.0, 300.0, includeNonFiniteEdgeCases = false).orNull().bind(),
            remainingMin = Arb.double(0.0, 300.0, includeNonFiniteEdgeCases = false).orNull().bind(),
            tripMinToPlace = Arb.double(0.0, 30.0, includeNonFiniteEdgeCases = false).orNull().bind()?.let { mapOf("p" to it) } ?: emptyMap(),
            nextTurnMeters = Arb.int(0..5000).orNull().bind(),
            nextTurnIsInterchange = Arb.boolean().orNull().bind(),
            turnSignalOn = Arb.boolean().orNull().bind(),
            braking = Arb.boolean().orNull().bind(),
            cautionAhead = Arb.boolean().orNull().bind(),
            otherAudioPlaying = Arb.boolean().orNull().bind(),
            passengerPresent = Arb.boolean().orNull().bind(),
            energyPercent = Arb.int(0..100).orNull().bind(),
            waypointCount = Arb.int(0..3).bind(),
        )
    }

    /** [anyMoment]에서 찜을 그 자리 3km 안 아무 방향에 둔 경우. 판단 반경 안팎이 섞인다. */
    private val anyMomentNearPlace: Arb<Triple<DriveContext, Place, AskHistory>> = arbitrary {
        val ctx = anyMoment.bind()
        val (pLat, pLon) = offset(ctx.lat, ctx.lon, Arb.double(0.1, 3.0, includeNonFiniteEdgeCases = false).bind(), deg().bind())
        Triple(ctx, Place("p", "찜한 곳", pLat, pLon), AskHistory())
    }

    /**
     * 안전 단계는 대개 통과하는 [calmMoment]에서 뒤 단계의 값(목적지, 잔량, 경유지, 동승자, 도로 시간, 이번 이동 기록)을
     * 무작위로 둔 경우. 모든 값이 무작위면 거의 다 안전 단계에서 멈춰, 뒤 단계의 구조가 검사되지 않기 때문이다.
     */
    private val laterStepMoment: Arb<Triple<DriveContext, Place, AskHistory>> = arbitrary {
        val (ctx, place) = calmMoment.bind()
        val dest = checkNotNull(ctx.destination)
        val varied = ctx.copy(
            destination = Arb.element(dest, dest.copy(isOffice = true), null).bind(),
            energyPercent = Arb.int(0..100).orNull().bind(),
            waypointCount = Arb.int(0..2).bind(),
            passengerPresent = Arb.boolean().orNull().bind(),
            tripMinToPlace = if (Arb.boolean().bind()) ctx.tripMinToPlace else emptyMap(),
        )
        Triple(varied, place, AskHistory(askedThisTrip = Arb.list(Arb.element("a", "b", "p"), 0..2).bind()))
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
        checkAll(RUNS, calmMoment, Arb.int(0..5)) { (ctx, place), which ->
            val unknown = when (which) {
                0 -> ctx.copy(turnSignalOn = null)
                1 -> ctx.copy(braking = null)
                2 -> ctx.copy(otherAudioPlaying = null)
                3 -> ctx.copy(cautionAhead = null)
                // 다음 회전까지 거리는 알지만 그 종류(나들목인지)를 모름
                4 -> ctx.copy(nextTurnIsInterchange = null)
                // 길 안내 중(목적지 있음)에 다음 회전을 모름
                else -> ctx.copy(nextTurnMeters = null)
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
        var beyondSafety = 0
        val stoppedAt = mutableSetOf<Int>()
        checkAll(RUNS, Arb.choice(anyMomentNearPlace, laterStepMoment)) { (ctx, place, history) ->
            val d = judge.judge(ctx, place, history, 0L)
            val outcomes = d.checks.map { it.outcome }
            val stop = outcomes.indexOfFirst { it != Outcome.PASS }
            stoppedAt += stop
            if (stop != 0) beyondSafety++
            assertEquals(6, outcomes.size)
            if (stop == -1) {
                assertIs<Verdict.Ask>(d.verdict)
            } else {
                assertTrue(outcomes[stop] == Outcome.HOLD || outcomes[stop] == Outcome.SKIP)
                assertTrue(outcomes.drop(stop + 1).all { it == Outcome.NOT_CHECKED })
                assertFalse(d.verdict is Verdict.Ask)
            }
        }
        assertEnough(beyondSafety, "안전 단계를 지나 뒤 단계까지 본")
        // 막힐 수 있는 줄(안전, 어떤 이동, 갈 여력, 얼마나 더, 너무 자주)과 끝까지 통과한 경우(-1)가 모두 나왔다
        assertEquals(setOf(-1, 0, 1, 2, 3, 5), stoppedAt)
    }

    @Test
    fun `읽은 값이 NaN·무한대·음수면 묻지 않고, 물을 때의 우회 시간으로는 늘 묻는 말을 만들 수 있다`() = runBlocking {
        val odd = Arb.element(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, -8.0)
        var asked = 0
        checkAll(RUNS, calmMoment, Arb.int(0..2), odd) { (ctx, place), which, value ->
            // 속도, 찜까지 도로 시간, 남은 시간 가운데 하나가 이상하면 읽지 못한 것과 같아 묻지 않는다
            val broken = when (which) {
                0 -> ctx.copy(speedKph = value)
                1 -> ctx.copy(tripMinToPlace = mapOf(place.id to value))
                else -> ctx.copy(remainingMin = value)
            }
            assertFalse(asks(broken, place))
            // 이상한 값이 없는 같은 순간에서 묻는다면 우회 시간은 0 이상의 유한한 값이고, 묻는 말을 만들다 멈추지 않는다
            val verdict = judge.judge(ctx, place, AskHistory(), 0L).verdict
            if (verdict is Verdict.Ask) {
                asked++
                assertTrue(verdict.detourMin.isFinite() && verdict.detourMin >= 0.0, "우회 시간 ${verdict.detourMin}")
                MomentJudge.question(place, verdict.detourMin, judge.allowedDetourMin(ctx))
            }
        }
        assertEnough(asked, "이상한 값이 없었다면 물었을")
    }

    @Test
    fun `찜이 판단 반경 2km 밖이면 다른 값이 무엇이든 묻지 않는다`() = runBlocking {
        var wouldAsk = 0
        checkAll(RUNS, calmMoment, Arb.double(2.05, 10.0, includeNonFiniteEdgeCases = false)) { (ctx, place), km ->
            if (asks(ctx, place)) wouldAsk++
            // 같은 방향으로 반경 밖까지 옮긴다(찜까지 도로 시간 등 다른 값은 그대로)
            val (fLat, fLon) = offset(ctx.lat, ctx.lon, km, DetourEstimator.bearingDeg(ctx.lat, ctx.lon, place.lat, place.lon))
            assertFalse(asks(ctx, place.copy(lat = fLat, lon = fLon)))
        }
        assertEnough(wouldAsk, "반경 안이었다면 물었을")
    }

    @Test
    fun `보류한 사이 반경을 벗어난 찜은 그 이동에서는 다른 값이 무엇이든 묻지 않는다`() = runBlocking {
        var wouldAsk = 0
        val left = AskHistory().recordVerdict("p", Verdict.Hold("방향지시등 켜짐")).recordRadiusExit("p")
        checkAll(RUNS, calmMoment) { (ctx, place) ->
            val fresh = asks(ctx, place)
            if (fresh) wouldAsk++
            assertFalse(judge.judge(ctx, place, left, 0L).verdict is Verdict.Ask)
            // 다음 이동에서는 처음과 같이 판단한다
            assertEquals(fresh, judge.judge(ctx, place, left.startNewTrip(), 0L).verdict is Verdict.Ask)
        }
        assertEnough(wouldAsk, "반경을 벗어나지 않았다면 물었을")
    }

    /**
     * 남겨 둔 처음 방식(직선거리 어림)의 성질이다. 지금은 찜까지 도로 시간이 없으면 묻지 않고 보류하므로
     * 이 어림과 보정값은 묻는 판단에 쓰이지 않는다(README). 보정값을 키우면 어림이 줄어드는 잘못(예: 곱하기 대신 나누기)을 잡는다.
     */
    @Test
    fun `보정값이 커지면 직선거리 어림도 커지거나 같다`() = runBlocking {
        var grew = 0
        checkAll(RUNS, calmMoment, anyFactor, anyFactor) { (ctx, place), a, b ->
            val (small, large) = if (a <= b) a to b else b to a
            val straight = ctx.copy(tripMinToPlace = emptyMap())
            val atSmall = checkNotNull(DetourEstimator.estimateMinutes(straight, place, small))
            val atLarge = checkNotNull(DetourEstimator.estimateMinutes(straight, place, large))
            assertTrue(atLarge >= atSmall, "보정값 $small → $large 에서 어림 $atSmall → $atLarge")
            if (atLarge > atSmall) grew++
        }
        assertEnough(grew, "보정값을 키워 어림이 커진")
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
            val h = answers.fold(AskHistory()) { acc, (id, answer) -> acc.record(id, answer, today = 0L, settings = Settings()) }
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
