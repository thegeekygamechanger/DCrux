package app.dcrux.audio

import app.dcrux.AudioSink
import app.dcrux.SoundControl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Sound output through the computer's default audio device. */
class JavaAudioSink(sampleRate: Int, frames: Int) : AudioSink {
    private val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)
    private val line: SourceDataLine = AudioSystem.getSourceDataLine(format).apply { open(format, frames * 4 * 4); start() }
    private var bytes = ByteArray(0)

    override fun write(samples: ShortArray, count: Int) {
        if (bytes.size < count * 2) bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s shr 8).toByte()
        }
        line.write(bytes, 0, count * 2)
    }

    override fun close() { runCatching { line.drain(); line.stop() }; line.close() }
}

/** Mind Flow on a computer: the same engine as on the phone, playing for as long as the window is open. */
class DesktopSounds(private val uptime: () -> Long) : SoundControl {
    private val engine = SoundEngine({ rate, frames -> JavaAudioSink(rate, frames) }, uptime)
    override val levels = MutableStateFlow<Map<SoundId, Float>>(emptyMap())
    override val paused = MutableStateFlow(false)
    override val timerEnd = MutableStateFlow(0L)

    private fun push() {
        val now = levels.value
        SoundId.entries.forEach { engine.levels[it] = now[it] ?: 0f }
        if (now.isNotEmpty() && !paused.value) engine.start {
            // Ended by itself (sleep timer, or every sound turned down).
            if (timerEnd.value > 0 && uptime() >= timerEnd.value) { levels.value = emptyMap(); timerEnd.value = 0; engine.stopAt = 0 }
        }
    }

    override fun set(id: SoundId, level: Float) {
        levels.update { if (level <= 0f) it - id else it + (id to level.coerceIn(0f, 1f)) }
        if (level > 0f) paused.value = false
        push()
    }

    override fun setMix(mix: Map<SoundId, Float>) { levels.value = mix; paused.value = false; push() }

    override fun setTimer(minutes: Int) {
        val end = if (minutes <= 0) 0L else uptime() + minutes * 60_000L
        timerEnd.value = end
        engine.stopAt = end
    }

    override fun pause() { if (levels.value.isEmpty() || paused.value) return; paused.value = true; engine.stop() }
    override fun resume() { if (levels.value.isEmpty()) return; paused.value = false; push() }

    override fun stopAll() {
        levels.value = emptyMap(); paused.value = false; timerEnd.value = 0; engine.stopAt = 0
        SoundId.entries.forEach { engine.levels[it] = 0f }
    }
}
