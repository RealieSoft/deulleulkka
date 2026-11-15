package app.deulleulkka.engine

/** 가 볼 곳(찜) 한 곳. */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

/** 내비게이션에 설정된 목적지. */
data class Destination(
    val lat: Double,
    val lon: Double,
    /** 즐겨찾기에 회사로 등록된 곳인지. */
    val isOffice: Boolean = false,
)

/**
 * 한 시점의 차량 맥락.
 * 실제 차량 SDK와 시나리오 재생기가 같은 형식으로 만들어 판단 엔진에 넣는다.
 * 차종이 지원하지 않는 신호는 null로 둔다.
 *
 * 안전 신호(다음 회전과 그 종류, 방향지시등, 브레이크, 단속 카메라·주의 지점, 통화·안내 음성)에는 기본값을 두지 않는다.
 * 넣는 쪽이 빠뜨린 값이 '꺼짐'으로 들어가 덜 조심하는 쪽으로 가지 않도록, 읽은 값이나 '모름'(null)을 늘 직접 적게 한다.
 */
data class DriveContext(
    val lat: Double,
    val lon: Double,
    /**
     * 차량 속도(km/h). 읽지 못하면 null이고, 그때는 묻지 않고 보류한다. NaN·무한대와 음수(표준 차량 속성은 후진하면
     * 음수를 줌)도 읽지 못한 것과 같이 보류한다.
     */
    val speedKph: Double?,
    /**
     * 진행 방향(북쪽에서 시계 방향으로 잰 도). 모르면 null이고, 그때는 목적지 쪽을 앞으로 본다.
     * 0~360 밖의 값은 360으로 나눈 나머지로 보고, NaN·무한대는 모름(null)과 같이 다룬다.
     */
    val headingDeg: Double? = null,
    /** 목적지가 없으면 null. 목적지가 있으면 길 안내 중으로 본다. */
    val destination: Destination?,
    val remainingKm: Double? = null,
    /** 목적지까지 남은 시간(분). NaN·무한대·음수는 받지 못한 것으로 본다. */
    val remainingMin: Double? = null,
    /**
     * 지금 위치에서 찜까지의 도로 시간(분), 찜 id별. 내비게이션의 `requestTrip`으로 경로를 바꾸지 않고 받는다.
     * 받지 못한 찜은 빠져 있고, 그 찜은 묻지 않고 보류한다([MomentJudge]). NaN·무한대·음수도 받지 못한 것으로 본다.
     */
    val tripMinToPlace: Map<String, Double> = emptyMap(),
    /**
     * 다음 회전(안내 지점)까지 남은 거리(m).
     * 길 안내 중(목적지 있음)에 null이면 회전이 없는 것이 아니라 확인할 수 없는 안전 신호로 보고, 완전히 멈춘 때만 묻는다.
     * 목적지가 없으면 안내가 없으므로 null로 둔다.
     */
    val nextTurnMeters: Int?,
    /**
     * 다음 회전이 나들목 진입·진출인지. 다음 회전까지 거리는 알지만 그 종류를 확인할 수 없으면 null이고,
     * 확인할 수 없는 안전 신호로 본다. 다음 회전 자체가 없으면(null) 이 값은 보지 않는다.
     */
    val nextTurnIsInterchange: Boolean?,
    /** 방향지시등. 차종이 지원하지 않아 확인할 수 없으면 null. */
    val turnSignalOn: Boolean?,
    /** 브레이크 페달. 확인할 수 없으면 null. */
    val braking: Boolean?,
    /** 단속 카메라·주의 지점 안내 구간인지. 확인할 수 없으면 null이고, 확인할 수 없는 안전 신호로 본다. */
    val cautionAhead: Boolean?,
    /** 통화 중이거나 내비게이션 안내 음성이 나오는 중인지. 확인할 수 없으면 null. */
    val otherAudioPlaying: Boolean?,
    /** 운전석 말고 누가 타고 있는지. 착좌 정보가 없는 차량은 null. */
    val passengerPresent: Boolean? = null,
    /** 배터리 충전율 또는 연료 잔량(%). 정보가 없으면 null. */
    val energyPercent: Int? = null,
    /**
     * 내비게이션에 이미 들어 있는 경유지 수. 받지 못했으면 null(엔진은 경유지가 있는지 모르는 것으로 보고 묻지 않는다).
     * 안전 신호처럼 기본값을 두지 않는다: 넣는 쪽이 빠뜨린 값이 "경유지 없음"으로 들어가 운전자의 경유지를 덮어쓰지 않도록.
     */
    val waypointCount: Int?,
)

/** 사용자가 주차 중에 바꿀 수 있는 값과 앱의 출발값. */
data class Settings(
    /** 더 걸려도 되는 시간(분). */
    val maxDetourMin: Double = 10.0,
    /** 혼자 탄 이동이면 더 걸려도 되는 시간을 절반으로 줄일지. 저희가 세운 가설이라 끌 수 있다. */
    val halveForSolo: Boolean = true,
    /** 한 번의 이동에 묻는 횟수. 답이 없던 것도 센다. */
    val maxAsksPerTrip: Int = 2,
    /** "다음에"라고 답한 곳을 쉬는 날수. */
    val snoozeDays: Int = 30,
    /** 이 값 아래로 잔량이 떨어지면 묻지 않는다(%). */
    val minEnergyPercent: Int = 20,
) {
    init {
        require(maxDetourMin.isFinite() && maxDetourMin >= 0.0) { "더 걸려도 되는 시간은 0분 이상이어야 함: $maxDetourMin" }
        require(maxAsksPerTrip in 0..MAX_ASKS_PER_TRIP) { "한 번의 이동에 묻는 횟수는 0~${MAX_ASKS_PER_TRIP}번: $maxAsksPerTrip" }
        require(snoozeDays >= 0) { "쉬는 날수는 0 이상이어야 함: $snoozeDays" }
        require(minEnergyPercent in 0..MAX_PERCENT) { "잔량 기준은 0~$MAX_PERCENT%: $minEnergyPercent" }
    }

    companion object {
        /** 한 번의 이동에 묻는 횟수의 상한. 사용자는 주차 중에 0~2번으로 줄일 수 있다(기획서 판단 표). */
        const val MAX_ASKS_PER_TRIP = 2

        private const val MAX_PERCENT = 100
    }
}

/** 판단의 네 단계. 이 순서로 보고, 처음 막힌 곳에서 멈춘다. */
enum class Step(val label: String) {
    SAFETY("안전"),
    ELIGIBILITY("자격"),
    VALUE("가치"),
    FREQUENCY("빈도"),
}

enum class Outcome(val label: String) {
    PASS("통과"),
    HOLD("보류"),
    SKIP("묻지 않음"),
    NOT_CHECKED("보지 않음"),
}

/** 판단 근거 화면의 한 줄: 무엇을 확인했고, 무엇을 봤고, 결과가 어땠는지. */
data class Check(
    val step: Step,
    val question: String,
    val seen: String,
    val outcome: Outcome,
)

sealed interface Verdict {
    /**
     * 지금 묻는다. [detourMin]은 어림한 추가 시간(분)이다.
     * 소리를 낼지는 엔진이 정하지 않는다: 통화·안내 음성을 확인할 수 없을 때([DriveContext.otherAudioPlaying]이 null)
     * 소리 없이 카드만 띄우는 것(기획서)은 앱의 화면·음성 쪽이 맡는다.
     */
    data class Ask(val detourMin: Double) : Verdict

    /** 지금은 말 걸 때가 아니다. 여유 구간에서 다시 판단한다. */
    data class Hold(val reason: String) : Verdict

    /**
     * 이번 판단에서는 묻지 않는다. 엔진이 이동 단위로 막는 것은 이번 이동에서 이미 물은 곳, 이번 이동에 묻는 횟수를
     * 다 쓴 경우, 보류한 사이 반경을 벗어난 곳([AskHistory])이고, 나머지는 다음 판단에서 다시 본다
     * (예: 지나친 찜은 굽은 길에서 다시 앞쪽이 될 수 있다).
     * [outsideTriggerRadius]는 찜이 판단 반경 밖이라 묻지 않은 것인지다. [AskHistory.recordVerdict]가 이 값으로
     * "보류한 사이 반경 이탈"을 남기므로, 앱이 위치 SDK의 영역 이탈 알림과 판단 결과를 어느 순서로 남겨도 같다.
     */
    data class Skip(val reason: String, val outsideTriggerRadius: Boolean = false) : Verdict
}

/** 판단 결과와 그 근거. 근거는 "왜 그때 물었는지", "왜 묻지 않았는지" 화면에 그대로 쓴다. */
data class Decision(
    val verdict: Verdict,
    val checks: List<Check>,
)
