package app.deulleulkka

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.SystemClock
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * 음성: Pleos TTS(질문을 말함)와 STT(답을 들음). 둘 다 기기 안에서 처리(ON_DEVICE), 언어는 Locale.KOREA
 * (Locale.KOREAN은 "unsupported locale: ko-"로 거절됨). TTS는 initialize() 다음에 addEventListener()를 붙인다(README).
 * 들를까 음성은 USAGE_ASSISTANT로 내보내 내비 안내 음성(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)과 가린다.
 */
class Voice(context: Context, private val main: Handler, private val sink: Sink) {

    interface Sink {
        fun onSpeakingChanged(speaking: Boolean)
        fun onSpeechDone(id: Long)
        fun onHeard(text: String)
    }

    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var stt: SpeechToText? = null
    private var ttsReady = false
    private var sttReady = false
    private var speakingId = 0L
    private var listenUntil = 0L
    private var recognizing = false
    private var tone: ToneGenerator? = null

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() = post { sink.onSpeakingChanged(true) }
        override fun onDone() = post { endedByService() }
        override fun onStop() = post { endedByService() }
        override fun onError(errorType: TtsErrorType) = post { endedByService() }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() = post {
            recognizing = false
            // 듣는 시간이 남았으면 다시 연다(인식기는 한 덩어리마다 끝난다). 열자마자 끝났으면(마이크를 못 씀 등) 쉬었다 연다
            val quick = SystemClock.elapsedRealtime() - recognizeStartedAt < QUICK_END_MS
            reopenListening(if (quick) RETRY_MS else 0L)
        }
        override fun onUpdated(stt: String, completed: Boolean) {
            if (completed && stt.isNotBlank()) post { if (SystemClock.elapsedRealtime() < listenUntil) sink.onHeard(stt) }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onError(errorType: SttErrorType) = post {
            recognizing = false
            reopenListening(RETRY_MS)
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    /** 들를까가 말하는 중인지. 끝 알림을 잃어도 묻기가 멈추지 않도록 [SPEAK_STUCK_MS]가 지나면 끝난 것으로 본다. */
    val speaking: Boolean
        get() = speakingId != 0L && SystemClock.elapsedRealtime() - speakingSince < SPEAK_STUCK_MS

    /** 말하기·듣기가 모두 준비됐는지. 아니면 흐름이 소리 없이 카드만 띄운다. */
    val ready: Boolean
        get() = ttsReady && sttReady

    private var speakingSince = 0L
    private var stoppedAt = 0L
    private var recognizeStartedAt = 0L

    fun start() {
        if (tts == null) {
            val t = TextToSpeech(app, TtsMode.ON_DEVICE)
            tts = t
            t.initialize(object : TtsInitListener {
                override fun onSuccess() = post {
                    ttsReady = true
                    t.setLocale(Locale.KOREA)
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                }
                override fun onFailure(errorType: TtsErrorType) = post { ttsReady = false }
            })
            t.addEventListener(ttsListener)
        }
        if (stt == null) {
            val s = SpeechToText(app, SttMode.ON_DEVICE)
            stt = s
            s.addListener(sttListener)
            s.initialize(object : SttInitListener {
                override fun onSuccess() = post {
                    sttReady = true
                    s.setLocale(Locale.KOREA)
                    // 준비되기 전에 듣기를 시켰으면 지금 연다
                    reopenListening(0L)
                }
                override fun onFailure(errorType: SttErrorType) = post { sttReady = false }
            })
        }
    }

    fun stop() {
        stopSpeaking()
        stopListening()
        runCatching { tts?.removeEventListener(ttsListener) }
        runCatching { tts?.release() }
        runCatching { stt?.removeListener(sttListener) }
        runCatching { stt?.release() }
        runCatching { tone?.release() }
        tts = null
        stt = null
        tone = null
        ttsReady = false
        sttReady = false
    }

    /** 말할 수 없으면(음성 서비스가 준비되지 않음) 곧바로 끝난 것으로 알린다. 흐름은 카드로 이어 간다. */
    fun speak(id: Long, text: String) {
        val t = tts
        if (t == null || !ttsReady) {
            post { sink.onSpeechDone(id) }
            return
        }
        speakingId = id
        speakingSince = SystemClock.elapsedRealtime()
        sink.onSpeakingChanged(true)
        t.speak(text, true)
    }

    fun stopSpeaking() {
        if (speakingId == 0L) return
        runCatching { tts?.stop() }
        stoppedAt = SystemClock.elapsedRealtime()
        finishSpeaking()
    }

    /**
     * 음성 서비스의 끝 알림. 알림에는 어느 말인지가 없어, 우리가 멈춘 직후([STALE_END_MS] 안)에 오는 알림은 멈춘 말의 늦은 알림으로 보고
     * 버린다(곧바로 시작한 다음 말을 끝난 것으로 잡지 않도록).
     */
    private fun endedByService() {
        if (SystemClock.elapsedRealtime() - stoppedAt < STALE_END_MS) return
        finishSpeaking()
    }

    private fun reopenListening(delayMs: Long) {
        main.postDelayed({
            if (!recognizing && SystemClock.elapsedRealtime() < listenUntil) startRecognizing()
        }, delayMs)
    }

    fun listen(untilMs: Long) {
        listenUntil = untilMs
        if (!recognizing) startRecognizing()
    }

    fun stopListening() {
        listenUntil = 0L
        if (recognizing) runCatching { stt?.stop() }
        recognizing = false
    }

    /** 받지 않는 말을 들었을 때의 짧은 알림음(150ms). */
    fun beep() {
        // 소리 장치를 잡지 못하면 생성자가 예외를 낸다. 알림음 없이 카드 안내만 남긴다
        runCatching {
            val g = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME).also { tone = it }
            g.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        }
    }

    /**
     * 시험용(debug): 녹음한 대답(16kHz·16비트·모노 PCM)을 인식기에 넣는다. 마이크 대신 쓴다.
     * 마이크 인식(request())이 열려 있으면 넣은 소리를 인식하지 않아(2026-11-15 에뮬레이터), 마이크를 닫고 넣는다.
     * 다 넣은 뒤 [FEED_SETTLE_MS] 동안은 마이크를 다시 열지 않는다.
     */
    fun feedPcm(pcm: ByteArray) {
        val s = stt ?: return
        feedingUntil = SystemClock.elapsedRealtime() + pcm.size / PCM_CHUNK_BYTES * PCM_CHUNK_MS + FEED_SETTLE_MS
        if (recognizing) runCatching { s.stop() }
        recognizing = false
        val chunks = pcm.toList().chunked(PCM_CHUNK_BYTES).map { it.toByteArray() }
        chunks.forEachIndexed { i, c -> main.postDelayed({ runCatching { s.sendAudio(c) } }, i * PCM_CHUNK_MS) }
        main.postDelayed({ runCatching { s.completeAudioSend() } }, chunks.size * PCM_CHUNK_MS)
    }

    private var feedingUntil = 0L

    private fun startRecognizing() {
        val s = stt ?: return
        if (!sttReady) return
        if (SystemClock.elapsedRealtime() < feedingUntil) return
        recognizing = true
        recognizeStartedAt = SystemClock.elapsedRealtime()
        runCatching { s.request() }.onFailure { recognizing = false }
    }

    private fun finishSpeaking() {
        val id = speakingId
        speakingId = 0L
        sink.onSpeakingChanged(false)
        if (id != 0L) sink.onSpeechDone(id)
    }

    private fun post(block: () -> Unit) {
        main.post(block)
    }

    private companion object {
        const val TONE_VOLUME = 80
        const val BEEP_MS = 150
        const val PCM_CHUNK_BYTES = 3_200
        const val PCM_CHUNK_MS = 100L
        const val FEED_SETTLE_MS = 3_000L
        const val SPEAK_STUCK_MS = 20_000L
        const val STALE_END_MS = 1_500L
        const val QUICK_END_MS = 500L
        const val RETRY_MS = 1_000L
    }
}
