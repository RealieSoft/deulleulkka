package app.deulleulkka

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
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
 * E4와 경유지 자리 확인.
 *
 * 1. 위치 SDK(FusedLocation): 지원 여부, 위치 콜백, 근접 알림(서울시청 반경 300m)을 건다.
 *    PC에서 `adb emu geo fix <경도> <위도>`로 위치를 옮기며 콜백과 ENTER/EXIT가 오는지 본다.
 *    같은 동안 내비의 현재 위치(getCurrentLocationInfo)도 5초마다 읽어 함께 움직이는지 본다.
 * 2. 경유지 자리: 목적지를 정하고 FIRST에 A를 넣은 뒤 다시 FIRST에 B를 넣어, 덮어쓰는지 뒤로 미는지 본다.
 *
 * 결과는 화면과 logcat(태그 DeulleulkkaProbe)에 남긴다.
 */
class LocationProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fused: FusedLocation
    private lateinit var navi: NaviHelper
    private lateinit var out: TextView

    // 근접 알림을 걸 지점(서울시청)
    private val alertLat = 37.5665
    private val alertLon = 126.9780
    private val alertRadiusMeters = 300f

    private val destination = RouteInfo(
        latitude = 37.48544722,
        longitude = 127.03636666,
        poiName = "42dot",
        poiId = "36khp37hh8gqcm5nqhe5",
        poiSubId = "0",
        address = "서울시 강남구 남부순환로 2621",
    )
    private val waypointA = RequestWaypointInfo(
        latitude = 37.41275833,
        longitude = 127.09481944,
        poiName = "소프트웨어드림센터",
        waypointIndex = WaypointIndex.FIRST,
        poiId = "36khp2hdj2cfcm5nqhe3",
        address = "경기도 성남시 수정구",
        poiSubId = "0",
    )
    private val waypointB = RequestWaypointInfo(
        latitude = 37.39475,
        longitude = 127.11120,
        poiName = "판교역",
        waypointIndex = WaypointIndex.FIRST,
        poiId = "",
        address = "경기도 성남시 분당구",
        poiSubId = "0",
    )

    private val locationListener = object : FusedLocationListener {
        // SDK는 이 인자를 null 허용으로 선언한다(javap에는 보이지 않아 컴파일로 확인)
        override fun onChanged(data: LocationData?) =
            log("fused.onChanged lat=${data?.latitude} lon=${data?.longitude} heading=${data?.heading} t=${data?.timestampMillis}")

        override fun onError(e: Exception) = log("fused.onError ${e::class.java.simpleName} ${e.message}")
    }

    private val alertListener = object : ProximityAlertListener {
        override fun onRegistered(id: String) = log("alert.onRegistered id=$id")

        override fun onTransition(event: ProximityAlertEvent) =
            log("alert.onTransition id=${event.alertId} ${event.transition}")

        override fun onError(e: Exception) = log("alert.onError ${e::class.java.simpleName} ${e.message}")
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = log("navi.onCurrentLocationInfo $info")

        override fun onRouteStarted(info: RouteStartInfo) =
            log("navi.onRouteStarted duration=${info.duration} distance=${info.distance}")

        override fun onWaypointChanged(info: WaypointChangedInfo) =
            log("navi.onWaypointChanged index=${info.index} cmd=${info.cmdType} route.duration=${info.routeStartInfo?.duration}")

        override fun onWayPointInfo(list: List<WaypointInfo>) =
            log("navi.onWayPointInfo ${list.size}건 " + list.joinToString { it.name })

        override fun onError(code: NaviErrorCode) = log("navi.onError $code")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 20f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(out) })

        fused = FusedLocation(this)
        step(0, "위치 SDK 지원 여부와 초기화") {
            log("fused.isSupported=${fused.isSupported()}")
            fused.initialize()
        }
        step(2_000, "위치 콜백과 근접 알림 등록") {
            fused.registerFusedLocationCallback(locationListener)
            fused.getLastKnownLocation(locationListener)
            fused.addProximityAlert(
                ProximityAlertRequest(alertLat, alertLon, alertRadiusMeters, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT)),
                alertListener,
            )
        }

        navi = NaviHelper(this)
        navi.initialize(
            {
                log("navi 연결됨")
                navi.addListener(naviListener)
                // 위치를 옮기는 동안 내비의 현재 위치를 5초마다 읽는다(0~60초)
                for (t in 0..60 step 5) step(t * 1_000L, "내비 현재 위치 조회") { navi.getCurrentLocationInfo() }
                waypointSteps(70_000)
            },
            { e -> log("navi 연결 실패 ${e::class.java.simpleName} ${e.message}") },
        )
    }

    private fun waypointSteps(start: Long) {
        step(start, "목적지 설정") { navi.requestRoute(destination) }
        step(start + 10_000, "FIRST에 A 넣기") { navi.addWaypoint(waypointA) }
        step(start + 22_000, "경유지 조회(A 뒤)") { navi.getWaypointInfo() }
        step(start + 25_000, "FIRST에 B 넣기") { navi.addWaypoint(waypointB) }
        step(start + 37_000, "경유지 조회(B 뒤)") { navi.getWaypointInfo() }
        step(start + 40_000, "경유지 모두 빼기") { navi.removeWaypoint(WaypointIndex.ALL) }
        step(start + 50_000, "경유지 조회(뺀 뒤)") { navi.getWaypointInfo() }
        step(start + 53_000, "경로 취소") { navi.cancelRoute() }
        step(start + 56_000, "끝") {}
    }

    private fun step(delayMs: Long, name: String, action: () -> Unit) {
        handler.postDelayed({
            log("── $name")
            runCatching(action).onFailure { log("예외 ${it::class.java.simpleName} ${it.message}") }
        }, delayMs)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { fused.unregisterFusedLocationCallback(locationListener); fused.release() }
        runCatching { navi.removeListener(naviListener); navi.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DeulleulkkaProbe"
    }
}
