package app.deulleulkka.engine

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 찜한 곳 근처에 들어왔을 때 "지금 물어도 되는가"를 정한다.
 *
 * 안전 → 자격 → 가치 → 빈도 순서로 보고, 처음 막힌 곳에서 멈춘다.
 * 본 것과 결과를 줄마다 남겨, 주차 중 판단 근거 화면이 그대로 보여 줄 수 있게 한다.
 */
class MomentJudge(private val settings: Settings = Settings()) {

    /** [roadFactor]는 우회 어림에 쓸 도로 보정값이다. 쓰면서 [RoadFactorCalibrator.factor]로 바꿔 넣는다. */
    fun judge(
        ctx: DriveContext,
        place: Place,
        history: AskHistory,
        today: Long,
        roadFactor: Double = DetourEstimator.ROAD_FACTOR,
    ): Decision {
        val detourMin = DetourEstimator.estimateMinutes(ctx, place, roadFactor)
        val rows: List<Triple<Step, String, () -> Pair<String, Outcome>>> = listOf(
            Triple(Step.SAFETY, "지금 말 걸어도 되나") { safety(ctx, place) },
            Triple(Step.ELIGIBILITY, "어떤 이동인가") { tripKind(ctx) },
            Triple(Step.ELIGIBILITY, "갈 여력이 있나") { capacity(ctx) },
            Triple(Step.VALUE, "얼마나 더 걸리나") { detour(ctx, detourMin) },
            Triple(Step.VALUE, "지금 가기 좋은 곳인가") { goodTime(ctx, place) },
            Triple(Step.VALUE, "누가 타고 있나") { company(ctx) },
            Triple(Step.FREQUENCY, "너무 자주 묻지 않나") { frequency(place, history, today) },
        )

        var stoppedBy: Check? = null
        val checks = rows.map { (step, question, look) ->
            if (stoppedBy != null) {
                Check(step, question, Outcome.NOT_CHECKED.label, Outcome.NOT_CHECKED)
            } else {
                val (seen, outcome) = look()
                Check(step, question, seen, outcome).also { if (outcome != Outcome.PASS) stoppedBy = it }
            }
        }

        val stop = stoppedBy
        val verdict = when {
            stop == null -> Verdict.Ask(checkNotNull(detourMin))
            stop.outcome == Outcome.HOLD -> Verdict.Hold(stop.seen)
            else -> Verdict.Skip(stop.seen)
        }
        return Decision(verdict, checks)
    }

    /**
     * 이 이동에서 허용하는 우회 시간(분).
     * 혼자 탄 이동이면 절반으로 줄인다. 착좌 정보가 없는 차량에서는 줄이지 않는다.
     */
    fun allowedDetourMin(ctx: DriveContext): Double =
        if (ctx.passengerPresent == false) settings.maxDetourMin / SOLO_DIVISOR else settings.maxDetourMin

    /** 경유지를 넣은 뒤 내비게이션이 다시 계산한 값이 허용 범위를 넘는지. 넘으면 한 번 더 묻는다. */
    fun exceedsAllowance(actualDetourMin: Double, ctx: DriveContext): Boolean =
        actualDetourMin > allowedDetourMin(ctx)

    /** 통과한 곳이 여럿일 때 먼저 물을 곳. 지금 가기 좋은 곳이 먼저, 그다음은 덜 돌아가는 곳. */
    fun pickFirst(ctx: DriveContext, candidates: List<Place>, roadFactor: Double = DetourEstimator.ROAD_FACTOR): Place? =
        candidates.sortedWith(
            compareByDescending<Place> { isGoodTimeFor(ctx, it) }
                .thenBy { DetourEstimator.estimateMinutes(ctx, it, roadFactor) ?: Double.MAX_VALUE },
        ).firstOrNull()

    /** 먹을 곳은 식사 시간대에, 볼 곳은 해가 지기 전에. */
    fun isGoodTimeFor(ctx: DriveContext, place: Place): Boolean = when (place.kind) {
        PlaceKind.EAT -> MEAL_HOURS.any { ctx.minuteOfDay in it }
        PlaceKind.SEE -> ctx.minuteOfDay < ctx.sunsetMinuteOfDay
    }

    /**
     * 안전 단계. 확인할 수 없는 안전 신호가 있으면 덜 조심하는 쪽이 아니라 더 조심하는 쪽으로 간다:
     * 그때는 완전히 멈춘 때만 묻는다. 멈춘 때는 브레이크를 보지 않는다(신호 대기 중에는 늘 밟고 있으므로).
     */
    private fun safety(ctx: DriveContext, place: Place): Pair<String, Outcome> {
        val turn = ctx.nextTurnMeters
        val stopped = ctx.speedKph < STOPPED_KPH
        val calmMeters =
            if (ctx.nextTurnIsInterchange) INTERCHANGE_CALM_METERS
            else max(MIN_CALM_METERS, ctx.speedKph / KPH_PER_MPS * CALM_SECONDS)
        // 수락하자마자 급하게 빠지지 않도록, 찜까지 이 거리보다 가까우면 묻지 않고 보류한다
        val leadMeters = max(MIN_LEAD_METERS, ctx.speedKph / KPH_PER_MPS * LEAD_SECONDS)
        val toPlaceMeters = DetourEstimator.distanceKm(ctx.lat, ctx.lon, place.lat, place.lon) * METERS_PER_KM
        val unknown = listOfNotNull(
            "방향지시등".takeIf { ctx.turnSignalOn == null },
            "브레이크".takeIf { ctx.braking == null },
            "통화·안내 음성".takeIf { ctx.otherAudioPlaying == null },
        )
        return when {
            ctx.otherAudioPlaying == true -> "통화 중이거나 안내 음성이 나오는 중" to Outcome.HOLD
            toPlaceMeters < leadMeters ->
                "찜까지 ${toPlaceMeters.roundToInt()}m (${leadMeters.roundToInt()}m보다 가까우면 급하게 빠지게 됨)" to Outcome.HOLD
            stopped && ctx.turnSignalOn == true -> "멈춰 있지만 방향지시등 켜짐" + turnText(turn, prefix = ", ") to Outcome.HOLD
            stopped && turn != null && turn < STOPPED_TURN_METERS -> "멈춰 있지만 " + turnText(turn) to Outcome.HOLD
            stopped -> "완전히 멈춤" to Outcome.PASS
            unknown.isNotEmpty() -> "${unknown.joinToString()} 확인 불가 (완전히 멈춘 때만 물음)" to Outcome.HOLD
            ctx.turnSignalOn == true -> "방향지시등 켜짐" + turnText(turn, prefix = ", ") to Outcome.HOLD
            ctx.braking == true -> "브레이크 밟는 중" to Outcome.HOLD
            ctx.cautionAhead -> "단속 카메라·주의 지점 안내 구간" to Outcome.HOLD
            turn != null && turn < calmMeters ->
                (if (ctx.nextTurnIsInterchange) "나들목 " else "") + turnText(turn) to Outcome.HOLD
            else -> "여유 구간" + turnText(turn, prefix = ", ") to Outcome.PASS
        }
    }

    private fun turnText(meters: Int?, prefix: String = ""): String = when {
        meters == null -> ""
        meters >= METERS_PER_KM -> "$prefix${"%.1f".format(meters / METERS_PER_KM.toDouble())}km 앞 회전"
        else -> "$prefix${meters}m 앞 회전"
    }

    private fun tripKind(ctx: DriveContext): Pair<String, Outcome> {
        val dest = ctx.destination
        return when {
            dest == null -> "목적지 없음" to Outcome.SKIP
            dest.isOffice -> "목적지가 회사" to Outcome.SKIP
            else -> "목적지가 회사가 아님" to Outcome.PASS
        }
    }

    private fun capacity(ctx: DriveContext): Pair<String, Outcome> {
        val energy = ctx.energyPercent
        val energyText = if (energy == null) "잔량 정보 없음" else "잔량 $energy%"
        return when {
            energy != null && energy < settings.minEnergyPercent -> "$energyText (${settings.minEnergyPercent}% 아래)" to Outcome.SKIP
            // 운전자가 넣어 둔 경유지의 순서를 바꾸거나 덮어쓰지 않도록, 경유지가 없을 때만 묻는다
            ctx.waypointCount > 0 -> "$energyText, 이미 경유지 ${ctx.waypointCount}곳 있음" to Outcome.SKIP
            else -> "$energyText, 경유지 없음" to Outcome.PASS
        }
    }

    private fun detour(ctx: DriveContext, detourMin: Double?): Pair<String, Outcome> {
        // 목적지가 없으면 앞 단계(어떤 이동인가)에서 이미 멈춘다.
        val minutes = checkNotNull(detourMin)
        val allowed = allowedDetourMin(ctx)
        val seen = "약 ${minutes.roundToInt()}분 더 걸림 (${allowed.roundToInt()}분까지)"
        return seen to if (minutes <= allowed) Outcome.PASS else Outcome.SKIP
    }

    private fun goodTime(ctx: DriveContext, place: Place): Pair<String, Outcome> {
        val kind = if (place.kind == PlaceKind.EAT) "먹을 곳" else "볼 곳"
        val fit = if (isGoodTimeFor(ctx, place)) "지금 가기 좋은 때 (먼저 물음)" else "때와 무관"
        return "$kind, $fit" to Outcome.PASS
    }

    private fun company(ctx: DriveContext): Pair<String, Outcome> {
        val seen = when (ctx.passengerPresent) {
            true -> "동승자 있음"
            false -> "혼자 탐 (허용 시간 절반)"
            null -> "착좌 정보 없음"
        }
        return seen to Outcome.PASS
    }

    private fun frequency(place: Place, history: AskHistory, today: Long): Pair<String, Outcome> {
        val asked = history.askedThisTrip.size
        val countText = "이번 이동에서 ${asked}번 (${settings.maxAsksPerTrip}번까지)"
        return when {
            history.isSnoozed(place.id, today) -> "\"다음에\"라고 답해 쉬는 중" to Outcome.SKIP
            place.id in history.askedThisTrip -> "이번 이동에서 이미 물은 곳" to Outcome.SKIP
            asked >= settings.maxAsksPerTrip -> countText to Outcome.SKIP
            else -> countText to Outcome.PASS
        }
    }

    companion object {
        /** 이 속도 아래면 완전히 멈춘 것으로 본다(km/h). 신호 대기는 여유 구간이다. */
        const val STOPPED_KPH = 1.0

        /** 다음 회전까지 이 시간(초)보다 덜 남았으면 조작 구간으로 본다. */
        const val CALM_SECONDS = 30.0

        /** 속도가 낮아도 다음 회전까지 이 거리(m)보다 가까우면 조작 구간으로 본다. */
        const val MIN_CALM_METERS = 500.0

        /** 나들목 진입·진출은 차선 변경이 길어 더 멀리서부터 조작 구간으로 본다(m). */
        const val INTERCHANGE_CALM_METERS = 1500.0

        /** 멈춰 있어도 다음 회전이 이 거리(m) 안이면 출발하자마자 회전하므로 보류한다. */
        const val STOPPED_TURN_METERS = 100

        /** 찜까지 이 시간(초) 안에 닿을 거리면 수락 직후 급하게 빠지게 되므로 묻지 않는다. */
        const val LEAD_SECONDS = 60.0

        /** 위 거리의 하한(m). 멈춰 있을 때도 이보다 가까우면 묻지 않는다. */
        const val MIN_LEAD_METERS = 300.0

        /** 혼자 탄 이동에서 허용 우회 시간을 나누는 값. */
        const val SOLO_DIVISOR = 2.0

        private const val KPH_PER_MPS = 3.6
        private const val METERS_PER_KM = 1000
        private const val MINUTES_PER_HOUR = 60

        /** 식사 시간대(자정부터 센 분): 11~14시, 17~20시. */
        val MEAL_HOURS: List<IntRange> = listOf(
            11 * MINUTES_PER_HOUR until 14 * MINUTES_PER_HOUR,
            17 * MINUTES_PER_HOUR until 20 * MINUTES_PER_HOUR,
        )
    }
}
