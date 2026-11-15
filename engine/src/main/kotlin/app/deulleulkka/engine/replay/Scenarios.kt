package app.deulleulkka.engine.replay

import app.deulleulkka.engine.Destination
import app.deulleulkka.engine.DriveContext
import app.deulleulkka.engine.Place

/** 재생할 한 장면: 시각 표시와 그때의 차량 맥락. 공개 길찾기로 잰 장면이면 [routeCheck]에 그 값을 둔다. */
data class Frame(val clock: String, val note: String, val ctx: DriveContext, val routeCheck: RouteCheck? = null)

/** 같은 지점을 공개 길찾기로 잰 실제 추가 시간(분)과 그 출처. */
data class RouteCheck(val actualDetourMin: Double, val source: String)

/**
 * 기획서의 사용 시나리오를 차량 맥락의 흐름으로 적은 것.
 * 에뮬레이터가 넣어 주지 못하는 신호(위치 이동, 경로 진행)를 대신 넣는 시나리오 재생기의 입력이며,
 * 단위 테스트도 같은 값을 쓴다. 좌표와 수치는 모두 가상의 예시다.
 */
object Scenarios {
    /** 11:12 위치에서 북쪽으로 약 1.6km(진행 방향 동쪽에서 85°, 앞쪽). 11:11 위치에서는 약 1.94km라 막 판단 반경(2km)에 든다. */
    val bakery = Place("bakery", "밀밭 베이커리", lat = 37.6143, lon = 127.2016)

    private val arboretum = Destination(lat = 37.6000, lon = 127.5000)
    private val office = Destination(lat = 37.6000, lon = 127.5000, isOffice = true)

    /**
     * 토요일 오전 11:12, 아이와 함께 수목원으로 가는 길의 직선 구간. 동쪽으로 달리고, 나들이 길이 막혀 남은 30km에 90분이다.
     * 빵집까지는 내비게이션이 도로로 3.3분이라고 알려 준다(`requestTrip`을 대신하는 재생기 값).
     * 다음 회전은 나들목이 아니고, 방향지시등·브레이크는 꺼져 있고, 단속 카메라·주의 지점 구간이 아니며 통화·안내 음성은 없다(모두 읽은 값).
     */
    private val saturdayCalm = DriveContext(
        lat = 37.6000,
        lon = 127.2000,
        speedKph = 62.0,
        headingDeg = 90.0,
        destination = arboretum,
        remainingKm = 30.0,
        remainingMin = 90.0,
        tripMinToPlace = mapOf(bakery.id to 3.3),
        nextTurnMeters = 1400,
        nextTurnIsInterchange = false,
        turnSignalOn = false,
        braking = false,
        cautionAhead = false,
        otherAudioPlaying = false,
        passengerPresent = true,
        energyPercent = 71,
    )

    /**
     * 시나리오 D → A: 우회전 직전이라 미뤘다가 직선 구간에서 묻는다.
     * 11:11에는 북쪽으로 달리며 300m 앞에서 우회전(동쪽)하려는 참이고, 돌고 나서 1분 뒤 11:12 위치에 온다.
     */
    val saturdayOuting: List<Frame> = listOf(
        Frame(
            "오전 11:11", "찜한 곳 근처에 들어옴",
            saturdayCalm.copy(
                lat = 37.5973,
                lon = 127.1966,
                speedKph = 38.0,
                headingDeg = 0.0,
                remainingKm = 30.6,
                remainingMin = 94.0,
                tripMinToPlace = mapOf(bakery.id to 4.0),
                nextTurnMeters = 300,
                turnSignalOn = true,
            ),
        ),
        Frame("오전 11:12", "회전을 마치고 직선 구간", saturdayCalm),
    )

    /** 시나리오 C: 월요일 아침 혼자 출근하는 길. */
    val mondayCommute: List<Frame> = listOf(
        Frame(
            "오전 8:12", "찜한 곳 근처에 들어옴",
            saturdayCalm.copy(
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
            saturdayCalm.copy(destination = null, remainingKm = null, remainingMin = null, nextTurnMeters = null),
        ),
    )

    /**
     * 공개 길찾기 경로로 잰 경우 하나를 그대로 넣은 장면: `docs/route-check`의 검증용 NC1(경주 분황로에서 서쪽으로, 찜 대릉원, 목적지 건천읍).
     * 지금 위치·찜·목적지 좌표, 찜까지의 경로 시간, 목적지까지 남은 거리·시간, 실제 추가 시간은 `followup_results.json`의 값을 그대로 옮겼다.
     * 시각, 속도, 다음 회전까지 거리, 나들목 아님, 방향지시등·브레이크·주의 지점·통화(모두 꺼짐), 동승자, 잔량은 재생기의 가상 값이다.
     * 진행 방향은 넣지 않아 목적지 쪽을 앞으로 본다.
     */
    val daereungwon = Place("daereungwon", "대릉원", lat = 35.838245, lon = 129.211509)

    val gyeongjuMeasured: List<Frame> = listOf(
        Frame(
            "오후 2:30", "찜한 곳 근처에 들어옴 (공개 길찾기 경로 값)",
            DriveContext(
                lat = 35.840585,
                lon = 129.232388,
                speedKph = 40.0,
                destination = Destination(lat = 35.840144, lon = 129.100185),
                remainingKm = 15.8578,
                remainingMin = 17.740000000000002,
                tripMinToPlace = mapOf("daereungwon" to 4.241666666666666),
                nextTurnMeters = 1200,
                nextTurnIsInterchange = false,
                turnSignalOn = false,
                braking = false,
                cautionAhead = false,
                otherAudioPlaying = false,
                passengerPresent = true,
                energyPercent = 60,
            ),
            routeCheck = RouteCheck(actualDetourMin = 2.848333333333331, source = "docs/route-check NC1"),
        ),
    )
}
