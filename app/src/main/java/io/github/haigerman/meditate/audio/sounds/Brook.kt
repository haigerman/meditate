package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Biquad
import io.github.haigerman.meditate.audio.dsp.Drift
import io.github.haigerman.meditate.audio.dsp.OnePole
import io.github.haigerman.meditate.audio.dsp.Rng
import io.github.haigerman.meditate.audio.dsp.Svf
import kotlin.math.pow

/**
 * Kleiner Gebirgsbach: ruhiges Plätschern und Gluckern. Mehrere "Strömungen" – jeweils
 * Rauschen durch ein Resonanzfilter, dessen Tonhöhe unruhig hin- und hergleitet –
 * erzeugen das typische flüssige Murmeln, wenn Wasser über Steine läuft.
 * Kaum tiefe Frequenzen, damit es nicht nach Meer oder Brandung klingt.
 */
class Brook(private val sr: Int, seed: Long = 23L) : SoundGenerator {
    private val rng = Rng(seed)

    private inner class Babble(val home: Float, val pan: Float, val minMs: Float, val maxMs: Float) {
        val filter = Svf(sr)
        var freqTarget = home
        var freq = home
        var ampTarget = 0f
        var amp = 0f
        var countdown = 0

        fun step() {
            if (--countdown <= 0) {
                countdown = (rng.range(minMs, maxMs) * sr / 1000f).toInt()
                // Tonhöhe springt um bis zu ±0,7 Oktaven um ihren "Heimatbereich";
                // aufwärts etwas häufiger – so klingt Wasser
                val oct = rng.range(-0.55f, 0.7f)
                freqTarget = home * 2f.pow(oct)
                // meist leise, ab und zu ein deutlicheres Gluckern
                val u = rng.float()
                ampTarget = u * u * u
            }
        }
    }

    // Heimatfrequenzen zwischen 650 Hz und 3,4 kHz, über das Stereobild verteilt
    private val babbles = listOf(
        Babble(650f, 0.3f, 60f, 160f),
        Babble(900f, 0.65f, 50f, 140f),
        Babble(1250f, 0.45f, 40f, 120f),
        Babble(1700f, 0.2f, 35f, 110f),
        Babble(2300f, 0.8f, 30f, 100f),
        Babble(3400f, 0.55f, 25f, 90f),
        Babble(1050f, 0.9f, 55f, 150f),
        Babble(1500f, 0.1f, 45f, 130f),
    )

    // Gleiten von Tonhöhe und Lautstärke (bestimmt, wie "flüssig" es klingt)
    private val glide = 1f - kotlin.math.exp(-2.0 * Math.PI * 18.0 / sr).toFloat()
    private val ampGlide = 1f - kotlin.math.exp(-2.0 * Math.PI * 12.0 / sr).toFloat()

    // feines, helles Rieseln als Untergrund
    private val trickleL = Biquad(sr).bandpass(3200f, 0.8f)
    private val trickleR = Biquad(sr).bandpass(3600f, 0.8f)
    private val trickleModL = Drift(sr, rng, 0.03f, 0.12f)
    private val trickleModR = Drift(sr, rng, 0.03f, 0.12f)

    private val flow = Drift(sr, rng, 6f, 15f)
    private val hpL = Biquad(sr).highpass(350f)
    private val hpR = Biquad(sr).highpass(350f)
    private val softL = OnePole(sr, 7000f)
    private val softR = OnePole(sr, 7000f)
    private var counter = 0

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val f = 0.8f + 0.2f * flow.advance(frames)
        for (i in 0 until frames) {
            val update = counter == 0
            counter = (counter + 1) and 7

            var l = 0f
            var r = 0f
            for (b in babbles) {
                b.step()
                b.freq += (b.freqTarget - b.freq) * glide
                b.amp += (b.ampTarget - b.amp) * ampGlide
                if (update) b.filter.set(b.freq, 9f)
                val s = b.filter.bandpass(rng.bipolar()) * b.amp * 0.32f
                l += s * (1f - b.pan)
                r += s * b.pan
            }

            val tl = 0.4f + 0.6f * trickleModL.next()
            val tr = 0.4f + 0.6f * trickleModR.next()
            l += trickleL.process(rng.bipolar()) * 0.035f * tl
            r += trickleR.process(rng.bipolar()) * 0.035f * tr

            left[i] = softL.lp(hpL.process(l)) * f
            right[i] = softR.lp(hpR.process(r)) * f
        }
    }
}
