package dev.murmr.app.feedback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Two short app-owned cues: a blip when the microphone opens and a rising two-note when the
 * text has reached the computer. Generated, not sampled, so there are no assets, and played as
 * assistance sonification so they follow the system sound volume, stay out of media, and are
 * untouched by the media mute the service applies during a hold. Opt-in (Settings > Feedback).
 */
class SoundCues {

    private val ready: ShortArray = tone(1320.0, 45)
    private val delivered: ShortArray = tone(880.0, 55) + silence(12) + tone(1320.0, 70)

    fun ready() = play(ready)

    fun delivered() = play(delivered)

    private fun play(pcm: ShortArray) {
        val track = runCatching {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                pcm.size * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.getOrElse {
            Log.w(TAG, "could not create cue track", it)
            return
        }
        if (track.state != AudioTrack.STATE_NO_STATIC_DATA && track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return
        }
        track.write(pcm, 0, pcm.size)
        // Release the track once it has played out; static tracks are one-shot.
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })
        track.play()
    }

    /** A sine burst with short fades so it neither clicks nor lingers. */
    private fun tone(frequencyHz: Double, ms: Int, amplitude: Double = 0.35): ShortArray {
        val n = SAMPLE_RATE * ms / 1000
        val fade = minOf(n / 4, SAMPLE_RATE * 8 / 1000)
        return ShortArray(n) { i ->
            val envelope = when {
                i < fade -> i / fade.toDouble()
                i > n - fade -> (n - i) / fade.toDouble()
                else -> 1.0
            }
            (sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE) * envelope * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun silence(ms: Int): ShortArray = ShortArray(SAMPLE_RATE * ms / 1000)

    private companion object {
        const val TAG = "SoundCues"
        const val SAMPLE_RATE = 24_000
    }
}
