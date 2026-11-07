package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Biquad
import io.github.haigerman.meditate.audio.dsp.Drift
import io.github.haigerman.meditate.audio.dsp.OnePole
import io.github.haigerman.meditate.audio.dsp.Rng
import io.github.haigerman.meditate.audio.dsp.TWO_PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Kleiner Bach: viele winzige "Blasen" (kurze, ansteigende Sinus-Chirps – so klingt
 * Wasser physikalisch) über einem plätschernden Rauschteppich.
 */
class Brook(private val sr: Int, seed: Long = 23L) : SoundGenerator {
    private val rng = Rng(seed)

    private class Bubble {
        var active = false
        var phase = 0.0
        var freq = 0.0
        var rise = 0.0       // relative Frequenzzunahme pro Sekunde
        var t = 0.0
        var dur = 0.0
        var decay = 0.0      // pro Sample
        var amp = 0f
        var env = 0f
        var panL = 0.5f
        var panR = 0.5f
    }

    private val bubbles = Array(64) { Bubble() }

    private val bedL = Biquad(sr).bandpass(1400f, 0.7f)
    private val bedR = Biquad(sr).bandpass(1600f, 0.7f)
    private val bedHiL = Biquad(sr).bandpass(3800f, 1.2f)
    private val bedHiR = Biquad(sr).bandpass(4200f, 1.2f)
    private val gurgleL = Drift(sr, rng, 0.04f, 0.18f)
    private val gurgleR = Drift(sr, rng, 0.04f, 0.18f)
    private val flow = Drift(sr, rng, 4f, 12f)
    private val toneL = OnePole(sr, 7000f)
    private val toneR = OnePole(sr, 7000f)
    private val dcL = OnePole(sr, 40f)
    private val dcR = OnePole(sr, 40f)

    private fun spawn(big: Boolean) {
        val b = bubbles.firstOrNull { !it.active } ?: return
        b.active = true
        b.phase = 0.0
        b.t = 0.0
        if (big) {
            b.freq = rng.range(350f, 900f).toDouble()
            b.dur = rng.range(0.025f, 0.06f).toDouble()
            b.amp = rng.range(0.25f, 0.6f)
        } else {
            // kleine Blasen: höher, kürzer, leiser
            val u = rng.float()
            b.freq = (900.0 + 3200.0 * u * u)
            b.dur = rng.range(0.006f, 0.022f).toDouble()
            b.amp = rng.range(0.05f, 0.3f) * (1.2f - u * 0.6f)
        }
        b.rise = rng.range(1.5f, 6f).toDouble()
        b.decay = exp(-5.0 / (b.dur * sr))
        b.env = 1f
        val p = rng.range(0.1f, 0.9f)
        b.panL = 1f - p
        b.panR = p
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val f = flow.advance(frames)
        val smallRate = (90f + 140f * f) / sr
        val bigRate = (1.5f + 5f * f) / sr

        for (i in 0 until frames) {
            if (rng.float() < smallRate) spawn(false)
            if (rng.float() < bigRate) spawn(true)

            var l = 0f
            var r = 0f
            for (b in bubbles) {
                if (!b.active) continue
                val fr = b.freq * (1.0 + b.rise * b.t)
                b.phase += TWO_PI * fr / sr
                if (b.phase > TWO_PI) b.phase -= TWO_PI
                // weiche Attack (1 ms), dann exponentielles Abklingen
                val att = if (b.t < 0.001) (b.t / 0.001).toFloat() else 1f
                val s = sin(b.phase).toFloat() * b.amp * b.env * att
                l += s * b.panL
                r += s * b.panR
                b.env *= b.decay.toFloat()
                b.t += 1.0 / sr
                if (b.t > b.dur * 1.6) b.active = false
            }

            val gl = 0.35f + 0.65f * gurgleL.next()
            val gr = 0.35f + 0.65f * gurgleR.next()
            val nl = rng.bipolar()
            val nr = rng.bipolar()
            l += (bedL.process(nl) * 0.22f + bedHiL.process(nl) * 0.08f) * gl * (0.7f + 0.3f * f)
            r += (bedR.process(nr) * 0.22f + bedHiR.process(nr) * 0.08f) * gr * (0.7f + 0.3f * f)

            val ol = toneL.lp(l)
            val orr = toneR.lp(r)
            left[i] = dcL.hp(ol) * 1.1f
            right[i] = dcR.hp(orr) * 1.1f
        }
    }
}
