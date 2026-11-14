package app.deulleulkka.engine.replay

import app.deulleulkka.engine.Destination
import app.deulleulkka.engine.DriveContext
import app.deulleulkka.engine.Place
import app.deulleulkka.engine.PlaceKind

/** 재생할 한 장면: 시각 표시와 그때의 차량 맥락. */
data class Frame(val clock: String, val note: String, val ctx: DriveContext)

/**
 * 기획서의 사용 시나리오를 차량 맥락의 흐름으로 적은 것.
 * 에뮬레이터가 넣어 주지 못하는 신호(위치 이동, 경로 진행)를 대신 넣는 시나리오 재생기의 입력이며,
 * 단위 테스트도 같은 값을 쓴다. 좌표와 수치는 모두 가상의 예시다.
 */
object Scenarios {
    val bakery = Place("bakery", "밀밭 베이커리", PlaceKind.EAT, lat = 37.6560, lon = 127.2600)

    private val arboretum = Destination(lat = 37.6000, lon = 127.5000)
    private val office = Destination(lat = 37.6000, lon = 127.5000, isOffice = true)

    private const val SUNSET = 17 * 60 + 20

    /** 토요일 오전 11:12, 아이와 함께 수목원으로 가는 길의 직선 구간. */
    private val saturdayCalm = DriveContext(
        minuteOfDay = 11 * 60 + 12,
        sunsetMinuteOfDay = SUNSET,
        lat = 37.6000,
        lon = 127.2000,
        speedKph = 62.0,
        destination = arboretum,
        remainingKm = 34.0,
        remainingMin = 41.0,
        nextTurnMeters = 1400,
        passengerPresent = true,
        energyPercent = 71,
    )

    /** 시나리오 D → A: 우회전 직전이라 미뤘다가 직선 구간에서 묻는다. */
    val saturdayOuting: List<Frame> = listOf(
        Frame(
            "오전 11:08", "찜한 곳 근처에 들어옴",
            saturdayCalm.copy(minuteOfDay = 11 * 60 + 8, nextTurnMeters = 300, turnSignalOn = true),
        ),
        Frame("오전 11:12", "회전을 마치고 직선 구간", saturdayCalm),
    )

    /** 시나리오 C: 월요일 아침 혼자 출근하는 길. */
    val mondayCommute: List<Frame> = listOf(
        Frame(
            "오전 8:12", "찜한 곳 근처에 들어옴",
            saturdayCalm.copy(
                minuteOfDay = 8 * 60 + 12,
                destination = office,
                nextTurnMeters = 2300,
                passengerPresent = false,
            ),
        ),
    )

    /** 목적지를 넣지 않고 달리는 날. */
    val noDestination: List<Frame> = listOf(
        Frame(
            "오후 3:40", "찜한 곳 근처에 들어옴",
            saturdayCalm.copy(minuteOfDay = 15 * 60 + 40, destination = null, remainingKm = null, remainingMin = null, nextTurnMeters = null),
        ),
    )
}
