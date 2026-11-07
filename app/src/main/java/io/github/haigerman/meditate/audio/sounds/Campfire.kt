package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Biquad
import io.github.haigerman.meditate.audio.dsp.BrownNoise
import io.github.haigerman.meditate.audio.dsp.Drift
import io.github.haigerman.meditate.audio.dsp.OnePole
import io.github.haigerman.meditate.audio.dsp.Rng
import kotlin.math.exp

/**
 * Lagerfeuer: tiefes Fauchen der Flammen, leises Zischen und – vor allem –
 * unregelmäßiges Knistern und gelegentliches Knacken von Holz.
 */
class Campfire(private val sr: Int, seed: Long = 37L) : SoundGenerator {
    private val rng = Rng(seed)
    private val brownL = BrownNoise(Rng(seed * 7 + 1))
    private val brownR = BrownNoise(Rng(seed * 7 + 2))

    private val roarL = Biquad(sr).lowpass(260f, 0.8f)
    private val roarR = Biquad(sr).lowpass(240f, 0.8f)
    private val lapL = Biquad(sr).bandpass(550f, 1.1f)
    private val lapR = Biquad(sr).bandpass(650f, 1.1f)
    private val hissL = Biquad(sr).highpass(3500f)
    private val hissR = Biquad(sr).highpass(3500f)
    private val dcL = OnePole(sr, 30f)
    private val dcR = OnePole(sr, 30f)

    private val heat = Drift(sr, rng, 3f, 10f)
    private val flickerL = Drift(sr, rng, 0.06f, 0.25f)
    private val flickerR = Drift(sr, rng, 0.06f, 0.25f)
    private val crackleActivity = Drift(sr, rng, 2f, 8f)

    private class Crackle(sr: Int) {
        val filter = Biquad(sr)
        var active = false
        var env = 0f
        var decay = 0f
        var amp = 0f
        var remaining = 0
        var panL = 0.5f
        var panR = 0.5f
    }

    private val crackles = Array(16) { Crackle(sr) }
    private var burstLeft = 0
    private var burstGap = 0

    private fun spawnCrackle(pop: Boolean) {
        val c = crackles.firstOrNull { !it.active } ?: return
        c.active = true
        c.filter.reset()
        val dur: Float
        if (pop) {
            c.filter.bandpass(rng.range(500f, 1600f), rng.range(1.5f, 4f))
            dur = rng.range(0.008f, 0.025f)
            c.amp = rng.range(1.2f, 2.6f)
        } else {
            c.filter.bandpass(rng.range(1500f, 7000f), rng.range(0.8f, 2.5f))
            dur = rng.range(0.0008f, 0.005f)
            // die meisten leise, wenige laut (Potenzverteilung)
            val u = rng.float()
            c.amp = 0.15f + 1.6f * u * u * u
        }
        c.env = 1f
        c.remaining = (dur * sr * 2f).toInt() + 1
        c.decay = exp(-4.0 / (dur * sr)).toFloat()
        val p = rng.range(0.25f, 0.75f)
        c.panL = 1f - p
        c.panR = p
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val h = heat.advance(frames)
        val act = crackleActivity.advance(frames)
        val crackleRate = (2f + 14f * act * act) / sr
        val burstRate = (0.15f + 0.6f * act) / sr
        val popRate = (0.06f + 0.25f * h) / sr

        for (i in 0 until frames) {
            if (rng.float() < crackleRate) spawnCrackle(false)
            if (rng.float() < popRate) spawnCrackle(true)
            if (burstLeft == 0 && rng.float() < burstRate) {
                burstLeft = 3 + rng.int(10)
                burstGap = 0
            }
            if (burstLeft > 0) {
                if (burstGap <= 0) {
                    spawnCrackle(false)
                    burstLeft--
                    burstGap = (rng.range(0.004f, 0.04f) * sr).toInt()
                } else {
                    burstGap--
                }
            }

            var l = 0f
            var r = 0f
            for (c in crackles) {
                if (!c.active) continue
                val s = c.filter.process(rng.bipolar() * c.env * c.amp)
                l += s * c.panL
                r += s * c.panR
                c.env *= c.decay
                if (--c.remaining <= 0) c.active = false
            }

            val fl = 0.55f + 0.45f * flickerL.next()
            val fr = 0.55f + 0.45f * flickerR.next()
            val bl = brownL.next()
            val br = brownR.next()
            val body = 0.6f + 0.4f * h
            l += roarL.process(bl) * 0.9f * body * fl
            r += roarR.process(br) * 0.9f * body * fr
            l += lapL.process(rng.bipolar()) * 0.07f * fl * body
            r += lapR.process(rng.bipolar()) * 0.07f * fr * body
            l += hissL.process(rng.bipolar()) * 0.012f * (0.5f + h)
            r += hissR.process(rng.bipolar()) * 0.012f * (0.5f + h)

            left[i] = dcL.hp(l)
            right[i] = dcR.hp(r)
        }
    }
}
