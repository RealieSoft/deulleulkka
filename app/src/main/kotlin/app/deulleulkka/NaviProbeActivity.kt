package app.deulleulkka

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView

/**
 * 에뮬레이터의 내비게이션이 NaviHelper 요청에 무엇을 돌려주는지 차례로 확인한다(E2, E3).
 *
 * 1. 연결 → 즐겨찾기·최근 목적지·현재 위치 조회
 * 2. 목적지 설정
 * 3. 경유지 넣기 전 남은 시간 → 경유 후보로 requestTrip → 경유지 넣기 → 남은 시간 → 경유지 빼기
 *
 * 결과는 화면과 logcat(태그 DeulleulkkaProbe)에 남긴다.
 */
class NaviProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var navi: NaviHelper
    private lateinit var out: TextView

    // 문서 예제의 좌표와 장소(Pleos NaviHelper 가이드)
    private val destination = RouteInfo(
        latitude = 37.48544722,
        longitude = 127.03636666,
        poiName = "42dot",
        poiId = "36khp37hh8gqcm5nqhe5",
        poiSubId = "0",
        address = "서울시 강남구 남부순환로 2621",
    )
    private val waypointLat = 37.41275833
    private val waypointLon = 127.09481944
    private val waypointPoiId = "36khp2hdj2cfcm5nqhe3"
    private val waypointName = "소프트웨어드림센터"
    private val waypointAddress = "경기도 성남시 수정구"

    private val listener = object : NaviHelperEventListener {
        override fun onBookmarkInfo(list: List<BookmarkInfo>) =
            log("onBookmarkInfo: ${list.size}건 " + list.joinToString { "${it.name}(${it.category})" })

        override fun onBookMarkChanged() = log("onBookMarkChanged")

        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) =
            log("onRecentDestinationInfo: ${list.size}건 " + list.joinToString { it.name })

        override fun onCurrentLocationInfo(info: ai.pleos.playground.navi.data.CurrentLocationInfo) =
            log("onCurrentLocationInfo: $info")

        override fun onRouteStarted(info: RouteStartInfo) =
            log("onRouteStarted: duration=${info.duration}s distance=${info.distance}m name=${info.name}")

        override fun onRoutingCompleted() = log("onRoutingCompleted")

        override fun onRouteCancelled() = log("onRouteCancelled")

        override fun onDestinationInfo(info: DestinationInfo) =
            log("onDestinationInfo: ${info.name} remainTime=${info.remainTime}s remainDistance=${info.remainDistance}m")

        override fun onWayPointInfo(list: List<WaypointInfo>) =
            log("onWayPointInfo: ${list.size}건 " + list.joinToString { "${it.name} remainTime=${it.remainTime}s" })

        override fun onWaypointChanged(info: WaypointChangedInfo) =
            log(
                "onWaypointChanged: index=${info.index} cmd=${info.cmdType} " +
                    "route.duration=${info.routeStartInfo?.duration}s route.distance=${info.routeStartInfo?.distance}m",
            )

        override fun onTripInfo(info: TripInfo) =
            log("onTripInfo: distance=${info.distance}m eta=${info.eta} soc=${info.estimatedSocAtDest}")

        override fun onTBTInfo(list: List<TBTInfo>) =
            log("onTBTInfo: " + list.take(2).joinToString { "${it.type} ${it.distance}m ${it.description}" })

        override fun onNavigationStatus(status: NavigationStatus) = log("onNavigationStatus: $status")

        override fun onError(code: NaviErrorCode) = log("onError: $code")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 22f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(out) })

        navi = NaviHelper(this)
        log("initialize 요청")
        navi.initialize(
            {
                log("연결됨")
                navi.addListener(listener)
                runSteps()
            },
            { e -> log("연결 실패: ${e::class.java.simpleName} ${e.message}") },
        )
    }

    private fun runSteps() {
        step(0, "즐겨찾기·최근 목적지·현재 위치 조회") {
            navi.getBookmarkInfo()
            navi.getRecentDestinationInfo()
            navi.getCurrentLocationInfo()
        }
        step(4_000, "목적지 설정 requestRoute") { navi.requestRoute(destination) }
        step(14_000, "경유 전 남은 시간 조회") {
            navi.getDestinationInfo()
            navi.getTBTInfo()
        }
        step(17_000, "경유 후보로 requestTrip") {
            navi.requestTrip(TripRequest(waypointLat, waypointLon, waypointPoiId, waypointName, "0"))
        }
        step(21_000, "경유지 넣기 addWaypoint") {
            navi.addWaypoint(
                RequestWaypointInfo(
                    latitude = waypointLat,
                    longitude = waypointLon,
                    poiName = waypointName,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = waypointPoiId,
                    address = waypointAddress,
                    poiSubId = "0",
                ),
            )
        }
        step(31_000, "경유 후 남은 시간 조회") {
            navi.getDestinationInfo()
            navi.getWaypointInfo()
        }
        step(35_000, "경유지 빼기 removeWaypoint") { navi.removeWaypoint(WaypointIndex.FIRST) }
        step(43_000, "뺀 뒤 남은 시간 조회") {
            navi.getDestinationInfo()
            navi.getWaypointInfo()
        }
        step(46_000, "끝") {}
    }

    private fun step(delayMs: Long, name: String, action: () -> Unit) {
        handler.postDelayed({
            log("── $name")
            runCatching(action).onFailure { log("예외: ${it::class.java.simpleName} ${it.message}") }
        }, delayMs)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { navi.removeListener(listener); navi.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DeulleulkkaProbe"
    }
}
