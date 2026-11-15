package app.deulleulkka

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.WaypointCmdType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import app.deulleulkka.core.Turn
import app.deulleulkka.core.Turns
import app.deulleulkka.engine.DetourEstimator
import app.deulleulkka.engine.Destination
import app.deulleulkka.engine.Place
import java.util.Locale

/**
 * 내비게이션 SDK(NaviHelper) 연결. 콜백은 binder 스레드에서 오므로 [main]으로 넘겨 화면 스레드에서만 상태를 바꾼다.
 * requestTrip의 답(TripInfo)에는 어느 곳의 값인지가 없어서, 한 번에 하나만 보내고 돌아온 답을 그 요청에 맞춘다.
 */
class Navi(context: Context, private val main: Handler, private val sink: Sink) {

    interface Sink {
        fun onNaviReady()
        fun onNaviFailed(reason: String)
        fun onDestination(destination: Destination?, remainSeconds: Int?, remainMeters: Int?)
        fun onTurn(turn: Turn?)
        /**
         * 지금 들어 있는 경유지들(이름, 위도, 경도). 들를까가 넣던 곳이 들어갔는지 다시 보일 때 맞춰 보는 데도 쓴다.
         * 에뮬레이터 내비는 경유지 좌표를 0, 0으로 돌려주므로(2026-11-15) 이름으로도 맞춘다.
         */
        fun onWaypoints(points: List<Triple<String, Double, Double>>)
        fun onWaypointAdded()
        fun onWaypointRemoved()
        fun onWaypointError()

        /** 경유지에 닿음([name]은 내비가 알려 준 경유지 이름). */
        fun onWaypointArrived(name: String)

        /** 찜까지 도로 시간(초)과 도로 거리(m). 받지 못했으면 둘 다 null. */
        fun onTrip(placeId: String, seconds: Int?, meters: Int?)
        fun onFavorites(places: List<Place>)
        fun onCamera(nearestMeters: Int?)
    }

    private val helper = NaviHelper(context)
    private var ready = false
    /** start()와 stop() 사이인지. 연결이 끝나기 전에 가려지면 연결이 끝난 뒤 곧바로 놓는다. */
    private var wanted = false
    private var lastPollMs = 0L
    private var office: Pair<Double, Double>? = null
    private var home: Pair<Double, Double>? = null
    private var bookmarks: List<Place> = emptyList()
    private var recents: List<Place> = emptyList()
    private val tripQueue = ArrayDeque<Place>()
    private var tripInFlight: Pair<Place, Long>? = null
    private var waypointChanging = false
    private var waypoints: List<WaypointInfo> = emptyList()
    private var removeAfterRefresh: Place? = null

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(info: DestinationInfo) = post {
            // 길 안내가 없으면 이름 없이 남은 시간·거리 0으로 온다(에뮬레이터에서 확인)
            if (info.name.isBlank() && info.remainTime == 0 && info.remainDistance == 0) {
                sink.onDestination(null, null, null)
            } else {
                val isOffice = office?.let { (lat, lon) -> metersBetween(lat, lon, info.latitude, info.longitude) <= SAME_PLACE_METERS } ?: false
                sink.onDestination(Destination(info.latitude, info.longitude, isOffice), info.remainTime, info.remainDistance)
            }
        }

        override fun onTBTInfo(list: List<TBTInfo>) = post {
            val next = list.firstOrNull()
            sink.onTurn(next?.let { Turn(it.distance, Turns.isInterchange(it.type.name)) })
        }

        override fun onWayPointInfo(list: List<WaypointInfo>) = post {
            waypoints = list
            // 빼 달라는 곳이 있으면 새 목록에서 자리를 찾아 뺀 뒤에 알린다(목록에 없으면 뺄 것이 없음 → 받는 쪽이 목록으로 확인)
            removeAfterRefresh?.let { place ->
                removeAfterRefresh = null
                removeAt(place)
            }
            sink.onWaypoints(list.map { Triple(it.name, it.latitude, it.longitude) })
        }

        override fun onWaypointChanged(info: WaypointChangedInfo) = post {
            waypointChanging = false
            when (info.cmdType) {
                WaypointCmdType.ADD -> sink.onWaypointAdded()
                WaypointCmdType.REMOVE -> sink.onWaypointRemoved()
            }
            refresh()
        }

        override fun onTripInfo(info: TripInfo) = post {
            // 늦게 온 답(시간 초과 뒤)이 다음 곳의 답으로 붙을 수 있어, 받는 쪽이 도로 거리를 직선거리와 견줘 거른다
            val asked = tripInFlight ?: return@post
            tripInFlight = null
            sink.onTrip(asked.first.id, info.eta, info.distance)
            sendNextTrip()
        }

        override fun onWayPointArrived(info: WaypointArrivedInfo) = post { sink.onWaypointArrived(info.name) }

        override fun onBookmarkInfo(list: List<BookmarkInfo>) = post {
            office = list.firstOrNull { it.category == BookmarkType.OFFICE }?.let { it.latitude to it.longitude }
            home = list.firstOrNull { it.category == BookmarkType.HOME }?.let { it.latitude to it.longitude }
            // 집·회사·충전소는 가 볼 곳 후보에서 뺀다(기획서 3번)
            bookmarks = list.filter { it.category == BookmarkType.DEFAULT }.map { place(it.name, it.latitude, it.longitude) }
            publishFavorites()
        }

        override fun onRecentDestinationInfo(list: List<RecentDestinationInfo>) = post {
            recents = list.map { place(it.name, it.latitude, it.longitude) }
            publishFavorites()
        }

        override fun onBookMarkChanged() = post { helper.getBookmarkInfo() }

        override fun onCameraAlert(list: List<CameraAlert>) = post {
            sink.onCamera(list.filter { it.status }.minOfOrNull { it.remainDistance })
        }

        override fun onNavigationStatus(status: NavigationStatus) = post {
            if (status != NavigationStatus.ROUTE_DRIVE) sink.onTurn(null)
            refresh()
        }

        override fun onRouteCancelled() = post { refresh() }

        override fun onRouteEnded() = post { refresh() }

        override fun onError(code: NaviErrorCode) = post {
            // 오류에는 어느 요청의 것인지가 없다. 도로 시간 묻기 오류(CAUSE_SEARCH_TRIP)는 그 요청의 실패로만 보고,
            // 그 밖(경로 탐색·배터리)은 경유지를 바꾸는 중이면 그 실패로 본다. 경유지를 바꾸는 동안에는 도로 시간을 묻지 않는다
            val tripError = code == NaviErrorCode.CAUSE_SEARCH_TRIP || !waypointChanging
            if (tripError) {
                tripInFlight?.let { (place, _) ->
                    tripInFlight = null
                    sink.onTrip(place.id, null, null)
                    sendNextTrip()
                }
            } else {
                waypointChanging = false
                sink.onWaypointError()
            }
        }
    }

    fun start() {
        wanted = true
        helper.initialize(
            {
                post {
                    // 연결이 끝나기 전에 가려졌으면 붙지 않고 놓는다
                    if (!wanted) {
                        runCatching { helper.release() }
                        return@post
                    }
                    ready = true
                    helper.addListener(listener)
                    helper.getBookmarkInfo()
                    helper.getRecentDestinationInfo()
                    refresh()
                    sink.onNaviReady()
                }
            },
            { e -> post { sink.onNaviFailed(e::class.java.simpleName) } },
        )
    }

    fun stop() {
        wanted = false
        if (!ready) return
        ready = false
        runCatching { helper.removeListener(listener) }
        runCatching { helper.release() }
    }

    /**
     * 목적지·남은 시간·다음 회전·경유지를 [POLL_MS]마다 다시 묻는다. 내비가 이 값들을 스스로 보내 주는지 확인하지 못해
     * (에뮬레이터 내비는 위치를 따라오지 않음) 묻고 받는다. 받는 쪽은 받은 시각을 남겨 낡은 값을 쓰지 않는다.
     */
    fun poll(nowMs: Long) {
        if (!ready || nowMs - lastPollMs < POLL_MS) return
        lastPollMs = nowMs
        refresh()
    }

    /** 목적지·다음 회전·경유지 수를 다시 묻는다. 답은 콜백으로 온다. */
    fun refresh() {
        if (!ready) return
        helper.getDestinationInfo()
        helper.getTBTInfo()
        helper.getWaypointInfo()
    }

    fun refreshFavorites() {
        if (!ready) return
        helper.getBookmarkInfo()
        helper.getRecentDestinationInfo()
    }

    /** 찜까지 도로 시간을 물을 곳을 줄 세운다. 이미 줄에 있거나 묻는 중인 곳은 넣지 않는다. */
    fun requestTrips(places: List<Place>, nowMs: Long) {
        if (!ready || waypointChanging) return
        val inFlight = tripInFlight
        if (inFlight != null && nowMs - inFlight.second > TRIP_TIMEOUT_MS) {
            tripInFlight = null
            sink.onTrip(inFlight.first.id, null, null)
        }
        places.filter { p -> tripQueue.none { it.id == p.id } && tripInFlight?.first?.id != p.id }.forEach(tripQueue::addLast)
        if (tripInFlight == null) sendNextTrip(nowMs)
    }

    private fun sendNextTrip(nowMs: Long = android.os.SystemClock.elapsedRealtime()) {
        val next = tripQueue.removeFirstOrNull() ?: return
        tripInFlight = next to nowMs
        helper.requestTrip(TripRequest(next.lat, next.lon, "", next.name, "0"))
    }

    fun addWaypoint(place: Place) {
        if (!ready) return sink.onWaypointError()
        waypointChanging = true
        helper.addWaypoint(
            RequestWaypointInfo(
                latitude = place.lat,
                longitude = place.lon,
                poiName = place.name,
                waypointIndex = WaypointIndex.FIRST,
                poiId = "",
                address = "",
                poiSubId = "0",
            ),
        )
    }

    /**
     * 들를까가 넣은 [place]만 뺀다. 넣은 뒤 운전자가 내비에서 다른 경유지를 더했을 수 있어 늘 첫째라고 보지 않고,
     * 경유지 목록을 새로 받아 그곳의 자리(첫째·둘째)를 찾아 뺀다. 연결이 끊겨 있으면 다시 이어진 뒤 받은 목록으로 한다.
     */
    fun removeWaypoint(place: Place) {
        removeAfterRefresh = place
        if (ready) helper.getWaypointInfo()
    }

    private fun removeAt(place: Place) {
        val which = when (waypoints.indexOfFirst { matches(it.name, it.latitude, it.longitude, place) }) {
            -1 -> return
            0 -> WaypointIndex.FIRST
            1 -> WaypointIndex.SECOND
            // SDK는 둘째까지만 골라 뺄 수 있다
            else -> return sink.onWaypointError()
        }
        waypointChanging = true
        helper.removeWaypoint(which)
    }

    private fun publishFavorites() {
        // 최근 목적지에도 집·회사가 들어 있을 수 있어 그 근처(같은 곳으로 보는 거리)는 뺀다
        val excluded = listOfNotNull(home, office)
        val recentPlaces = recents.filter { p -> excluded.none { (lat, lon) -> metersBetween(lat, lon, p.lat, p.lon) <= SAME_PLACE_METERS } }
        sink.onFavorites((bookmarks + recentPlaces).distinctBy { it.id })
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    companion object {
        /** 같은 곳으로 보는 거리(m). 즐겨찾기와 최근 목적지에 같은 곳이 있으면 하나로 묶는다. */
        const val SAME_PLACE_METERS = 150.0
        const val TRIP_TIMEOUT_MS = 10_000L
        const val POLL_MS = 2_000L
        private const val METERS_PER_KM = 1000.0

        /** 좌표로 만든 찜 id. 즐겨찾기와 최근 목적지에 같은 곳이 있어도 id가 같아 하나로 묶인다(소수 넷째 자리, 약 11m). */
        fun idFor(lat: Double, lon: Double): String = String.format(Locale.ROOT, "p:%.4f,%.4f", lat, lon)

        fun place(name: String, lat: Double, lon: Double): Place = Place(idFor(lat, lon), name.trim(), lat, lon)

        fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
            DetourEstimator.distanceKm(lat1, lon1, lat2, lon2) * METERS_PER_KM

        /**
         * 내비의 경유지 하나가 [place]인지. 넣을 때 보낸 이름이 같거나 좌표가 가까우면 같은 곳으로 본다.
         * 에뮬레이터 내비는 경유지 좌표를 0, 0으로 돌려주고(2026-11-15), 실차 내비는 좌표를 도로·입구로 옮겨 줄 수 있어[미확인] 둘 중 하나로 맞춘다.
         */
        fun matches(name: String, lat: Double, lon: Double, place: Place): Boolean {
            val sameName = place.name.isNotBlank() && name.trim() == place.name.trim()
            val near = !(lat == 0.0 && lon == 0.0) && metersBetween(lat, lon, place.lat, place.lon) <= SAME_PLACE_METERS
            return sameName || near
        }
    }
}
