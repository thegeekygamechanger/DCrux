package app.dcrux.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import app.dcrux.AudioSink

/** Sound output through Android's AudioTrack. */
class AndroidAudioSink(sampleRate: Int, frames: Int) : AudioSink {
    private val track = AudioTrack(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
        AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
        maxOf(AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), frames * 4 * 2),
        AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE,
    ).apply { play() }

    override fun write(samples: ShortArray, count: Int) { track.write(samples, 0, count) }
    override fun close() { runCatching { track.stop() }; track.release() }
}
