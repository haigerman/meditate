package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Biquad
import io.github.haigerman.meditate.audio.dsp.BrownNoise
import io.github.haigerman.meditate.audio.dsp.Drift
import io.github.haigerman.meditate.audio.dsp.OnePole
import io.github.haigerman.meditate.audio.dsp.PinkNoise
import io.github.haigerman.meditate.audio.dsp.Rng

/**
 * Wind: rosa Rauschen durch langsam wandernde Bandpässe, moduliert von Böen.
 * Dazu ein leises, tiefes Grollen und ein ganz dezentes Pfeifen.
 */
class Wind(private val sr: Int, seed: Long = 11L) : SoundGenerator {
    private val rng = Rng(seed)
    private val noiseL = PinkNoise(Rng(seed * 31 + 1))
    private val noiseR = PinkNoise(Rng(seed * 31 + 2))
    private val brown = BrownNoise(Rng(seed * 31 + 3))

    private val bpL = Biquad(sr)
    private val bpR = Biquad(sr)
    private val whistleL = Biquad(sr)
    private val whistleR = Biquad(sr)
    private val rumble = OnePole(sr, 120f)
    private val smoothL = OnePole(sr, 4000f)
    private val smoothR = OnePole(sr, 4000f)

    private val gust = Drift(sr, rng, 3f, 9f)
    private val gustFast = Drift(sr, rng, 0.5f, 1.8f)
    private val toneL = Drift(sr, rng, 2f, 6f)
    private val toneR = Drift(sr, rng, 2f, 6f)
    private val whistleTone = Drift(sr, rng, 4f, 10f)

    private var counter = 0
    private var intensity = 0.5f

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (counter == 0) {
                val g = gust.advance(32)
                val f = gustFast.advance(32)
                intensity = 0.2f + 0.8f * (g * g) * (0.75f + 0.25f * f)
                val tl = toneL.advance(32)
                val tr = toneR.advance(32)
                bpL.bandpass(180f + 650f * intensity * (0.6f + 0.4f * tl), 0.9f)
                bpR.bandpass(180f + 650f * intensity * (0.6f + 0.4f * tr), 0.9f)
                val wt = whistleTone.advance(32)
                val wf = 500f + 700f * intensity + 250f * wt
                whistleL.bandpass(wf, 12f)
                whistleR.bandpass(wf * 1.07f, 12f)
            }
            counter = (counter + 1) and 31

            val nl = noiseL.next()
            val nr = noiseR.next()
            val rum = rumble.lp(brown.next()) * 1.6f * intensity
            val whistleAmt = 0.6f * intensity * intensity
            val l = bpL.process(nl) * 2.2f * intensity + rum + whistleL.process(nl) * whistleAmt
            val r = bpR.process(nr) * 2.2f * intensity + rum + whistleR.process(nr) * whistleAmt
            left[i] = smoothL.lp(l)
            right[i] = smoothR.lp(r)
        }
    }
}
