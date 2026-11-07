package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Biquad
import io.github.haigerman.meditate.audio.dsp.Drift
import io.github.haigerman.meditate.audio.dsp.OnePole
import io.github.haigerman.meditate.audio.dsp.PinkNoise
import io.github.haigerman.meditate.audio.dsp.Rng
import io.github.haigerman.meditate.audio.dsp.TWO_PI
import kotlin.math.PI
import kotlin.math.sin

/**
 * Waldgeräusche: leises Blätterrauschen im Wind und mehrere Vögel unterschiedlicher
 * "Art", die in zufälligen Abständen aus verschiedenen Richtungen und Entfernungen singen.
 */
class Forest(private val sr: Int, seed: Long = 67L) : SoundGenerator {
    private val rng = Rng(seed)

    // ---------- Blätter ----------
    private val leafHpL = Biquad(sr).highpass(1800f)
    private val leafHpR = Biquad(sr).highpass(1800f)
    private val leafLpL = Biquad(sr).lowpass(7500f)
    private val leafLpR = Biquad(sr).lowpass(7500f)
    private val breeze = Drift(sr, rng, 4f, 11f)
    private val flutterL = Drift(sr, rng, 0.03f, 0.12f)
    private val flutterR = Drift(sr, rng, 0.03f, 0.12f)
    private val air = PinkNoise(Rng(seed + 5))
    private val airLp = Biquad(sr).lowpass(350f)

    // ---------- Vögel ----------
    private class Note(
        val dur: Float, val f0: Float, val f1: Float, val amp: Float,
        val vibRate: Float = 0f, val vibDepth: Float = 0f, val gapAfter: Float = 0f,
        val harmonic: Float = 0.12f,
    )

    private enum class Species { TIT, BLACKBIRD, ROBIN, FINCH, DOVE }

    private inner class Bird(val species: Species, val pan: Float, val distance: Float, val pitch: Float) {
        val lp = OnePole(sr, 12000f - 8000f * distance)
        var notes: List<Note> = emptyList()
        var noteIdx = 0
        var t = 0f          // Zeit innerhalb der aktuellen Note (inkl. Pause)
        var phase = 0.0
        var nextCall = (rng.range(0.5f, 8f) * sr).toLong()
        val active get() = noteIdx < notes.size
    }

    private val birds = listOf(
        Bird(Species.TIT, 0.25f, 0.3f, 1f),
        Bird(Species.BLACKBIRD, 0.7f, 0.45f, 1f),
        Bird(Species.ROBIN, 0.55f, 0.6f, 1.03f),
        Bird(Species.FINCH, 0.15f, 0.55f, 0.97f),
        Bird(Species.TIT, 0.85f, 0.75f, 1.05f),
        Bird(Species.DOVE, 0.4f, 0.8f, 1f),
    )

    private var clock = 0L

    private fun phrase(species: Species, p: Float): List<Note> = buildList {
        when (species) {
            Species.TIT -> { // "zi-zi-bä"
                val hi = rng.range(5000f, 5800f) * p
                val lo = rng.range(3600f, 4200f) * p
                repeat(2 + rng.int(4)) {
                    add(Note(0.07f, hi, hi * 0.97f, 0.7f, gapAfter = 0.04f))
                    add(Note(0.11f, lo, lo * 0.98f, 0.9f, gapAfter = 0.09f))
                }
            }
            Species.BLACKBIRD -> { // flötender, melodischer Gesang
                var f = rng.range(1800f, 2600f) * p
                repeat(4 + rng.int(5)) {
                    val target = (f * rng.range(0.82f, 1.22f)).coerceIn(1600f, 3400f)
                    add(Note(rng.range(0.09f, 0.26f), f, target, rng.range(0.6f, 1f), 7f, 0.012f, rng.range(0.02f, 0.08f), 0.05f))
                    f = target
                }
                // leises, hohes Gezwitscher am Ende
                repeat(rng.int(4)) {
                    val hf = rng.range(5000f, 7000f) * p
                    add(Note(0.03f, hf, hf * 0.9f, 0.25f, gapAfter = 0.02f))
                }
            }
            Species.ROBIN -> { // perlende, hohe Kaskaden
                var f = rng.range(4500f, 6500f) * p
                repeat(8 + rng.int(14)) {
                    val next = (f * rng.range(0.85f, 1.15f)).coerceIn(3500f, 7000f)
                    add(Note(rng.range(0.025f, 0.06f), f, next, rng.range(0.4f, 0.9f), 30f, 0.02f, rng.range(0.008f, 0.03f)))
                    f = next
                }
            }
            Species.FINCH -> { // absteigende Tonfolge mit Schnörkel
                val n = 6 + rng.int(6)
                for (k in 0 until n) {
                    val f0 = (6200f - k * 180f) * p
                    add(Note(0.045f, f0, f0 * 0.75f, 0.6f + 0.04f * k, gapAfter = 0.055f - k * 0.003f))
                }
                add(Note(0.16f, 3200f * p, 4400f * p, 0.9f, 18f, 0.04f, 0f))
            }
            Species.DOVE -> { // tiefes, sanftes Gurren in der Ferne
                val f = rng.range(480f, 560f)
                add(Note(0.35f, f, f * 1.04f, 0.8f, 4f, 0.01f, 0.15f, 0.25f))
                add(Note(0.55f, f * 1.05f, f * 0.96f, 1f, 4f, 0.01f, 0.2f, 0.25f))
                add(Note(0.3f, f, f * 0.98f, 0.7f, 4f, 0.01f, 0f, 0.25f))
            }
        }
    }

    private fun scheduleNext(b: Bird) {
        val pause = when (b.species) {
            Species.DOVE -> rng.range(18f, 45f)
            Species.ROBIN -> rng.range(5f, 16f)
            else -> rng.range(4f, 14f)
        }
        b.nextCall = clock + (pause * sr).toLong()
    }

    private fun renderBird(b: Bird): Float {
        if (!b.active) {
            if (clock >= b.nextCall) {
                b.notes = phrase(b.species, b.pitch)
                b.noteIdx = 0
                b.t = 0f
                b.phase = 0.0
                scheduleNext(b)
            } else {
                return b.lp.lp(0f)
            }
        }
        val n = b.notes[b.noteIdx]
        val dt = 1f / sr
        var out = 0f
        if (b.t < n.dur) {
            val x = b.t / n.dur
            // weiche Hüllkurve (sinusförmig), leichte Betonung am Anfang
            val env = sin(PI.toFloat() * x).let { it * it } * (1.1f - 0.2f * x)
            val glide = x * x * (3f - 2f * x)
            var f = n.f0 + (n.f1 - n.f0) * glide
            if (n.vibDepth > 0f) f *= 1f + n.vibDepth * sin(TWO_PI.toFloat() * n.vibRate * b.t)
            b.phase += TWO_PI * f / sr
            if (b.phase > TWO_PI * 1000) b.phase -= TWO_PI * 1000
            out = (sin(b.phase).toFloat() + n.harmonic * sin(2 * b.phase).toFloat()) * env * n.amp
        }
        b.t += dt
        if (b.t >= n.dur + n.gapAfter) {
            b.noteIdx++
            b.t = 0f
        }
        val loud = 0.32f * (1f - 0.75f * b.distance)
        return b.lp.lp(out * loud)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val br = breeze.advance(frames)
        val leafAmt = 0.03f + 0.11f * br * br
        for (i in 0 until frames) {
            val fl = 0.3f + 0.7f * flutterL.next()
            val fr = 0.3f + 0.7f * flutterR.next()
            var l = leafLpL.process(leafHpL.process(rng.bipolar())) * leafAmt * fl
            var r = leafLpR.process(leafHpR.process(rng.bipolar())) * leafAmt * fr
            val a = airLp.process(air.next()) * 0.05f
            l += a
            r += a
            for (b in birds) {
                val s = renderBird(b)
                l += s * (1f - b.pan)
                r += s * b.pan
            }
            clock++
            left[i] = l
            right[i] = r
        }
    }
}
