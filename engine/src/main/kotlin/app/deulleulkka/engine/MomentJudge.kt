package app.deulleulkka.engine

import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 찜한 곳 근처에 들어왔을 때 "지금 물어도 되는가"를 정한다.
 *
 * 안전 → 자격 → 가치 → 빈도 순서로 보고, 처음 막힌 곳에서 멈춘다.
 * 본 것과 결과를 줄마다 남겨, 주차 중 판단 근거 화면이 그대로 보여 줄 수 있게 한다.
 */
class MomentJudge(private val settings: Settings = Settings()) {

    /**
     * [roadFactor]는 직선거리로 우회를 어림할 때 쓸 도로 보정값이다. 찜까지의 도로 시간이 없으면 묻지 않고 보류하므로,
     * 지금은 판단 결과를 바꾸지 않는다([DetourEstimator] 참고).
     *
     * 이동 단위 기록은 앱이 [history]에 남긴다. 판단할 때마다 그 결과를 [AskHistory.recordVerdict]로,
     * 찜의 판단 반경을 벗어나면 [AskHistory.recordRadiusExit]로 남긴다. 엔진은 반경 밖의 찜을 그 자리에서 묻지 않지만,
     * "보류한 사이 반경을 벗어났다"는 기록은 앱이 반경 이탈을 알려야 생긴다(위치 SDK의 영역 이탈 알림을 받았을 때,
     * 또는 판단 결과가 반경 밖으로 나왔을 때 부른다). 보류한 사이 반경을 벗어난 찜은 그 이동에서는 다시 들어와도 묻지 않는다.
     */
    fun judge(
        ctx: DriveContext,
        place: Place,
        history: AskHistory,
        today: Long,
        roadFactor: Double = DetourEstimator.ROAD_FACTOR,
    ): Decision {
        val detourMin = DetourEstimator.estimateMinutes(ctx, place, roadFactor)
        val rows: List<Triple<Step, String, () -> Pair<String, Outcome>>> = listOf(
            Triple(Step.SAFETY, "지금 말 걸어도 되나") { safety(ctx, place, history) },
            Triple(Step.ELIGIBILITY, "어떤 이동인가") { tripKind(ctx) },
            Triple(Step.ELIGIBILITY, "갈 여력이 있나") { capacity(ctx) },
            Triple(Step.VALUE, "얼마나 더 걸리나") { detour(ctx, place, detourMin) },
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
        if (settings.halveForSolo && ctx.passengerPresent == false) settings.maxDetourMin / SOLO_DIVISOR else settings.maxDetourMin

    /**
     * 찜이 진행 방향의 뒤쪽에 있는지(이미 지나쳤는지). 진행 방향을 모르면(null·NaN·무한대) 목적지 쪽을 앞으로 본다.
     * 진행 방향이 0~360 밖이면 360으로 나눈 나머지로 본다(720° = 0°, -10° = 350°).
     * 지나친 곳을 물으면 되돌아가게 되므로 이번 판단에서는 묻지 않는다(다음 판단에서 다시 본다, [Verdict.Skip]).
     */
    fun isBehind(ctx: DriveContext, place: Place): Boolean {
        val dest = ctx.destination ?: return false
        val heading = ctx.headingDeg?.takeIf { it.isFinite() }?.let { (it % FULL_TURN_DEG + FULL_TURN_DEG) % FULL_TURN_DEG }
        val ahead = heading ?: DetourEstimator.bearingDeg(ctx.lat, ctx.lon, dest.lat, dest.lon)
        val toPlace = DetourEstimator.bearingDeg(ctx.lat, ctx.lon, place.lat, place.lon)
        val diff = abs((toPlace - ahead + HALF_TURN_DEG * 3) % FULL_TURN_DEG - HALF_TURN_DEG)
        return diff > AHEAD_MAX_DEG
    }

    /** 찜에서 [TRIGGER_RADIUS_METERS] 안에 들어왔는지. 앱은 위치 SDK의 영역 진입 알림으로 판단을 시작한다. */
    fun inTriggerRadius(ctx: DriveContext, place: Place): Boolean =
        DetourEstimator.distanceKm(ctx.lat, ctx.lon, place.lat, place.lon) * METERS_PER_KM <= TRIGGER_RADIUS_METERS

    /** 경유지를 넣은 뒤 내비게이션이 다시 계산한 값이 허용 범위를 넘는지. 넘으면 한 번 더 묻는다. */
    fun exceedsAllowance(actualDetourMin: Double, ctx: DriveContext): Boolean =
        actualDetourMin > allowedDetourMin(ctx)

    /**
     * 판단을 통과한 곳이 여럿일 때 먼저 물을 곳. 덜 돌아가는 곳(판단이 어림한 우회 시간)부터 묻는다.
     * 판단을 통과하지 못한 후보(지나친 곳, 도로 시간이 없어 보류된 곳 등)는 고르지 않고, 하나도 없으면 null.
     * 후보마다의 판단 결과는 돌려주지 않으므로, 앱은 [AskHistory.recordVerdict]에 남길 결과를 후보마다 [judge]로 따로 받는다.
     */
    fun pickFirst(
        ctx: DriveContext,
        candidates: List<Place>,
        history: AskHistory,
        today: Long,
        roadFactor: Double = DetourEstimator.ROAD_FACTOR,
    ): Place? =
        candidates
            .mapNotNull { place -> (judge(ctx, place, history, today, roadFactor).verdict as? Verdict.Ask)?.let { place to it.detourMin } }
            .minByOrNull { (_, detourMin) -> detourMin }
            ?.first

    /**
     * 안전 단계. 찜의 판단 반경 안에서만 판단하고, 보류한 사이 반경을 벗어났던 찜은 그 이동에서는 묻지 않는다.
     * 속도를 읽지 못하면 보류한다(기획서 판단 표). null·NaN·무한대와 음수(후진하면 음수로 옴)를 모두 "속도를 읽지 못함"으로
     * 다룬다. 후진을 따로 다루는 규칙은 두지 않고, 음수 속도를 멈춤(시속 1km 아래)으로 잘못 보지 않게만 한다.
     * 확인할 수 없는 안전 신호가 있으면 덜 조심하는 쪽이 아니라 더 조심하는 쪽으로 간다:
     * 그때는 완전히 멈춘 때만 묻는다. 길 안내 중(목적지 있음)인데 다음 회전을 모르는 것, 다음 회전의 종류(나들목인지)나
     * 단속 카메라·주의 지점 구간인지를 모르는 것도 확인할 수 없는 안전 신호다.
     * 멈춘 때는 브레이크를 보지 않는다(신호 대기 중에는 늘 밟고 있으므로).
     *
     * 완전히 멈춘 때도 통화·안내 음성과 찜까지의 거리는 먼저 본다. 그 밖에는 방향지시등과 100m 안 회전만 보고,
     * 단속 카메라·주의 지점 구간과 나들목 1.5km는 보지 않는다. 기획서 판단 표의 "완전히 멈춘 때는 … 여유 구간으로 보되,
     * 방향지시등이 켜져 있거나 100m 안에 회전이 있으면 보류"를 문자 그대로 따른 것이다. 멈춘 때에도 그 두 규칙을 볼지는
     * 기획서가 따로 정하지 않았다.
     */
    private fun safety(ctx: DriveContext, place: Place, history: AskHistory): Pair<String, Outcome> {
        val toPlaceMeters = DetourEstimator.distanceKm(ctx.lat, ctx.lon, place.lat, place.lon) * METERS_PER_KM
        if (!inTriggerRadius(ctx, place)) {
            val km = shownAgainst(toPlaceMeters / METERS_PER_KM, TRIGGER_RADIUS_KM, over = true, digits = 1)
            return "찜까지 ${km}km (판단 반경 ${exactNumber(TRIGGER_RADIUS_KM)}km 밖)" to Outcome.SKIP
        }
        if (place.id in history.leftWhileHeldThisTrip) {
            return "보류한 사이 반경을 벗어났던 곳 (이번 이동에서는 묻지 않음)" to Outcome.SKIP
        }
        // 속도를 모르면 멈춤·회전 여유·찜까지의 여유를 하나도 정할 수 없다
        val speed = ctx.speedKph?.takeIf { it.isFinite() && it >= 0.0 } ?: return "속도 모름" to Outcome.HOLD
        val turn = ctx.nextTurnMeters
        val stopped = speed < STOPPED_KPH
        val calmMeters =
            if (ctx.nextTurnIsInterchange == true) INTERCHANGE_CALM_METERS
            else max(MIN_CALM_METERS, speed / KPH_PER_MPS * CALM_SECONDS)
        // 수락하자마자 급하게 빠지지 않도록, 찜까지 이 거리보다 가까우면 묻지 않고 보류한다
        val leadMeters = max(MIN_LEAD_METERS, speed / KPH_PER_MPS * LEAD_SECONDS)
        val unknown = listOfNotNull(
            "다음 회전".takeIf { ctx.destination != null && turn == null },
            "다음 회전 종류".takeIf { turn != null && ctx.nextTurnIsInterchange == null },
            "방향지시등".takeIf { ctx.turnSignalOn == null },
            "브레이크".takeIf { ctx.braking == null },
            "단속 카메라·주의 지점".takeIf { ctx.cautionAhead == null },
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
            // 고속도로에서는 묻는 창이 몇 초뿐이고, 나들목으로 빠져 되돌아오는 길은 어림이 크게 틀릴 수 있다
            speed >= HIGH_SPEED_KPH -> "고속 주행 중 (시속 ${HIGH_SPEED_KPH.roundToInt()}km 이상)" to Outcome.HOLD
            ctx.cautionAhead == true -> "단속 카메라·주의 지점 안내 구간" to Outcome.HOLD
            turn != null && turn < calmMeters ->
                (if (ctx.nextTurnIsInterchange == true) "나들목 " else "") + turnText(turn) to Outcome.HOLD
            else -> "여유 구간" + turnText(turn, prefix = ", ") to Outcome.PASS
        }
    }

    private fun turnText(meters: Int?, prefix: String = ""): String = when {
        meters == null -> ""
        meters >= METERS_PER_KM -> "$prefix${String.format(Locale.ROOT, "%.1f", meters / METERS_PER_KM.toDouble())}km 앞 회전"
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

    /**
     * 잔량을 읽지 못하면(null) 막지 않고 묻는다. 기획서 판단 표는 잔량을 모를 때를 정하지 않았고,
     * "확인할 수 없으면 더 조심하는" 안전 신호 목록(방향지시등, 브레이크, 통화·안내 음성)에도 잔량은 없다.
     */
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

    /**
     * 우회 시간을 허용 시간과 견준다. 근거 문구의 분은 묻는 말과 같게 반올림한 정수 분으로 적되,
     * 그렇게 적으면 견준 결과와 어긋나 보일 때(허용 5분에 5.4분인데 "약 5분 … (5분까지)"로 보이며 묻지 않음)는
     * 소수 첫째 자리까지 결과 쪽으로 적는다([shownAgainst]).
     */
    private fun detour(ctx: DriveContext, place: Place, detourMin: Double?): Pair<String, Outcome> {
        // 목적지가 없으면 앞 단계(어떤 이동인가)에서 이미 멈춘다.
        val minutes = checkNotNull(detourMin)
        if (isBehind(ctx, place)) return "이미 지나친 곳 (되돌아가야 함)" to Outcome.SKIP
        // 직선거리 어림은 실제보다 크게 짧을 수 있어 그 값으로는 묻지 않는다. 값을 받으면 다음 판단에서 다시 본다
        if (!DetourEstimator.usesTrip(ctx, place)) {
            val missing = if (DetourEstimator.tripMinTo(ctx, place) == null) "찜까지 도로 시간 없음" else "남은 시간 정보 없음"
            return "$missing (받으면 다시 판단)" to Outcome.HOLD
        }
        val allowed = allowedDetourMin(ctx)
        val over = minutes > allowed
        val seen = "약 ${shownAgainst(minutes, allowed, over, digits = 0)}분 더 걸림 (${exactNumber(allowed)}분까지)"
        return seen to if (over) Outcome.SKIP else Outcome.PASS
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

        /** 이 속도(km/h) 이상이면 고속 주행으로 보고 보류한다. 국도의 시속 80km는 묻는 범위에 남긴다. */
        const val HIGH_SPEED_KPH = 90.0

        /** 진행 방향과 찜 방향의 차이가 이 각도(도)보다 크면 지나친 곳으로 본다. */
        const val AHEAD_MAX_DEG = 90.0

        /** 찜에서 이 거리(m) 안에 들어오면 판단을 시작한다. 그 밖의 찜은 묻지 않는다. */
        const val TRIGGER_RADIUS_METERS = 2000.0

        private const val HALF_TURN_DEG = 180.0
        private const val FULL_TURN_DEG = 360.0

        /**
         * 묻는 말. 반올림해 1분이 안 되는 곳(0.5분 미만)은 "약 0분"이라 하지 않고 분을 빼고 묻는다.
         * 분은 판단 근거 문구와 같은 규칙으로 적는다: 보통은 반올림한 정수 분이고, 허용 시간 [allowedMin]이 정수 분이 아니어서
         * 반올림하면 허용 시간을 넘어 보일 때만(예: 설정 7분에 혼자 탄 이동의 허용 3.5분, 어림 3.5분) 소수 첫째 자리로 적는다.
         * [allowedMin]을 주지 않으면 늘 반올림한 정수 분이다. [detourMin]은 판단이 돌려준 0 이상의 유한한 값이어야 한다.
         */
        fun question(place: Place, detourMin: Double, allowedMin: Double = Double.POSITIVE_INFINITY): String {
            val subject = withSubjectParticle(place.name)
            return if (detourMin.roundToInt() < 1) {
                "찜해 둔 $subject 가는 길에 있어요. 들를까요?"
            } else {
                val minutes = shownAgainst(detourMin, allowedMin, over = detourMin > allowedMin, digits = 0)
                "찜해 둔 $subject 가는 길 근처에 있어요. 약 ${minutes}분 더 걸리는데 들를까요?"
            }
        }

        /**
         * 이름 뒤에 주격 조사를 붙인다. 읽을 때 마지막 소리에 받침이 있으면 "이", 없으면 "가".
         * 끝의 괄호·문장부호·빈칸은 건너뛰고 마지막으로 읽는 글자를 본다.
         * - 한글: 그 글자의 받침.
         * - 숫자: 끝자리를 읽는 소리. 1(일)·3(삼)·6(육)·7(칠)·8(팔)과 0(십·백·천·만·영)은 받침이 있고, 2(이)·4(사)·5(오)·9(구)는 없다.
         * - 영문 한 글자: 글자 이름. L(엘)·M(엠)·N(엔)·R(알)은 받침이 있다.
         * - 영문 낱말: 끝 글자로 어림한다. l·m·n·ng로 끝나면 받침이 있다고 본다. 영어를 한글로 읽는 소리는
         *   규칙만으로 정해지지 않아 틀릴 수 있다(예: Book은 "북"이지만 "가"를 붙인다).
         * - 그 밖의 문자: "가".
         */
        fun withSubjectParticle(name: String): String {
            if (name.isEmpty()) return name
            val readable = name.trimEnd { !it.isLetterOrDigit() }
            val last = readable.lastOrNull() ?: return name + "가"
            val hasFinal = when {
                last in HANGUL_FIRST..HANGUL_LAST -> (last - HANGUL_FIRST) % FINAL_CONSONANT_COUNT != 0
                last in '0'..'9' -> last in DIGITS_WITH_FINAL
                last.isLatinLetter() -> latinHasFinal(readable)
                else -> false
            }
            return name + if (hasFinal) "이" else "가"
        }

        private fun Char.isLatinLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

        /** [text]는 영문 글자로 끝난다. 그 글자가 홀로 있으면 글자 이름으로, 낱말의 끝이면 끝 글자로 본다. */
        private fun latinHasFinal(text: String): Boolean {
            val last = text.last()
            val alone = text.length == 1 || !text[text.length - 2].isLatinLetter()
            if (alone) return last.uppercaseChar() in LETTER_NAMES_WITH_FINAL
            val lower = text.lowercase(Locale.ROOT)
            return lower.endsWith("ng") || lower.last() in WORD_ENDINGS_WITH_FINAL
        }

        private const val HANGUL_FIRST = '가'
        private const val HANGUL_LAST = '힣'

        /** 한글 음절표에서 받침 자리의 수(받침 없음 포함). */
        private const val FINAL_CONSONANT_COUNT = 28

        /** 읽는 소리에 받침이 있는 끝자리 숫자. */
        private const val DIGITS_WITH_FINAL = "013678"

        /** 이름(엘, 엠, 엔, 알)에 받침이 있는 영문 글자. */
        private const val LETTER_NAMES_WITH_FINAL = "LMNR"

        /** 이 글자로 끝나는 영문 낱말은 받침이 있다고 어림한다. */
        private const val WORD_ENDINGS_WITH_FINAL = "lmn"

        /**
         * [value]를 [limit]와 견준 결과([over]: [value]가 [limit]보다 큰지)가 적은 숫자에서도 그대로 읽히게 적는다.
         * 보통은 소수 [digits]자리로 반올림하고, 반올림한 숫자로는 결과가 반대로 보일 때만 한 자리 더 적되
         * 결과 쪽으로(넘으면 올리고, 아니면 내려) 적는다. 예: 허용 5분에 5.4분이면 "5"가 아니라 "5.4".
         */
        private fun shownAgainst(value: Double, limit: Double, over: Boolean, digits: Int): String {
            val scale = 10.0.pow(digits)
            val rounded = Math.round(value * scale) / scale
            if ((rounded > limit) == over) return String.format(Locale.ROOT, "%.${digits}f", rounded)
            val finer = scale * 10
            val directed = if (over) ceil(value * finer) / finer else floor(value * finer) / finer
            return String.format(Locale.ROOT, "%.${digits + 1}f", directed)
        }

        /** 기준값을 줄이지 않고 그대로 적는다(10.0 → "10", 3.5 → "3.5"). */
        private fun exactNumber(value: Double): String =
            if (value == floor(value)) value.toLong().toString() else BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

        private const val KPH_PER_MPS = 3.6
        private const val METERS_PER_KM = 1000
        private const val TRIGGER_RADIUS_KM = TRIGGER_RADIUS_METERS / METERS_PER_KM
    }
}
