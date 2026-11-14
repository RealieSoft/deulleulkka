package app.deulleulkka.engine

/** 사용자가 주차 중에 표시해 둔 장소의 유형. */
enum class PlaceKind { EAT, SEE }

/** 가 볼 곳(찜) 한 곳. */
data class Place(
    val id: String,
    val name: String,
    val kind: PlaceKind,
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
 */
data class DriveContext(
    /** 자정부터 센 분(0~1439). */
    val minuteOfDay: Int,
    /** 그날 해가 지는 시각(자정부터 센 분). */
    val sunsetMinuteOfDay: Int,
    val lat: Double,
    val lon: Double,
    val speedKph: Double,
    /** 목적지가 없으면 null. */
    val destination: Destination?,
    val remainingKm: Double? = null,
    val remainingMin: Double? = null,
    /** 다음 회전까지 남은 거리(m). 안내가 없으면 null. */
    val nextTurnMeters: Int? = null,
    /** 다음 회전이 나들목 진입·진출인지. */
    val nextTurnIsInterchange: Boolean = false,
    /** 방향지시등. 차종이 지원하지 않아 확인할 수 없으면 null. */
    val turnSignalOn: Boolean? = false,
    /** 브레이크 페달. 확인할 수 없으면 null. */
    val braking: Boolean? = false,
    /** 단속 카메라·주의 지점 안내 구간인지. */
    val cautionAhead: Boolean = false,
    /** 통화 중이거나 내비게이션 안내 음성이 나오는 중인지. 확인할 수 없으면 null. */
    val otherAudioPlaying: Boolean? = false,
    /** 운전석 말고 누가 타고 있는지. 착좌 정보가 없는 차량은 null. */
    val passengerPresent: Boolean? = null,
    /** 배터리 충전율 또는 연료 잔량(%). 정보가 없으면 null. */
    val energyPercent: Int? = null,
    val waypointCount: Int = 0,
)

/** 사용자가 주차 중에 바꿀 수 있는 값과 앱의 출발값. */
data class Settings(
    /** 더 걸려도 되는 시간(분). */
    val maxDetourMin: Double = 10.0,
    /** 한 번의 이동에 묻는 횟수. 답이 없던 것도 센다. */
    val maxAsksPerTrip: Int = 2,
    /** "다음에"라고 답한 곳을 쉬는 날수. */
    val snoozeDays: Int = 30,
    /** 이 값 아래로 잔량이 떨어지면 묻지 않는다(%). */
    val minEnergyPercent: Int = 20,
)

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
    /** 지금 묻는다. [detourMin]은 어림한 추가 시간(분)이다. */
    data class Ask(val detourMin: Double) : Verdict

    /** 지금은 말 걸 때가 아니다. 여유 구간에서 다시 판단한다. */
    data class Hold(val reason: String) : Verdict

    /** 이 이동에서는 묻지 않는다. */
    data class Skip(val reason: String) : Verdict
}

/** 판단 결과와 그 근거. 근거는 "왜 그때 물었는지", "왜 묻지 않았는지" 화면에 그대로 쓴다. */
data class Decision(
    val verdict: Verdict,
    val checks: List<Check>,
)
