package app.deulleulkka

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
 * E5(음성 왕복)와 E6(오디오 용도) 확인.
 *
 * 1. 오디오: 시작할 때 AudioManager.getMode()·getActivePlaybackConfigurations()를 찍고,
 *    registerAudioPlaybackCallback으로 재생 목록이 바뀔 때마다 각 항목의 usage를 남긴다.
 * 2. TTS(ON_DEVICE): 한국어 문장 하나를 기본 오디오 속성으로 말하고, 이어서 USAGE_ASSISTANT로 바꿔 한 번 더 말한다.
 *    콜백(준비·시작·끝·중지·오류)과 speak()부터 시작까지 걸린 시간을 남긴다.
 * 3. STT(ON_DEVICE): request()로 마이크 인식을 한 번 열고 닫는다. 이어서 앱에 넣어 둔 16kHz·16비트·모노 PCM
 *    (assets의 probe 폴더에 둔 pcm 파일, PC의 한국어 합성 음성)을 sendAudio()로 3,200바이트씩 100ms 간격으로 보낸다.
 * 4. 내비: 경로를 설정해 내비가 음성을 낼 때 재생 목록에 무엇이 보이는지 본다.
 *
 * 5. 대답(answers): 수락·거절 대답 pcm 다섯 개를 12초 간격으로 두 번씩 보내고, 문장마다 completed=true 조각 수와
 *    completeAudioSend부터 결과까지 걸린 ms를 요약한다.
 *
 * intent extra "phase": all(기본) | tts | stt | navi | watch | answers("names"=쉼표로 나눈 pcm 이름). 결과는 화면과 logcat(태그 DeulleulkkaProbe)에 남긴다.
 */
class VoiceProbeActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val t0 = SystemClock.elapsedRealtime()
    private lateinit var out: TextView
    private lateinit var audio: AudioManager
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var navi: NaviHelper? = null

    private var speakAt = 0L
    private var ttsRmsCount = 0
    private var sttRmsCount = 0

    // pcm 한 개를 보내는 동안의 기록: 첫 청크·completeAudioSend 시각과 받은 결과
    private class Trial(val name: String) {
        var firstChunkAt = 0L
        var sentAllAt = 0L
        var partials = 0
        val pieces = mutableListOf<Pair<Long, String>>()
    }
    private var trial: Trial? = null

    // 경로를 걸어 내비 음성을 유도할 목적지(문서 예제 좌표)
    private val destination = RouteInfo(
        latitude = 37.48544722,
        longitude = 127.03636666,
        poiName = "42dot",
        poiId = "36khp37hh8gqcm5nqhe5",
        poiSubId = "0",
        address = "서울시 강남구 남부순환로 2621",
    )

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) =
            log("audio.callback mode=${modeName(audio.mode)} ${describe(configs)}")
    }

    private val ttsListener = object : EventListener {
        override fun onReady() = log("tts.onReady")
        override fun onStart() = log("tts.onStart (speak 후 ${SystemClock.elapsedRealtime() - speakAt}ms) 재생목록=${describe(audio.activePlaybackConfigurations)}")
        override fun onDone() = log("tts.onDone (speak 후 ${SystemClock.elapsedRealtime() - speakAt}ms) rms ${ttsRmsCount}회")
        override fun onStop() = log("tts.onStop")
        override fun onError(errorType: TtsErrorType) = log("tts.onError $errorType")
        override fun onUpdatedRms(rms: Double) {
            ttsRmsCount++
            if (ttsRmsCount == 1 || ttsRmsCount % 25 == 0) log("tts.onUpdatedRms #$ttsRmsCount rms=$rms")
        }
    }

    private val sttListener = object : ResultListener {
        override fun onReady() = log("stt.onReady")
        override fun onStartedRecognition() = log("stt.onStartedRecognition")
        override fun onEndedRecognition() = log("stt.onEndedRecognition")
        override fun onUpdated(stt: String, completed: Boolean) {
            val now = SystemClock.elapsedRealtime()
            val tag = trial?.let { t ->
                if (completed) t.pieces += now to stt else t.partials++
                val fromEnd = if (t.sentAllAt > 0) "${now - t.sentAllAt}ms" else "아직 다 안 보냄"
                " [${t.name} 첫 청크부터 ${now - t.firstChunkAt}ms, 다 보낸 때부터 $fromEnd]"
            } ?: ""
            log("stt.onUpdated completed=$completed text=\"$stt\"$tag")
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = log("stt.onUpdatedEpdData on=$on off=$off")
        override fun onError(errorType: SttErrorType) = log("stt.onError $errorType")
        override fun onUpdatedRms(rms: Double) {
            sttRmsCount++
            if (sttRmsCount == 1 || sttRmsCount % 25 == 0) log("stt.onUpdatedRms #$sttRmsCount rms=$rms")
        }
    }

    private val naviListener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) =
            log("navi.onRouteStarted duration=${info.duration} distance=${info.distance} 재생목록=${describe(audio.activePlaybackConfigurations)}")
        override fun onRouteCancelled() = log("navi.onRouteCancelled")
        override fun onRoutingCompleted() = log("navi.onRoutingCompleted")
        override fun onTBTInfo(list: List<TBTInfo>) = log("navi.onTBTInfo ${list.size}건 ${list.take(2)}")
        override fun onNavigationStatus(status: NavigationStatus) = log("navi.onNavigationStatus $status")
        override fun onError(code: NaviErrorCode) = log("navi.onError $code")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { textSize = 18f; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(out) })
        audio = getSystemService(AudioManager::class.java)

        val phase = intent.getStringExtra("phase") ?: "all"
        log("── 시작 phase=$phase")
        step(0, "오디오 기준값(아무 소리 없음)") {
            log("audio.mode=${modeName(audio.mode)} isMusicActive=${audio.isMusicActive}")
            log("audio.active ${describe(audio.activePlaybackConfigurations)}")
            audio.registerAudioPlaybackCallback(playbackCallback, handler)
        }
        when (phase) {
            "tts" -> ttsSteps(1_000)
            "stt" -> sttSteps(1_000)
            "navi" -> naviSteps(1_000)
            "answers" -> answerSteps(1_000, intent.getStringExtra("names")?.split(',') ?: ANSWERS)
            // 통화 흉내(adb emu gsm call) 등 바깥 조작 동안 2초마다 모드와 재생 목록만 찍는다(60초)
            "watch" -> for (t in 1..30) step(t * 2_000L, "감시 #$t") {
                log("audio.mode=${modeName(audio.mode)} active ${describe(audio.activePlaybackConfigurations)}")
            }
            else -> {
                ttsSteps(1_000)
                sttSteps(30_000)
                naviSteps(80_000)
            }
        }
    }

    private fun ttsSteps(start: Long) {
        step(start, "TTS 만들기(ON_DEVICE)·초기화") {
            val t = TextToSpeech(applicationContext, TtsMode.ON_DEVICE)
            tts = t
            // 문서 순서: initialize() 다음에 addEventListener() (먼저 부르면 SDK 안에서 lateinit 예외)
            t.initialize(object : TtsInitListener {
                override fun onSuccess() = log("tts.initialize onSuccess")
                override fun onFailure(errorType: TtsErrorType) = log("tts.initialize onFailure $errorType")
            })
            t.addEventListener(ttsListener)
        }
        step(start + 3_000, "TTS 말하기 1(기본 오디오 속성)") {
            // 언어만 있는 Locale.KOREAN("ko")은 서비스가 "unsupported locale: ko-"로 거절한다. 문서대로 Locale.KOREA
            tts?.setLocale(Locale.KOREA)
            speakAt = SystemClock.elapsedRealtime()
            tts?.speak("찜해 둔 카페가 가는 길 근처에 있어요. 들를까요?", true)
            log("tts.speak 호출 끝")
        }
        step(start + 14_000, "TTS 말하기 2(USAGE_ASSISTANT)") {
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            speakAt = SystemClock.elapsedRealtime()
            tts?.speak("네, 경유지로 넣을게요.", true)
            log("tts.speak 호출 끝")
        }
        step(start + 25_000, "TTS 뒤 오디오 상태") {
            log("audio.mode=${modeName(audio.mode)} active ${describe(audio.activePlaybackConfigurations)}")
        }
    }

    private fun sttSteps(start: Long) {
        step(start, "STT 만들기(ON_DEVICE)·초기화") {
            val s = SpeechToText(applicationContext, SttMode.ON_DEVICE)
            stt = s
            s.addListener(sttListener)
            s.initialize(object : SttInitListener {
                override fun onSuccess() = log("stt.initialize onSuccess")
                override fun onFailure(errorType: SttErrorType) = log("stt.initialize onFailure $errorType")
            })
        }
        step(start + 3_000, "STT 마이크 인식 request()") {
            stt?.setLocale(Locale.KOREA)
            stt?.request()
            log("stt.request 호출 끝")
        }
        step(start + 11_000, "STT stop()") { stt?.stop() }
        sendPcm(start + 14_000, "yes")
        sendPcm(start + 26_000, "no")
        sendPcm(start + 38_000, "cancel")
    }

    // 수락·거절 대답 pcm을 차례로 sendAudio로 넣는다(마이크 request() 없이). 문장 사이 12초, rounds번 되풀이
    private fun answerSteps(start: Long, names: List<String>, rounds: Int = 2) {
        step(start, "STT 만들기(ON_DEVICE)·초기화") {
            val s = SpeechToText(applicationContext, SttMode.ON_DEVICE)
            stt = s
            s.addListener(sttListener)
            s.initialize(object : SttInitListener {
                override fun onSuccess() = log("stt.initialize onSuccess")
                override fun onFailure(errorType: SttErrorType) = log("stt.initialize onFailure $errorType")
            })
        }
        step(start + 3_000, "STT setLocale(KOREA)") { stt?.setLocale(Locale.KOREA) }
        var at = start + 5_000
        repeat(rounds) { r ->
            names.forEach { name ->
                sendPcm(at, name)
                step(at + ANSWER_GAP_MS - 1_500, "요약 ${r + 1}회차 $name") { summarize(name) }
                at += ANSWER_GAP_MS
            }
        }
        step(at, "끝") {}
    }

    private fun summarize(name: String) {
        val t = trial?.takeIf { it.name == name } ?: return log("요약 $name: 기록 없음")
        val list = t.pieces.mapIndexed { i, (at, text) -> "#${i + 1} \"$text\" 다 보낸 때부터 ${at - t.sentAllAt}ms" }
        log("요약 $name: completed=true ${t.pieces.size}번, completed=false ${t.partials}번, 첫 청크→다 보냄 ${t.sentAllAt - t.firstChunkAt}ms | ${list.joinToString(" | ")}")
    }

    // 3,200바이트(16kHz·16비트·모노 100ms) 청크를 100ms 간격으로 보낸 뒤 completeAudioSend()
    private fun sendPcm(start: Long, name: String) {
        step(start, "STT sendAudio($name.pcm)") {
            val pcm = assets.open("probe/$name.pcm").use { it.readBytes() }
            val chunks = pcm.toList().chunked(CHUNK_BYTES).map { it.toByteArray() }
            val t = Trial(name).also { trial = it }
            log("pcm $name ${pcm.size}바이트, ${chunks.size}청크")
            chunks.forEachIndexed { i, c ->
                handler.postDelayed({
                    if (i == 0) t.firstChunkAt = SystemClock.elapsedRealtime()
                    runCatching { stt?.sendAudio(c) }.onFailure { log("sendAudio 예외 ${it::class.java.simpleName} ${it.message}") }
                }, i * CHUNK_MS)
            }
            handler.postDelayed({
                runCatching { stt?.completeAudioSend() }.onFailure { log("completeAudioSend 예외 ${it.message}") }
                t.sentAllAt = SystemClock.elapsedRealtime()
                log("stt.completeAudioSend 호출 끝($name)")
            }, chunks.size * CHUNK_MS)
        }
    }

    private fun naviSteps(start: Long) {
        step(start, "내비 연결") {
            val n = NaviHelper(applicationContext)
            navi = n
            n.initialize(
                {
                    log("navi 연결됨")
                    n.addListener(naviListener)
                },
                { e -> log("navi 연결 실패 ${e::class.java.simpleName} ${e.message}") },
            )
        }
        step(start + 3_000, "경로 설정(내비 음성 유도)") { navi?.requestRoute(destination) }
        for (t in 1..6) step(start + 3_000 + t * 3_000L, "재생목록 확인") {
            log("audio.mode=${modeName(audio.mode)} active ${describe(audio.activePlaybackConfigurations)}")
        }
        step(start + 25_000, "경로 취소") { navi?.cancelRoute() }
        step(start + 28_000, "끝") {}
    }

    private fun describe(configs: List<AudioPlaybackConfiguration>): String =
        if (configs.isEmpty()) "[0건]"
        else "[${configs.size}건] " + configs.joinToString(" | ") {
            val a = it.audioAttributes
            "usage=${usageName(a.usage)} content=${a.contentType} {$it}"
        }

    private fun usageName(u: Int) = when (u) {
        AudioAttributes.USAGE_MEDIA -> "MEDIA"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "ASSISTANCE_NAVIGATION_GUIDANCE"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "ASSISTANCE_SONIFICATION"
        AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
        AudioAttributes.USAGE_UNKNOWN -> "UNKNOWN"
        else -> "기타($u)"
    }

    private fun modeName(m: Int) = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "기타($m)"
    }

    private fun step(delayMs: Long, name: String, action: () -> Unit) {
        handler.postDelayed({
            log("── $name")
            runCatching(action).onFailure { log("예외 ${it::class.java.simpleName} ${it.message}") }
        }, delayMs)
    }

    private fun log(line: String) {
        val msg = "+${SystemClock.elapsedRealtime() - t0}ms $line"
        Log.i(TAG, msg)
        runOnUiThread { out.append(msg + "\n") }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        runCatching { tts?.removeEventListener(ttsListener); tts?.release() }
        runCatching { stt?.removeListener(sttListener); stt?.release() }
        runCatching { navi?.removeListener(naviListener); navi?.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DeulleulkkaProbe"
        private const val CHUNK_BYTES = 3_200
        private const val CHUNK_MS = 100L
        private const val ANSWER_GAP_MS = 12_000L
        // 본문이 받는 수락 낱말("들를게")과 그 변형, 취소·거절 대답(앞뒤 0.3초 묵음을 붙인 합성음)
        private val ANSWERS = listOf("a1_deulleulge", "a2_deulleulgeyo", "a3_eung_deulleulge", "a4_neokki_chwiso", "a5_daeumeyo")
    }
}
