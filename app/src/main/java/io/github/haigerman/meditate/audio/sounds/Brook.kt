package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Biquad
import io.github.haigerman.meditate.audio.dsp.Drift
import io.github.haigerman.meditate.audio.dsp.OnePole
import io.github.haigerman.meditate.audio.dsp.PinkNoise
import io.github.haigerman.meditate.audio.dsp.Rng

/**
 * Fließendes Wasser: mehrere Frequenzbänder aus Rauschen, deren Lautstärke jeweils
 * unabhängig und unregelmäßig schwankt. Zusammen ergibt das das gleichmäßige,
 * lebendige Rauschen und Murmeln eines Bachs – ohne einzelne Bläschen.
 */
class Brook(private val sr: Int, seed: Long = 23L) : SoundGenerator {
    private val rng = Rng(seed)

    private class Band(sr: Int, rng: Rng, val freq: Float, q: Float, val gain: Float, minS: Float, maxS: Float) {
        val filterL = Biquad(sr).bandpass(freq * rng.range(0.94f, 1.06f), q)
        val filterR = Biquad(sr).bandpass(freq * rng.range(0.94f, 1.06f), q)
        val modL = Drift(sr, rng, minS, maxS)
        val modR = Drift(sr, rng, minS, maxS)
    }

    // tiefe Bänder schwanken langsamer, hohe schneller – wie bei echtem Wasser
    private val bands = listOf(
        Band(sr, rng, 220f, 0.9f, 0.55f, 0.25f, 0.9f),
        Band(sr, rng, 480f, 1.0f, 0.6f, 0.15f, 0.6f),
        Band(sr, rng, 900f, 1.1f, 0.55f, 0.08f, 0.35f),
        Band(sr, rng, 1600f, 1.2f, 0.42f, 0.05f, 0.22f),
        Band(sr, rng, 2800f, 1.3f, 0.28f, 0.04f, 0.15f),
        Band(sr, rng, 4500f, 1.4f, 0.14f, 0.03f, 0.12f),
    )

    private val noiseL = PinkNoise(Rng(seed * 13 + 1))
    private val noiseR = PinkNoise(Rng(seed * 13 + 2))
    private val whiteL = Rng(seed * 13 + 3)
    private val whiteR = Rng(seed * 13 + 4)
    private val flow = Drift(sr, rng, 5f, 14f)
    private val softL = OnePole(sr, 6000f)
    private val softR = OnePole(sr, 6000f)
    private val dcL = OnePole(sr, 50f)
    private val dcR = OnePole(sr, 50f)

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val f = 0.75f + 0.25f * flow.advance(frames)
        for (i in 0 until frames) {
            // Mischung aus rosa und weißem Rauschen: voll, aber nicht dumpf
            val nl = noiseL.next() * 0.7f + whiteL.bipolar() * 0.3f
            val nr = noiseR.next() * 0.7f + whiteR.bipolar() * 0.3f
            var l = 0f
            var r = 0f
            for (b in bands) {
                // Lautstärke je Band zwischen 15 % und 100 %, leicht quadratisch für mehr Kontrast
                val ml = b.modL.next()
                val mr = b.modR.next()
                l += b.filterL.process(nl) * b.gain * (0.15f + 0.85f * ml * ml)
                r += b.filterR.process(nr) * b.gain * (0.15f + 0.85f * mr * mr)
            }
            left[i] = dcL.hp(softL.lp(l)) * f
            right[i] = dcR.hp(softR.lp(r)) * f
        }
    }
}
