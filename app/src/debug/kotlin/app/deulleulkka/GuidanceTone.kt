package app.deulleulkka

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * debug 빌드에만 있는 시험 소리. 에뮬레이터 내비는 안내 음성을 내지 않아, 내비 안내 음성과 같은 쓰임새
 * (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)로 짧은 소리를 내서 "안내 음성 중에는 말을 멈춘다"를 시험한다.
 */
object GuidanceTone {
    private const val RATE = 16_000
    private const val FREQUENCY_HZ = 660.0
    private const val AMPLITUDE = 6_000.0
    private const val STOP_SLACK_MS = 200L

    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private val stopper = Runnable { stop() }

    fun play(seconds: Int) {
        stop()
        val frames = RATE * seconds.coerceIn(1, 30)
        val pcm = ShortArray(frames) { i -> (sin(2 * PI * FREQUENCY_HZ * i / RATE) * AMPLITUDE).toInt().toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(frames * 2)
            .build()
        t.write(pcm, 0, frames)
        t.play()
        track = t
        main.postDelayed(stopper, frames * 1_000L / RATE + STOP_SLACK_MS)
    }

    fun stop() {
        main.removeCallbacks(stopper)
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }
}
