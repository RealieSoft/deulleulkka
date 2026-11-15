package app.deulleulkka

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import app.deulleulkka.core.AskFlow
import app.deulleulkka.core.Candidates
import app.deulleulkka.core.Card
import app.deulleulkka.core.Choice
import app.deulleulkka.core.ContextBuilder
import app.deulleulkka.core.FlowCommand
import app.deulleulkka.core.FlowEvent
import app.deulleulkka.core.FlowState
import app.deulleulkka.core.LocationGuard
import app.deulleulkka.core.Phase
import app.deulleulkka.core.SavedState
import app.deulleulkka.core.SavedStateCodec
import app.deulleulkka.core.Signals
import app.deulleulkka.core.SpeedEstimator
import app.deulleulkka.core.Turn
import app.deulleulkka.core.VisitTracker
import app.deulleulkka.engine.Decision
import app.deulleulkka.engine.Destination
import app.deulleulkka.engine.DriveContext
import app.deulleulkka.engine.MomentJudge
import app.deulleulkka.engine.Place
import app.deulleulkka.engine.Settings
import java.time.LocalDate
import kotlin.math.cos

/**
 * SDK 값을 모아 1초마다 흐름([AskFlow])에 넣고, 흐름이 시킨 일을 SDK·화면에서 한다. 모든 상태는 화면 스레드에서만 바꾼다.
 * 창이 보이는 동안만 돈다(onStart~onStop). 가려지면 묻지 않고 SDK 연결도 놓는다(Pleos 미사용 앱 가이드).
 */
class FlowRunner(context: Context, private val ui: Ui) : Navi.Sink, Location.Sink, CarSignals.Sink, Voice.Sink {

    interface Ui {
        fun showCard(card: Card)
        fun hideCard()
        fun render(view: View)
    }

    /** 주차 중 화면과 위쪽 한 줄에 쓰는 것. */
    data class View(
        val parked: Boolean,
        val places: List<Place>,
        val visited: List<Place>,
        /** "들를게"로 넣어 두어 아직 "취소"를 받는 곳. */
        val pending: Place?,
        val newFavorites: List<Place>,
        val decisions: List<Pair<Place, Decision>>,
        val onRoute: Int,
        val naviReady: Boolean,
        val locationKnown: Boolean,
        val simulated: Boolean,
    )

    private val main = Handler(Looper.getMainLooper())
    private val store = Store(context)
    private val contentResolver = context.contentResolver
    private val settings = Settings()
    private val judge = MomentJudge(settings)
    private val flow = AskFlow(settings, judge)
    private var saved: SavedState = store.load()
    private var lastSaved = SavedStateCodec.encode(saved)
    private var state = FlowState(history = saved.history, questionsAsked = saved.questionsAsked, pendingAccept = saved.pendingAccept)

    private var signals = Signals()
    private var carSpeed: Double? = null
    private var estimatedSpeed: Pair<Double, Long>? = null
    private val speedEstimator = SpeedEstimator()
    private val trips = mutableMapOf<String, Pair<Int, Long>>()
    private var favorites: List<Place> = emptyList()
    private val visits = VisitTracker()
    private var parkedSince: Long? = null
    private var naviReady = false
    private var visible = false
    private var locationAllowed = false

    private val navi = Navi(context, main, this)
    private val location = Location(context, main, this)
    private val car = CarSignals(context, main, this)
    private val voice = Voice(context, main, this)

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    fun start(hasLocationPermission: Boolean) {
        visible = true
        endTripIfCarWasOff()
        // 가려진 동안의 차량·위치·내비 값은 낡았다. 새로 들어올 때까지 모름으로 둔다(낡은 P와 P 시각으로 이동을 끝내지 않도록)
        signals = signals.copy(
            lat = null, lon = null, headingDeg = null, turnSignalOn = null, braking = null, parked = null,
            otherAudioPlaying = null, nextTurn = null, remainSeconds = null, remainMeters = null,
        )
        carSpeed = null
        estimatedSpeed = null
        freshFixAt = null
        parkedSince = null
        assistantPlaying = false
        // 다시 보이면 내비의 경유지 목록과 기어를 새로 받을 때까지(길어야 RESYNC_MS) 흐름을 멈춘다
        // (가려진 동안 넣은 경유지를 실패로 보고 빼지 않도록, 낡은 값으로 판단하지 않도록)
        resyncUntil = now() + RESYNC_MS
        waypointsSeen = false
        locationAllowed = hasLocationPermission
        navi.start()
        car.start()
        voice.start()
        if (locationAllowed) location.start()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    /** 창이 가려짐: 묻는 중이면 멈추고(흐름이 기록을 정함), SDK 연결을 놓는다. */
    fun stop() {
        visible = false
        tick()
        saveAlive()
        main.removeCallbacks(ticker)
        location.stop()
        voice.stop()
        car.stop()
        navi.stop()
        naviReady = false
    }

    /** 주차 중 버튼(들를게·다음에·취소). 흐름이 주차(P)가 아니면 받지 않는다. */
    fun press(choice: Choice) = apply(FlowEvent.Pressed(choice, now(), today()))

    fun addPlace(place: Place) = edit { it.copy(places = (it.places + place).distinctBy(Place::id), declined = it.declined - place.id) }

    fun declineFavorite(place: Place) = edit { it.copy(declined = it.declined + place.id) }

    fun removePlace(place: Place) = edit { it.copy(places = it.places.filterNot { p -> p.id == place.id }) }

    /** 다녀온 곳을 다시 가 볼 곳으로. */
    fun restoreVisited(place: Place) =
        edit { it.copy(places = (it.places + place).distinctBy(Place::id), visited = it.visited.filterNot { p -> p.id == place.id }) }

    fun refreshFavorites() = navi.refreshFavorites()

    // ---------- 1초마다 ----------

    private fun tick() {
        val now = now()
        if (visible) {
            navi.poll(now)
            if (now - lastAliveSave >= ALIVE_SAVE_MS) saveAlive()
        }
        if (visible && now < resyncUntil && !(waypointsSeen && signals.parked != null)) {
            render(signals.let(ContextBuilder::build), signals)
            return
        }
        resyncUntil = 0L
        val today = today()
        val est = estimatedSpeed?.takeIf { now - it.second <= SPEED_FRESH_MS }?.first
        // 내비 값은 받은 지 오래되면 모름으로 넘긴다(엔진이 보류한다)
        val base = signals.copy(
            speedKph = carSpeed ?: est,
            tripSecondsTo = freshTrips(now),
            nextTurn = signals.nextTurn.takeIf { now - turnAt <= NAVI_FRESH_MS },
            remainSeconds = signals.remainSeconds.takeIf { now - destAt <= NAVI_FRESH_MS },
            remainMeters = signals.remainMeters.takeIf { now - destAt <= NAVI_FRESH_MS },
        )
        // 달리는데 새 위치가 오래 오지 않으면(터널 등) 위치를 모름으로 본다(LocationGuard). 차량 속도로만 본다
        val located = if (LocationGuard.usable(freshFixAt?.let { now - it }, carSpeed)) base else base.copy(lat = null, lon = null, headingDeg = null)
        val lat = located.lat
        val lon = located.lon
        val candidates = if (lat != null && lon != null) Candidates.near(lat, lon, saved.places) else emptyList()
        if (lat != null && lon != null) {
            // 지켜보던 곳이 지켜보는 거리 밖으로 빠지면 반경을 벗어난 것으로 남긴다. 위치가 한 번에 크게 옮겨
            // 판단 반경 밖에서의 판단(Verdict.Skip)을 한 번도 거치지 못해도 "보류한 사이 지나친 곳"이 남게 한다.
            // 가까운 순으로 자른 목록에서 밀려난 것은 거리 안이라 빼지 않는다
            val ids = candidates.mapTo(HashSet(), Place::id)
            (watched - ids).filter { id -> beyondWatch(id, lat, lon) }.forEach { apply(FlowEvent.RadiusExit(it)) }
            watched = ids
        }
        val sig = SimOverrides.apply(located, candidates.map(Place::id))
        tripLifecycle(sig.parked, now, today)
        val ctx = ContextBuilder.build(sig)
        val voiceBusy = voice.speaking || assistantPlaying
        if (ctx != null) {
            val known = sig.tripSecondsTo.keys
            navi.requestTrips(Candidates.needsTrip(ctx.lat, ctx.lon, candidates, known), now)
            visits.update(ctx.lat, ctx.lon, sig.parked == true, now, saved.places)?.let(::markVisited)
            apply(FlowEvent.Tick(ctx, candidates, visible, sig.parked == true, now, today, voiceBusy, voice.ready))
        } else if (!visible) {
            // 위치를 모르는 채로 가려지면 묻는 중인 것만 멈춘다
            apply(FlowEvent.Tick(emptyContext(), emptyList(), false, sig.parked == true, now, today, voiceBusy, voice.ready))
        }
        // 상태가 바뀔 때만 남긴다. 좌표·장소 이름은 남기지 않는다
        val summary = "tick location=${ctx != null} candidates=${candidates.size} parked=${sig.parked} phase=${state.phase::class.java.simpleName}"
        if (summary != lastSummary) {
            Log.d(TAG, summary)
            lastSummary = summary
        }
        render(ctx, sig)
    }

    private var lastSummary = ""
    private var lastWaypointLog = ""
    private var lastFixMs = 0L
    private var freshFixAt: Long? = null
    private var resyncUntil = 0L
    private var waypointsSeen = false
    private var watched: Set<String> = emptySet()
    private var turnAt = 0L
    private var destAt = 0L
    private var assistantPlaying = false
    private var lastAliveSave = 0L

    /** 들를까가 넣은 곳이 내비의 경유지 목록에 실제로 보였는지(그곳 id). 보인 적이 있어야 목록에서 빠진 것을 "뺐다/닿았다"로 믿는다. */
    private var seenInRoute: String? = null

    /** 넣어 둔 곳이 경유지 목록에 없던 연속 횟수. 늦게 온 옛 목록 하나로 사라졌다고 보지 않도록 두 번 이어질 때 믿는다. */
    private var goneStreak = 0

    private fun beyondWatch(id: String, lat: Double, lon: Double): Boolean {
        val p = saved.places.firstOrNull { it.id == id } ?: return false
        return Navi.metersBetween(lat, lon, p.lat, p.lon) > Candidates.WATCH_RADIUS_METERS
    }

    /**
     * 시동을 끈 동안을 가려내 이동을 끝낸다. 앱은 시동 끄기를 알 수 없고 그동안 돌지 않으므로, 다시 보일 때
     * 부팅 횟수가 바뀌었거나(재부팅) 깊은 잠(부팅 뒤 경과 - 깊은 잠을 뺀 경과)이 [CAR_OFF_SLEEP_MS]를 넘었으면 이동이 끝난 것으로 본다.
     * 시동을 켠 채 다른 화면을 본 동안은 잠들지 않으므로 이동이 이어진다.
     */
    private fun endTripIfCarWasOff() {
        val last = store.loadAlive()
        val now = aliveNow()
        store.saveAlive(now)
        lastAliveSave = now()
        if (!saved.inTrip || last == null) return
        val rebooted = (now.bootCount >= 0 && last.bootCount >= 0 && now.bootCount != last.bootCount) || now.elapsedMs < last.elapsedMs
        val sleptMs = (now.elapsedMs - last.elapsedMs) - (now.uptimeMs - last.uptimeMs)
        if (rebooted || sleptMs >= CAR_OFF_SLEEP_MS) {
            Log.i(TAG, "car was off: rebooted=$rebooted slept=${sleptMs / 1000}s → trip ended")
            saved = saved.copy(inTrip = false)
            apply(FlowEvent.TripEnded(today()))
        }
    }

    private fun aliveNow(): Store.Alive = Store.Alive(
        bootCount = runCatching { android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1),
        elapsedMs = SystemClock.elapsedRealtime(),
        uptimeMs = SystemClock.uptimeMillis(),
    )

    private fun saveAlive() {
        store.saveAlive(aliveNow())
        lastAliveSave = now()
    }

    /** 시동을 켠 뒤 처음 P를 벗어나면 새 이동. 앱은 시동 끄기를 알 수 없어, P로 [TRIP_END_PARKED_MS] 넘게 서 있으면 이동이 끝난 것으로 본다. */
    private fun tripLifecycle(parked: Boolean?, now: Long, today: Long) {
        when (parked) {
            false -> {
                parkedSince = null
                if (!saved.inTrip) {
                    saved = saved.copy(inTrip = true)
                    apply(FlowEvent.TripStarted(today))
                }
            }
            true -> {
                val since = parkedSince ?: now.also { parkedSince = it }
                if (saved.inTrip && now - since >= TRIP_END_PARKED_MS) {
                    saved = saved.copy(inTrip = false)
                    apply(FlowEvent.TripEnded(today))
                }
            }
            null -> Unit
        }
    }

    private fun apply(event: FlowEvent) {
        val step = flow.reduce(state, event)
        state = step.state
        step.commands.forEach(::execute)
        persist()
    }

    private fun execute(c: FlowCommand) {
        when (c) {
            is FlowCommand.Speak -> voice.speak(c.id, c.text)
            FlowCommand.StopSpeaking -> voice.stopSpeaking()
            is FlowCommand.Listen -> voice.listen(c.untilMs)
            FlowCommand.StopListening -> voice.stopListening()
            is FlowCommand.ShowCard -> ui.showCard(c.card)
            FlowCommand.HideCard -> ui.hideCard()
            FlowCommand.Beep -> voice.beep()
            is FlowCommand.AddWaypoint -> {
                seenInRoute = null
                goneStreak = 0
                navi.addWaypoint(c.place)
            }
            is FlowCommand.RemoveWaypoint -> navi.removeWaypoint(c.place)
        }
        // 기록에는 좌표·장소 이름을 남기지 않는다(README "로그에 위치·장소 이름이 남음"을 출시 앱에서는 막음)
        Log.i(TAG, c::class.java.simpleName)
    }

    private fun persist() {
        saved = saved.copy(history = state.history, questionsAsked = state.questionsAsked, pendingAccept = state.pendingAccept)
        val encoded = SavedStateCodec.encode(saved)
        if (encoded != lastSaved) {
            store.save(saved)
            lastSaved = encoded
        }
    }

    private fun edit(change: (SavedState) -> SavedState) {
        saved = change(saved)
        persist()
        render(signals.let(ContextBuilder::build), signals)
    }

    private fun markVisited(place: Place) {
        saved = saved.copy(places = saved.places.filterNot { it.id == place.id }, visited = (saved.visited + place).distinctBy(Place::id))
        persist()
    }

    private fun render(ctx: DriveContext?, sig: Signals) {
        val taken = (saved.places + saved.visited).map(Place::id).toSet() + saved.declined
        ui.render(
            View(
                parked = sig.parked == true,
                places = saved.places,
                visited = saved.visited,
                pending = state.pendingAccept,
                newFavorites = favorites.filter { it.id !in taken },
                decisions = saved.places.mapNotNull { p -> state.decisions[p.id]?.let { p to it } },
                onRoute = ctx?.let { onRouteCount(it) } ?: 0,
                naviReady = naviReady,
                locationKnown = ctx != null,
                simulated = SimOverrides.active,
            ),
        )
    }

    /** "이번 이동에서 물을 수 있는 찜 N곳": 지금 자리와 목적지를 잇는 선에서 2km 띠 안이고 진행 방향 앞쪽인 찜(기획서 3번, 어림). */
    private fun onRouteCount(ctx: DriveContext): Int {
        val dest = ctx.destination ?: return 0
        return saved.places.count { p -> !judge.isBehind(ctx, p) && distanceToSegmentKm(ctx.lat, ctx.lon, dest, p) <= ROUTE_BAND_KM }
    }

    private fun distanceToSegmentKm(lat: Double, lon: Double, dest: Destination, p: Place): Double {
        val kx = KM_PER_DEG * cos(Math.toRadians(lat))
        val ax = 0.0
        val ay = 0.0
        val bx = (dest.lon - lon) * kx
        val by = (dest.lat - lat) * KM_PER_DEG
        val px = (p.lon - lon) * kx
        val py = (p.lat - lat) * KM_PER_DEG
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * bx + (py - ay) * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun freshTrips(now: Long): Map<String, Int> =
        trips.filterValues { (_, at) -> now - at <= TRIP_FRESH_MS }.mapValues { (_, v) -> v.first }

    private fun emptyContext() = DriveContext(
        lat = 0.0, lon = 0.0, speedKph = null, destination = null, nextTurnMeters = null, nextTurnIsInterchange = null,
        turnSignalOn = null, braking = null, cautionAhead = null, otherAudioPlaying = null, waypointCount = null,
    )

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun today(): Long = LocalDate.now().toEpochDay()

    // ---------- SDK 콜백(화면 스레드) ----------

    override fun onNaviReady() {
        naviReady = true
    }

    override fun onNaviFailed(reason: String) {
        naviReady = false
        Log.w(TAG, "navi 연결 실패 $reason")
    }

    override fun onDestination(destination: Destination?, remainSeconds: Int?, remainMeters: Int?) {
        if (destination != signals.destination) trips.clear()
        signals = signals.copy(destination = destination, remainSeconds = remainSeconds, remainMeters = remainMeters)
        destAt = now()
    }

    override fun onTurn(turn: Turn?) {
        signals = signals.copy(nextTurn = turn)
        turnAt = now()
    }

    override fun onWaypoints(points: List<Triple<String, Double, Double>>) {
        signals = signals.copy(waypointCount = points.size)
        waypointsSeen = true
        // 넣고 빼는 결과는 경유지 목록으로 맞춘다. 넣는 중에 창이 가려지면(경로가 바뀌면 내비가 앞으로 나옴) 알림을 놓치고,
        // 뺐다는 알림(onWaypointChanged)은 운전자가 내비에서 다른 경유지를 뺀 것과 가리지 못하기 때문이다
        val ph = state.phase
        val target = when (ph) {
            is Phase.Adding -> ph.place
            is Phase.Removing -> ph.place
            else -> state.pendingAccept
        } ?: return
        val found = points.any { (name, lat, lon) -> Navi.matches(name, lat, lon, target) }
        if (found) {
            seenInRoute = target.id
            goneStreak = 0
        } else {
            goneStreak++
        }
        // 2초마다 오는 목록이라 바뀔 때만 남긴다(개수와 찾음 여부뿐, 좌표·이름은 남기지 않음)
        val line = "waypoints n=${points.size} targetFound=$found"
        if (line != lastWaypointLog) {
            Log.d(TAG, line)
            lastWaypointLog = line
        }
        val waitingAdd = ph is Phase.Adding || (ph is Phase.Removing && ph.waitingAdd)
        when {
            waitingAdd -> if (found) apply(FlowEvent.WaypointAdded(target.id, now()))
            // 빼는 중: 목록에서 보였던 곳이 빠졌으면 뺀 것. 한 번도 보이지 않았으면 맞춰 보지 못한 것이라 뺐다고 하지 않는다
            ph is Phase.Removing -> if (!found) {
                apply(if (seenInRoute == target.id) FlowEvent.WaypointRemoved(target.id) else FlowEvent.WaypointFailed(target.id, now(), today()))
            }
            // 넣어 둔 곳이 경로에서 사라짐(닿았거나 운전자가 내비에서 뺌): 더는 "취소"를 받지 않는다
            seenInRoute == target.id && goneStreak >= GONE_STREAK -> apply(FlowEvent.WaypointGone(target.id, today()))
        }
    }

    override fun onWaypointArrived(name: String) {
        // 찜에 닿으면 다녀온 곳으로 바꾼다(시동을 곧 끄면 머문 시간을 셀 수 없어서). 들를까가 넣은 곳이면 "취소" 받기도 끝낸다
        saved.places.firstOrNull { it.name.isNotBlank() && it.name.trim() == name.trim() }?.let(::markVisited)
        state.pendingAccept?.takeIf { it.name.trim() == name.trim() }?.let { apply(FlowEvent.WaypointGone(it.id, today())) }
    }

    override fun onWaypointAdded() {
        when (val ph = state.phase) {
            is Phase.Adding -> apply(FlowEvent.WaypointAdded(ph.place.id, now()))
            is Phase.Removing -> if (ph.waitingAdd) apply(FlowEvent.WaypointAdded(ph.place.id, now()))
            else -> Unit
        }
    }

    /** 뺐다는 알림만으로는 들를까가 넣은 곳인지 알 수 없어, 뒤이어 오는 경유지 목록([onWaypoints])으로 확인한다. */
    override fun onWaypointRemoved() = Unit

    override fun onWaypointError() {
        when (val ph = state.phase) {
            is Phase.Adding -> apply(FlowEvent.WaypointFailed(ph.place.id, now(), today()))
            is Phase.Removing -> apply(FlowEvent.WaypointFailed(ph.place.id, now(), today()))
            else -> Unit
        }
    }

    override fun onTrip(placeId: String, seconds: Int?, meters: Int?) {
        if (seconds != null && tripPlausible(placeId, meters)) trips[placeId] = seconds to now() else trips.remove(placeId)
    }

    /**
     * 받은 도로 거리가 그 찜까지의 직선거리와 맞는지. 답에는 어느 곳의 것인지가 없어 늦게 온 다른 곳의 답이 붙을 수 있다.
     * 도로 거리는 직선거리보다 짧을 수 없고(위치 오차만큼 봐줌), 직선의 몇 배를 넘기 어렵다.
     */
    private fun tripPlausible(placeId: String, meters: Int?): Boolean {
        val p = saved.places.firstOrNull { it.id == placeId } ?: return true
        val lat = signals.lat ?: return true
        val lon = signals.lon ?: return true
        meters ?: return true
        val straight = Navi.metersBetween(lat, lon, p.lat, p.lon)
        return meters >= straight * TRIP_MIN_FACTOR - TRIP_SLACK_M && meters <= straight * TRIP_MAX_FACTOR + TRIP_SLACK_M
    }

    override fun onFavorites(places: List<Place>) {
        favorites = places
    }

    override fun onCamera(nearestMeters: Int?) {
        signals = signals.copy(cameraMeters = nearestMeters, cameraChannelSeen = true)
    }

    override fun onLocation(lat: Double, lon: Double, headingDeg: Double?, atMs: Long, fixMs: Long) {
        // 새 측위만 "새 위치"로 센다(감시 루틴이 다시 넣은 마지막 위치는 측위 시각이 같다). 속도 어림도 측위 시각으로 잰다
        if (fixMs > lastFixMs) {
            lastFixMs = fixMs
            freshFixAt = atMs
            speedEstimator.update(lat, lon, fixMs)?.let { estimatedSpeed = it to atMs }
        }
        signals = signals.copy(lat = lat, lon = lon, headingDeg = headingDeg)
    }

    override fun onTurnSignal(on: Boolean?) {
        signals = signals.copy(turnSignalOn = on)
    }

    override fun onBraking(pressed: Boolean?) {
        signals = signals.copy(braking = pressed)
    }

    override fun onParked(parked: Boolean?) {
        signals = signals.copy(parked = parked)
    }

    override fun onEnergy(percent: Int?) {
        signals = signals.copy(energyPercent = percent)
    }

    override fun onSpeed(kph: Double?) {
        carSpeed = kph
    }

    override fun onOtherAudio(playing: Boolean) {
        signals = signals.copy(otherAudioPlaying = playing)
    }

    override fun onAssistantAudio(playing: Boolean) {
        assistantPlaying = playing
    }

    override fun onSpeakingChanged(speaking: Boolean) {
        car.reportAudio()
    }

    override fun onSpeechDone(id: Long) = apply(FlowEvent.SpeechDone(id, now()))

    override fun onHeard(text: String) = apply(FlowEvent.Heard(text, now(), today()))

    /** 시험용(debug): 인식기 대신 들은 말을 넣는다. */
    fun simulateHeard(text: String) = onHeard(text)

    /** 시험용(debug): 녹음한 대답을 인식기에 넣는다. */
    fun simulateAudio(pcm: ByteArray) = voice.feedPcm(pcm)

    /** 시험용(debug): 묻고 답한 기록만 비운다(찜은 그대로). */
    fun simulateResetHistory() {
        state = FlowState()
        saved = saved.copy(history = state.history, questionsAsked = 0, pendingAccept = null)
        persist()
    }

    /** 시험용(debug): 다녀온 곳을 가 볼 곳으로 되돌린다. */
    fun simulateRestoreVisited() = saved.visited.forEach(::restoreVisited)

    /** 시험용(debug): 가 볼 곳 첫째를 다녀온 곳으로(머문 시간 10분을 기다리지 않고). */
    fun simulateVisited() {
        saved.places.firstOrNull()?.let(::markVisited)
        render(signals.let(ContextBuilder::build), signals)
    }

    /** 시험용(debug): 이동을 끝내고 새로 시작한다(시동을 껐다 켠 것처럼). */
    fun simulateNewTrip() {
        val today = today()
        saved = saved.copy(inTrip = false)
        apply(FlowEvent.TripEnded(today))
        saved = saved.copy(inTrip = true)
        apply(FlowEvent.TripStarted(today))
    }

    companion object {
        const val TAG = "Deulleulkka"
        const val TICK_MS = 1_000L
        const val SPEED_FRESH_MS = 5_000L
        const val TRIP_FRESH_MS = 60_000L
        const val TRIP_END_PARKED_MS = 10 * 60_000L
        const val ROUTE_BAND_KM = 2.0
        const val RESYNC_MS = 8_000L

        /** 내비 값(다음 회전·남은 시간)을 믿는 시간. 2초마다 다시 묻는다(Navi.POLL_MS). */
        const val NAVI_FRESH_MS = 5_000L

        /** 이만큼 넘게 깊이 잠들었으면(시동을 끈 것) 이동이 끝난 것으로 본다. */
        const val CAR_OFF_SLEEP_MS = 60_000L
        const val ALIVE_SAVE_MS = 10_000L
        const val GONE_STREAK = 2
        const val TRIP_MIN_FACTOR = 0.9
        const val TRIP_MAX_FACTOR = 4.0
        const val TRIP_SLACK_M = 500.0
        private const val KM_PER_DEG = 111.195

        /** debug 받개가 지금 떠 있는 흐름을 찾는 길. 출시 앱에서는 쓰는 곳이 없다. */
        @Volatile var current: FlowRunner? = null
    }
}
