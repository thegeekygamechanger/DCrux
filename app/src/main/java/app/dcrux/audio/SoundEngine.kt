package app.dcrux.audio

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class SoundId(val title: String, val hint: String, val headphones: Boolean = false) {
    RAIN("Rain", "Steady rain on leaves"),
    OCEAN("Ocean", "Slow rolling waves"),
    STREAM("Stream", "Water over stones"),
    WIND("Wind", "Soft wind in trees"),
    FIRE("Fireplace", "Crackle and warmth"),
    NIGHT("Night", "Crickets after dusk"),
    BROWN("Brown noise", "Deep, even hush"),
    PINK("Pink noise", "Balanced, like a waterfall"),
    WHITE("White noise", "Bright masking hiss"),
    HEART("Heartbeat", "Slow resting pulse"),
    DRONE("Deep drone", "A low, steady hum"),
    BOWL("Singing bowl", "A struck bowl, fading"),
    DELTA("Delta 2 Hz", "Binaural, for sleep", true),
    THETA("Theta 6 Hz", "Binaural, for meditation", true),
    ALPHA("Alpha 10 Hz", "Binaural, calm focus", true),
}

private const val SR = 44100
private const val TWO_PI = (2.0 * PI).toFloat()

private class Rng(seed: Int) {
    private var s = seed or 1
    fun next(): Float {
        s = s xor (s shl 13); s = s xor (s ushr 17); s = s xor (s shl 5)
        return s * 4.656613e-10f
    }

    /** 0..1 */
    fun unit(): Float = (next() + 1f) * 0.5f
}

private class Pink(seed: Int) {
    private val r = Rng(seed)
    private var b0 = 0f; private var b1 = 0f; private var b2 = 0f
    fun next(): Float {
        val w = r.next()
        b0 = 0.99765f * b0 + w * 0.0990460f
        b1 = 0.96300f * b1 + w * 0.2965164f
        b2 = 0.57000f * b2 + w * 1.0526913f
        return (b0 + b1 + b2 + w * 0.1848f) * 0.22f
    }
}

private class Brown(seed: Int) {
    private val r = Rng(seed)
    private var b = 0f
    fun next(): Float {
        b = (b + 0.02f * r.next()) / 1.02f
        return b * 3.5f
    }
}

private class LowPass(cutoff: Float) {
    private var a = coef(cutoff)
    private var y = 0f
    private fun coef(fc: Float) = 1f - exp(-TWO_PI * fc / SR)
    fun set(fc: Float) { a = coef(fc) }
    fun run(x: Float): Float { y += a * (x - y); return y }
}

/** State-variable band-pass; cheap to retune every sample. */
private class BandPass(var freq: Float, var q: Float) {
    private var low = 0f; private var band = 0f
    fun run(x: Float): Float {
        val f = 2f * sin(PI.toFloat() * freq / SR)
        low += f * band
        val high = x - low - band / q
        band += f * high
        return band
    }
}

/** A value that drifts between random targets; gives slow, natural movement. */
private class Drift(seed: Int, private val seconds: Float, private val lo: Float, private val hi: Float) {
    private val r = Rng(seed)
    private var value = (lo + hi) / 2
    private var target = value
    private var left = 0
    fun next(): Float {
        if (left <= 0) { target = lo + (hi - lo) * r.unit(); left = (seconds * SR * (0.5f + r.unit())).toInt() }
        left--
        value += (target - value) * (3f / (seconds * SR))
        return value
    }
}

private abstract class Voice {
    var gain = 0f
    /** Adds [n] frames into the left/right mix buffers, ramping gain from [g0] to [g1]. */
    abstract fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float)
}

private class NoiseVoice(private val kind: Int) : Voice() {
    private val pl = Pink(11); private val pr = Pink(29)
    private val bl = Brown(7); private val br = Brown(53)
    private val wl = Rng(3); private val wr = Rng(91)
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = g0 + (g1 - g0) * i / n
            when (kind) {
                0 -> { l[i] += bl.next() * g * 0.9f; r[i] += br.next() * g * 0.9f }
                1 -> { l[i] += pl.next() * g * 1.3f; r[i] += pr.next() * g * 1.3f }
                else -> { l[i] += wl.next() * g * 0.22f; r[i] += wr.next() * g * 0.22f }
            }
        }
    }
}

private class RainVoice : Voice() {
    private val pl = Pink(101); private val pr = Pink(202)
    private val lpl = LowPass(500f); private val lpr = LowPass(500f)
    private val drops = Rng(77)
    private val swell = Drift(5, 6f, 0.75f, 1.05f)
    private var envL = 0f; private var envR = 0f
    private val dl = Rng(13); private val dr = Rng(31)
    private val hl = LowPass(2500f); private val hr = LowPass(2500f)
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = (g0 + (g1 - g0) * i / n) * swell.next()
            val a = pl.next(); val b = pr.next()
            // a bright hiss: pink noise with its low end taken out
            var outL = (a - lpl.run(a)) * 2.2f
            var outR = (b - lpr.run(b)) * 2.2f
            // individual drops: short bright ticks at random sides
            val roll = drops.unit()
            if (roll < 0.0016f) { if (drops.next() > 0f) envL = 0.5f + drops.unit() * 0.5f else envR = 0.5f + drops.unit() * 0.5f }
            val tl = dl.next() * envL; val tr = dr.next() * envR
            outL += (tl - hl.run(tl)) * 0.55f; outR += (tr - hr.run(tr)) * 0.55f
            envL *= 0.9965f; envR *= 0.9965f
            l[i] += outL * g; r[i] += outR * g
        }
    }
}

private class OceanVoice : Voice() {
    private val bl = Brown(401); private val br = Brown(402)
    private val pl = Pink(403); private val pr = Pink(404)
    private val fl = LowPass(600f); private val fr = LowPass(600f)
    private val period = Drift(9, 10f, 7.5f, 12f)
    private var phase = 0f
    private var tick = 0
    private var cutoff = 600f
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            phase += 1f / (period.next() * SR)
            if (phase >= 1f) phase -= 1f
            // a wave: quick rise to the crest, long wash back out
            val env = if (phase < 0.28f) { val t = phase / 0.28f; t * t * (3f - 2f * t) } else { val t = (phase - 0.28f) / 0.72f; (1f - t) * (1f - t) }
            if (tick++ % 32 == 0) { cutoff = 250f + 3200f * env; fl.set(cutoff); fr.set(cutoff) }
            val g = (g0 + (g1 - g0) * i / n) * (0.16f + 0.84f * env)
            l[i] += fl.run(bl.next() * 0.7f + pl.next() * 1.6f) * g * 1.5f
            r[i] += fr.run(br.next() * 0.7f + pr.next() * 1.6f) * g * 1.5f
        }
    }
}

private class WindVoice : Voice() {
    private val pl = Pink(501); private val pr = Pink(502)
    private val bpl = BandPass(450f, 1.1f); private val bpr = BandPass(480f, 1.1f)
    private val freq = Drift(21, 4f, 260f, 900f)
    private val amp = Drift(22, 5f, 0.35f, 1f)
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val f = freq.next()
            bpl.freq = f; bpr.freq = f * 1.07f
            val g = (g0 + (g1 - g0) * i / n) * amp.next()
            l[i] += bpl.run(pl.next()) * g * 2.6f
            r[i] += bpr.run(pr.next()) * g * 2.6f
        }
    }
}

private class StreamVoice : Voice() {
    private val wl = Rng(601); private val wr = Rng(602)
    private val bands = Array(4) { BandPass(700f * (1 shl it) * 0.8f, 2.2f) }
    private val bandsR = Array(4) { BandPass(760f * (1 shl it) * 0.8f, 2.2f) }
    private val flutter = Array(4) { Drift(610 + it, 0.12f + it * 0.04f, 0.15f, 1f) }
    private val bed = Pink(620)
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = g0 + (g1 - g0) * i / n
            val a = wl.next(); val b = wr.next()
            var outL = 0f; var outR = 0f
            for (k in 0 until 4) {
                val f = flutter[k].next()
                outL += bands[k].run(a) * f; outR += bandsR[k].run(b) * f
            }
            val p = bed.next() * 0.25f
            l[i] += (outL * 0.16f + p) * g; r[i] += (outR * 0.16f + p) * g
        }
    }
}

private class FireVoice : Voice() {
    private val rumble = Brown(701); private val low = LowPass(180f)
    private val hiss = Pink(702); private val hissLow = LowPass(900f)
    private val rnd = Rng(703); private val crack = Rng(704)
    private val bp = BandPass(2400f, 1.6f)
    private var env = 0f; private var decay = 0.99f; private var pan = 0.5f
    private val breath = Drift(705, 2.5f, 0.6f, 1f)
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = g0 + (g1 - g0) * i / n
            if (rnd.unit() < 0.00035f) {
                env = 0.4f + rnd.unit() * 0.9f
                decay = 0.985f + rnd.unit() * 0.012f
                bp.freq = 1300f + rnd.unit() * 3200f
                pan = rnd.unit()
            }
            val c = bp.run(crack.next() * env) * 1.4f
            env *= decay
            val h = hiss.next()
            val base = low.run(rumble.next()) * 1.5f * breath.next() + (h - hissLow.run(h)) * 0.35f
            l[i] += (base + c * (1f - pan)) * g; r[i] += (base + c * pan) * g
        }
    }
}

private class NightVoice : Voice() {
    private class Cricket(seed: Int, val freq: Float, val pan: Float, val rate: Float) {
        val r = Rng(seed); var phase = 0f; var t = (r.unit() * SR).toInt(); var wait = 0
        fun next(): Float {
            phase += TWO_PI * freq / SR
            if (phase > TWO_PI) phase -= TWO_PI
            t++
            if (wait > 0) { wait--; if (wait == 0) t = 0; return 0f }
            // one chirp = three short pulses
            val pulse = (SR * 0.017f).toInt(); val gap = (SR * 0.013f).toInt(); val unit = pulse + gap
            val k = t / unit
            if (k >= 3) { wait = (SR * rate * (0.8f + r.unit() * 0.5f)).toInt(); return 0f }
            val inPulse = t % unit
            if (inPulse >= pulse) return 0f
            val x = inPulse / pulse.toFloat()
            return sin(phase) * sin(PI.toFloat() * x)
        }
    }

    private val crickets = arrayOf(Cricket(801, 4300f, 0.2f, 0.9f), Cricket(802, 4750f, 0.75f, 1.25f), Cricket(803, 3900f, 0.5f, 1.7f))
    private val bed = Pink(810); private val bedLow = LowPass(700f)
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = g0 + (g1 - g0) * i / n
            val air = bedLow.run(bed.next()) * 0.9f
            var outL = air; var outR = air
            for (c in crickets) { val s = c.next() * 0.16f; outL += s * (1f - c.pan); outR += s * c.pan }
            l[i] += outL * g; r[i] += outR * g
        }
    }
}

private class HeartVoice : Voice() {
    private var t = 0
    private val beat = (SR * 60f / 54f).toInt()
    private fun thump(age: Int, freq: Float, tau: Float): Float {
        if (age < 0) return 0f
        val s = age / SR.toFloat()
        val e = exp(-s / tau)
        if (e < 0.001f) return 0f
        val p = TWO_PI * freq * s
        // harmonics keep the pulse audible on small phone speakers
        return (sin(p) + 0.45f * sin(2f * p) + 0.2f * sin(3f * p)) * e
    }

    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = g0 + (g1 - g0) * i / n
            val s = (thump(t, 52f, 0.075f) + 0.7f * thump(t - (SR * 0.29f).toInt(), 66f, 0.055f)) * 0.55f * g
            l[i] += s; r[i] += s
            if (++t >= beat) t = 0
        }
    }
}

private class DroneVoice : Voice() {
    private val base = 110f
    private val ratios = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f)
    private val amps = floatArrayOf(0.5f, 1f, 0.42f, 0.3f, 0.12f)
    private val phL = FloatArray(5); private val phR = FloatArray(5)
    private val swell = Drift(901, 7f, 0.65f, 1f)
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = (g0 + (g1 - g0) * i / n) * swell.next() * 0.2f
            var a = 0f; var b = 0f
            for (k in 0 until 5) {
                // a fraction of a hertz apart left and right: a slow, breathing beat
                phL[k] += TWO_PI * (base * ratios[k] - 0.12f * (k + 1)) / SR
                phR[k] += TWO_PI * (base * ratios[k] + 0.12f * (k + 1)) / SR
                if (phL[k] > TWO_PI) phL[k] -= TWO_PI
                if (phR[k] > TWO_PI) phR[k] -= TWO_PI
                a += sin(phL[k]) * amps[k]; b += sin(phR[k]) * amps[k]
            }
            l[i] += a * g; r[i] += b * g
        }
    }
}

private class BowlVoice : Voice() {
    private val f0 = 196f
    private val ratios = floatArrayOf(1f, 2.76f, 5.4f, 8.93f)
    private val amps = floatArrayOf(1f, 0.55f, 0.28f, 0.12f)
    private val taus = floatArrayOf(7f, 4.5f, 2.6f, 1.3f)
    private val env = FloatArray(4)
    private val ph = FloatArray(4); private val ph2 = FloatArray(4)
    private val rnd = Rng(1001)
    private var wait = SR / 4
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            if (--wait <= 0) {
                for (k in 0 until 4) env[k] = 1f
                wait = (SR * (11f + rnd.unit() * 5f)).toInt()
            }
            val g = (g0 + (g1 - g0) * i / n) * 0.3f
            var a = 0f; var b = 0f
            for (k in 0 until 4) {
                val f = f0 * ratios[k]
                ph[k] += TWO_PI * f / SR; ph2[k] += TWO_PI * (f + 0.7f) / SR
                if (ph[k] > TWO_PI) ph[k] -= TWO_PI
                if (ph2[k] > TWO_PI) ph2[k] -= TWO_PI
                env[k] *= exp(-1f / (taus[k] * SR))
                a += sin(ph[k]) * env[k] * amps[k]; b += sin(ph2[k]) * env[k] * amps[k]
            }
            l[i] += (a * 0.8f + b * 0.2f) * g; r[i] += (b * 0.8f + a * 0.2f) * g
        }
    }
}

/** A steady tone in each ear a few hertz apart; the brain hears the difference as a slow beat. */
private class BinauralVoice(private val beat: Float) : Voice() {
    private val carrier = 180f
    private var pl = 0f; private var pr = 0f
    private val bedL = Brown(1101); private val bedR = Brown(1102)
    override fun render(l: FloatArray, r: FloatArray, n: Int, g0: Float, g1: Float) {
        for (i in 0 until n) {
            val g = g0 + (g1 - g0) * i / n
            pl += TWO_PI * carrier / SR; pr += TWO_PI * (carrier + beat) / SR
            if (pl > TWO_PI) pl -= TWO_PI
            if (pr > TWO_PI) pr -= TWO_PI
            l[i] += (sin(pl) * 0.22f + bedL.next() * 0.12f) * g
            r[i] += (sin(pr) * 0.22f + bedR.next() * 0.12f) * g
        }
    }
}

/**
 * Every sound in Mind Flow is synthesised here, sample by sample, while it plays.
 * There are no recordings in the app, so there is nothing to license.
 */
class SoundEngine(private val sink: (sampleRate: Int, frames: Int) -> app.dcrux.AudioSink, private val clock: () -> Long) {
    /** Target level per sound, 0..1. Absent or 0 means off. */
    val levels = ConcurrentHashMap<SoundId, Float>()

    /** The clock time at which playback should have faded out; 0 = no timer. */
    @Volatile var stopAt = 0L

    /** The token of the loop that is allowed to run; replacing it retires the old loop. */
    @Volatile private var current: Any? = null

    val isRunning: Boolean get() = current != null

    private fun newVoice(id: SoundId): Voice = when (id) {
        SoundId.RAIN -> RainVoice()
        SoundId.OCEAN -> OceanVoice()
        SoundId.STREAM -> StreamVoice()
        SoundId.WIND -> WindVoice()
        SoundId.FIRE -> FireVoice()
        SoundId.NIGHT -> NightVoice()
        SoundId.BROWN -> NoiseVoice(0)
        SoundId.PINK -> NoiseVoice(1)
        SoundId.WHITE -> NoiseVoice(2)
        SoundId.HEART -> HeartVoice()
        SoundId.DRONE -> DroneVoice()
        SoundId.BOWL -> BowlVoice()
        SoundId.DELTA -> BinauralVoice(2f)
        SoundId.THETA -> BinauralVoice(6f)
        SoundId.ALPHA -> BinauralVoice(10f)
    }

    /** [onFinished] runs when this playback ends by itself or by [stop], not when it is replaced. */
    @Synchronized
    fun start(onFinished: () -> Unit) {
        if (current != null) return
        val token = Any()
        current = token
        Thread({ loop(token, onFinished) }, "dcrux-audio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        current = null
    }

    private fun loop(token: Any, onFinished: () -> Unit) {
        val voices = HashMap<SoundId, Voice>()
        val frames = 2048
        val track = sink(SR, frames)
        val l = FloatArray(frames); val r = FloatArray(frames)
        val out = ShortArray(frames * 2)
        var master = 0f
        try {
            while (current === token) {
                l.fill(0f); r.fill(0f)
                // sleep timer: fade over the last 20 seconds, then finish
                val end = stopAt
                val targetMaster = if (end > 0) ((end - clock()) / 20_000f).coerceIn(0f, 1f) else 1f
                if (end > 0 && targetMaster <= 0f && master < 0.01f) break
                var any = false
                for (id in SoundId.entries) {
                    val target = (levels[id] ?: 0f).coerceIn(0f, 1f)
                    val v = voices[id]
                    if (target <= 0f && (v == null || v.gain < 0.0005f)) { v?.gain = 0f; continue }
                    val voice = v ?: newVoice(id).also { voices[id] = it }
                    val next = voice.gain + (target - voice.gain) * 0.25f
                    voice.render(l, r, frames, voice.gain, next)
                    voice.gain = next
                    any = true
                }
                if (!any && levels.values.none { it > 0f }) break
                val m0 = master
                master += (targetMaster - master) * 0.2f
                for (i in 0 until frames) {
                    val m = m0 + (master - m0) * i / frames
                    out[2 * i] = (soft(l[i] * m) * 30000f).toInt().toShort()
                    out[2 * i + 1] = (soft(r[i] * m) * 30000f).toInt().toShort()
                }
                track.write(out, out.size)
            }
        } finally {
            runCatching { track.close() }
            val replaced = current != null && current !== token
            if (current === token) current = null
            if (!replaced) onFinished()
        }
    }

    /** Gentle limiter so several layered sounds never clip. */
    private fun soft(x: Float): Float {
        val c = x.coerceIn(-1.5f, 1.5f)
        return c - (c * c * c) * (4f / 27f)
    }
}
