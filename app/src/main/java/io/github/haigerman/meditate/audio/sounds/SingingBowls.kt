package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Resonator
import io.github.haigerman.meditate.audio.dsp.Rng

/**
 * Klangschalen: drei Schalen mit typischen Teiltönen. Jeder Teilton besteht aus zwei
 * minimal verstimmten Moden – daraus entsteht das charakteristische "Wabern".
 * Die Schalen werden in ruhigen, unregelmäßigen Abständen sanft angeschlagen.
 */
class SingingBowls(private val sr: Int, seed: Long = 53L) : SoundGenerator {
    private val rng = Rng(seed)

    private val ratios = doubleArrayOf(1.0, 2.71, 5.15, 8.25)
    private val amps = doubleArrayOf(1.0, 0.55, 0.22, 0.08)
    private val t60s = doubleArrayOf(32.0, 20.0, 10.0, 5.0)

    private inner class Bowl(f: Double, val pan: Float) {
        val modes: List<Pair<Resonator, Double>> = buildList {
            for (k in ratios.indices) {
                val fk = f * ratios[k]
                val beat = rng.range(0.6f, 2.8f).toDouble() * (1 + k * 0.5)
                add(Resonator(sr, fk - beat / 2, t60s[k]) to amps[k] * 0.5)
                add(Resonator(sr, fk + beat / 2, t60s[k] * 0.9) to amps[k] * 0.5)
            }
        }
        fun strike(strength: Double) {
            for ((r, a) in modes) r.strike(strength * a)
        }
    }

    private val bowls = listOf(
        Bowl(174.6, 0.38f),  // F3
        Bowl(233.1, 0.62f),  // Bb3
        Bowl(311.1, 0.5f),   // Eb4
    )

    private var nextStrike = (0.6f * sr).toLong()
    private var echoStrike = -1L
    private var echoBowl = 0
    private var clock = 0L
    private var lastBowl = -1

    private fun pickBowl(): Int {
        var i = rng.int(bowls.size)
        if (i == lastBowl) i = (i + 1 + rng.int(bowls.size - 1)) % bowls.size
        lastBowl = i
        return i
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        for (i in 0 until frames) {
            if (clock == nextStrike) {
                val b = pickBowl()
                bowls[b].strike(rng.range(0.18f, 0.28f).toDouble())
                nextStrike = clock + (rng.range(14f, 30f) * sr).toLong()
                if (rng.chance(0.3f)) {
                    echoBowl = pickBowl()
                    echoStrike = clock + (rng.range(3f, 7f) * sr).toLong()
                }
            }
            if (clock == echoStrike) {
                bowls[echoBowl].strike(rng.range(0.1f, 0.18f).toDouble())
                echoStrike = -1L
            }
            clock++

            var l = 0f
            var r = 0f
            for (b in bowls) {
                var s = 0f
                for ((res, _) in b.modes) s += res.next()
                l += s * (1f - b.pan)
                r += s * b.pan
            }
            left[i] = l
            right[i] = r
        }
    }
}
