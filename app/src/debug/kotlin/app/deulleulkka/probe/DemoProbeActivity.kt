package app.deulleulkka.probe

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * 확인 영상용 화면(시연 앱 아님). E2~E7에서 확인한 SDK 호출을 한 흐름으로 이어 돌아온 값을 화면에 크게 적는다.
 *
 * ① 내비와 옆 칸에 나눠 띄움(칸 나누기는 녹화 스크립트가 함)
 * ② 목적지 설정 → 경유지 넣기 → 다시 계산된 경로 값(넣은 뒤 걸린 시간 포함)
 * ③ TTS(ON_DEVICE)로 한국어 질문을 말함(화면 녹화에는 소리가 담기지 않으므로 콜백을 적음)
 * ④ PC 합성음 대답 두 개(assets의 probe 폴더 yes·a1_deulleulge)를 STT(ON_DEVICE) sendAudio로 넣고 인식 글자를 그대로 적음
 * ⑤ PC에서 Virtual Console(8088 포트)로 방향지시등을 바꾸면 Vehicle SDK 콜백으로 오는 값을 적음
 *
 * 칸 나누기(크기·방향 변경) 때 다시 만들어지지 않도록 매니페스트에서 configChanges를 받는다.
 * 시간표는 PC 쪽 녹화 스크립트(저장소에 넣지 않음)의 방향지시등 변경 시점과 맞춰 둔다.
 */
class DemoProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val t0 = SystemClock.elapsedRealtime()
    private lateinit var title: TextView
    private lateinit var signal: TextView
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView

    private lateinit var navi: NaviHelper
    private lateinit var vehicle: Vehicle
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null

    private var waypointAt = 0L
    private var speakAt = 0L
    private var lastSignal: TurnSignalValue? = null

    private val destination = RouteInfo(
        latitude = 37.48544722,
        longitude = 127.03636666,
        poiName = "42dot",
        poiId = "36khp37hh8gqcm5nqhe5",
        poiSubId = "0",
        address = "서울시 강남구 남부순환로 2621",
    )
    private val waypoint = RequestWaypointInfo(
        latitude = 37.41275833,
        longitude = 127.09481944,
        poiName = "소프트웨어드림센터",
        waypointIndex = WaypointIndex.FIRST,
        poiId = "36khp2hdj2cfcm5nqhe3",
        address = "경기도 성남시 수정구",
        poiSubId = "0",
    )

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) = log("내비 경로 시작: ${info.duration}초 · ${info.distance}m", true)
        override fun onRouteCancelled() = log("내비 알림: 이전 경로 내려놓음(onRouteCancelled)")
        override fun onDestinationInfo(info: DestinationInfo) = log("목적지까지 남은 값: ${info.remainTime}초 · ${info.remainDistance}m", true)
        override fun onWaypointChanged(info: WaypointChangedInfo) {
            val sec = (SystemClock.elapsedRealtime() - waypointAt) / 100 / 10.0
            log("다시 계산된 경로(${info.cmdType}): ${info.routeStartInfo?.duration}초 · ${info.routeStartInfo?.distance}m — 넣은 지 ${sec}초 뒤", true)
        }
        override fun onError(code: NaviErrorCode) = log("내비 오류: $code")
    }

    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            runOnUiThread { signal.text = "방향지시등(Vehicle SDK 콜백): $value · ${signalName(value)}" }
            // 구독 직후 현재 값이 한두 번 오므로, 값이 바뀐 때만 기록에 남긴다
            if (value != lastSignal) {
                log(if (lastSignal == null) "방향지시등 구독 시작: 지금 $value" else "방향지시등 콜백: $value(${signalName(value)})", lastSignal != null)
                lastSignal = value
            }
        }
        override fun onFailed(e: Exception) = log("방향지시등 콜백 실패: ${e::class.java.simpleName}")
    }

    private val ttsListener = object : EventListener {
        override fun onReady() {}
        override fun onStart() = log("TTS 말하기 시작(호출 뒤 ${SystemClock.elapsedRealtime() - speakAt}ms)", true)
        override fun onDone() = log("TTS 말하기 끝(${(SystemClock.elapsedRealtime() - speakAt) / 100 / 10.0}초)", true)
        override fun onStop() {}
        override fun onError(errorType: TtsErrorType) = log("TTS 오류: $errorType")
        override fun onUpdatedRms(rms: Double) {}
    }

    private val sttListener = object : ResultListener {
        override fun onReady() {}
        override fun onStartedRecognition() {}
        override fun onEndedRecognition() {}
        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed) log("인식 결과(completed=true): \"$stt\"", true)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onError(errorType: SttErrorType) = log("STT 오류: $errorType")
        override fun onUpdatedRms(rms: Double) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 17, 17))
            setPadding(36, 28, 36, 28)
        }
        val badge = TextView(this).apply {
            textSize = 24f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(17, 17, 17)); setBackgroundColor(Color.rgb(245, 180, 0))
            setPadding(20, 10, 20, 10)
            text = "확인용 앱(시연 앱 아님)"
        }
        title = TextView(this).apply {
            textSize = 30f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 16, 0, 16)
            text = "들를까 · 에뮬레이터에서 SDK 확인"
        }
        signal = TextView(this).apply {
            textSize = 22f; setTextColor(Color.WHITE); setPadding(0, 12, 0, 8)
            text = "방향지시등(Vehicle SDK 콜백): 아직 없음"
        }
        out = TextView(this).apply { textSize = 22f; setTextColor(Color.rgb(230, 230, 230)); setLineSpacing(6f, 1f) }
        scroll = ScrollView(this).apply { addView(out) }
        // 방향지시등을 켜면 시스템 사각지대 카메라 창이 이 칸 위쪽을 덮는다(demo3 1차 녹화). 표시와 방향지시등 상태를 아래에도 둔다
        val footer = TextView(this).apply {
            textSize = 20f; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(17, 17, 17)); setBackgroundColor(Color.rgb(245, 180, 0))
            setPadding(20, 8, 20, 8)
            text = "확인용 앱(시연 앱 아님)"
        }
        root.addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(title)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(signal)
        root.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        log("Pleos Connect Emulator에서 실제 SDK를 호출하고 돌아온 값을 그대로 적습니다.")
        connect()
        // intent extra mode=signal: 방향지시등 장면만(칸을 바꿔 카메라 창 위치를 볼 때). 내비 경로·음성 장면은 돌리지 않는다
        if (intent.getStringExtra("mode") == "signal") {
            scene(5_000, "⑤ 방향지시등(Vehicle SDK)") { log("PC의 Virtual Console(8088 포트)로 방향지시등을 바꿉니다") }
            return
        }
        scene(8_000, "① 내비 옆 칸에 나눠 띄움") { log("왼쪽: 확인용 앱 / 오른쪽: 내비게이션") }
        scene(11_000, "② 경유지 넣기") { navi.requestRoute(destination); log("목적지 설정: 42dot") }
        step(20_000) { navi.getDestinationInfo() }
        step(23_000) {
            waypointAt = SystemClock.elapsedRealtime()
            navi.addWaypoint(waypoint)
            log("경유지 넣기: 소프트웨어드림센터")
        }
        step(34_000) { navi.getDestinationInfo() }
        scene(37_000, "③ 한국어 질문 말하기(TTS)") {
            log("말할 문장: “찜해 둔 카페가 가는 길 근처에 있어요. 들를까요?”")
            log("(화면 녹화에는 소리가 담기지 않아 콜백을 적습니다)")
            tts?.setLocale(Locale.KOREA)
            tts?.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            speakAt = SystemClock.elapsedRealtime()
            tts?.speak("찜해 둔 카페가 가는 길 근처에 있어요. 들를까요?", true)
        }
        scene(46_000, "④ 대답 인식(STT sendAudio)") {
            log("PC 합성음 “응, 들러 줘”를 인식기에 넣습니다")
            stt?.setLocale(Locale.KOREA)
            sendPcm("probe/yes.pcm")
        }
        step(54_000) {
            log("PC 합성음 “들를게”를 인식기에 넣습니다")
            sendPcm("probe/a1_deulleulge.pcm")
        }
        scene(63_000, "⑤ 방향지시등(Vehicle SDK)") { log("PC의 Virtual Console(8088 포트)로 방향지시등을 바꿉니다") }
        scene(79_000, "정리") {
            navi.removeWaypoint(WaypointIndex.ALL)
            log("경유지 빼기")
        }
        step(82_000) { navi.cancelRoute(); log("경로 취소 · 끝") }
    }

    private fun connect() {
        navi = NaviHelper(applicationContext)
        navi.initialize({ navi.addListener(naviListener); log("내비 연결됨") }, { e -> log("내비 연결 실패: ${e::class.java.simpleName}") })

        vehicle = Vehicle(applicationContext)
        runCatching { vehicle.initialize() }.onFailure { log("차량 SDK 초기화 실패: ${it::class.java.simpleName}") }
        step(3_000) { vehicle.getTurnSignal().registerTurnSignalState(turnListener) }

        tts = TextToSpeech(applicationContext, TtsMode.ON_DEVICE).also { t ->
            t.initialize(object : TtsInitListener {
                override fun onSuccess() {}
                override fun onFailure(errorType: TtsErrorType) = log("TTS 초기화 실패: $errorType")
            })
            t.addEventListener(ttsListener)
        }
        stt = SpeechToText(applicationContext, SttMode.ON_DEVICE).also { s ->
            s.addListener(sttListener)
            s.initialize(object : SttInitListener {
                override fun onSuccess() {}
                override fun onFailure(errorType: SttErrorType) = log("STT 초기화 실패: $errorType")
            })
        }
    }

    private fun signalName(v: TurnSignalValue?) = when (v?.name) {
        "OFF" -> "꺼짐"
        "LEFT" -> "왼쪽 깜빡임"
        "RIGHT" -> "오른쪽 깜빡임"
        else -> "기타"
    }

    // 16kHz·16비트·모노 PCM을 100ms(3,200바이트)씩 실시간 속도로 보낸다
    private fun sendPcm(asset: String) {
        val chunks = assets.open(asset).use { it.readBytes() }.toList().chunked(CHUNK_BYTES).map { it.toByteArray() }
        chunks.forEachIndexed { i, c -> handler.postDelayed({ runCatching { stt?.sendAudio(c) } }, i * CHUNK_MS) }
        handler.postDelayed({ runCatching { stt?.completeAudioSend() } }, chunks.size * CHUNK_MS)
    }

    private fun scene(delayMs: Long, name: String, action: () -> Unit) = step(delayMs) {
        title.text = name
        log("── $name")
        action()
    }

    private fun step(delayMs: Long, action: () -> Unit) {
        handler.postDelayed({ runCatching(action).onFailure { log("예외: ${it::class.java.simpleName}") } }, delayMs)
    }

    private fun log(line: String, result: Boolean = false) {
        val sec = (SystemClock.elapsedRealtime() - t0) / 1000
        val text = "[${sec}초] " + (if (result) "▶ " else "") + line
        Log.i(TAG, text)
        runOnUiThread {
            val s = SpannableString(text + "\n")
            if (result) s.setSpan(ForegroundColorSpan(Color.rgb(120, 230, 120)), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(s)
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { navi.removeListener(naviListener); navi.release() }
        runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener); vehicle.release() }
        runCatching { tts?.removeEventListener(ttsListener); tts?.release() }
        runCatching { stt?.removeListener(sttListener); stt?.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DeulleulkkaProbe"
        private const val CHUNK_BYTES = 3_200
        private const val CHUNK_MS = 100L
    }
}
