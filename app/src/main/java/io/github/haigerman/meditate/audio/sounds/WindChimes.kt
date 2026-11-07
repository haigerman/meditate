package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Drift
import io.github.haigerman.meditate.audio.dsp.Resonator
import io.github.haigerman.meditate.audio.dsp.Rng

/**
 * Windspiel: fünf Metallröhren, pentatonisch gestimmt (klingt in jeder Kombination
 * harmonisch). Jede Röhre hat die unharmonischen Obertöne eines frei schwingenden Stabs.
 * Der Wind bestimmt, wie oft und wie kräftig der Klöppel anschlägt.
 */
class WindChimes(private val sr: Int, seed: Long = 41L) : SoundGenerator {
    private val rng = Rng(seed)

    // D-Dur-Pentatonik: D5 E5 F#5 A5 B5 D6
    private val fundamentals = doubleArrayOf(587.33, 659.26, 739.99, 880.0, 987.77, 1174.66)
    private val ratios = doubleArrayOf(1.0, 2.756, 5.404, 8.933)
    private val partialAmps = doubleArrayOf(1.0, 0.45, 0.2, 0.08)
    private val t60s = doubleArrayOf(7.0, 3.5, 1.6, 0.7)

    private inner class Tube(f: Double, val pan: Float) {
        val partials = Array(ratios.size) { k ->
            // minimale Verstimmung macht jede Röhre individuell
            Resonator(sr, f * ratios[k] * (1.0 + rng.range(-0.002f, 0.002f)), t60s[k] * (1000.0 / f).coerceIn(0.7, 1.5))
        }
        fun strike(strength: Double) {
            for (k in partials.indices) partials[k].strike(strength * partialAmps[k])
        }
        val ringing: Boolean get() = partials[0].energy > 1e-10 || partials[1].energy > 1e-10
    }

    private val tubes = fundamentals.mapIndexed { i, f ->
        Tube(f, 0.2f + 0.6f * i / (fundamentals.size - 1))
    }

    private val wind = Drift(sr, rng, 4f, 12f)
    private var clusterLeft = 0
    private var clusterGap = 0
    private var lastTube = -1

    private fun hit(intensity: Float) {
        var idx = rng.int(tubes.size)
        if (idx == lastTube) idx = (idx + 1 + rng.int(tubes.size - 1)) % tubes.size
        lastTube = idx
        tubes[idx].strike((0.08 + 0.22 * rng.float()) * (0.4 + 0.6 * intensity))
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val w = wind.advance(frames)
        val intensity = w * w
        // Anschläge pro Sekunde: zwischen fast Stille und lebhaft
        val clusterRate = (0.12f + 1.1f * intensity) / sr

        for (i in 0 until frames) {
            if (clusterLeft == 0 && rng.float() < clusterRate) {
                clusterLeft = 1 + rng.int(2 + (intensity * 4).toInt())
                clusterGap = 0
            }
            if (clusterLeft > 0) {
                if (clusterGap <= 0) {
                    hit(intensity)
                    clusterLeft--
                    clusterGap = (rng.range(0.08f, 0.45f) * sr).toInt()
                } else {
                    clusterGap--
                }
            }

            var l = 0f
            var r = 0f
            for (t in tubes) {
                var s = 0f
                for (p in t.partials) s += p.next()
                l += s * (1f - t.pan)
                r += s * t.pan
            }
            left[i] = l
            right[i] = r
        }
    }
}
