package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.dsp.Biquad
import io.github.haigerman.meditate.audio.dsp.Drift
import io.github.haigerman.meditate.audio.dsp.Reverb
import io.github.haigerman.meditate.audio.dsp.Rng
import io.github.haigerman.meditate.audio.dsp.TWO_PI
import io.github.haigerman.meditate.audio.dsp.WarmWave
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sphärische Klänge: warme, schwebende Akkordflächen, die alle 20–40 Sekunden
 * ganz langsam ineinander überblenden. Darüber gelegentlich helle Schimmertöne,
 * alles in einen großen Hallraum getaucht. Tonart D-Dur – passt zum Windspiel.
 */
class Spheres(private val sr: Int, seed: Long = 79L) : SoundGenerator {
    private val rng = Rng(seed)

    private val chords = arrayOf(
        floatArrayOf(146.83f, 220.00f, 329.63f, 369.99f),          // Dadd9
        floatArrayOf(123.47f, 185.00f, 293.66f, 329.63f, 440.00f), // Bm(add11)
        floatArrayOf(98.00f, 146.83f, 246.94f, 369.99f),           // Gmaj7
        floatArrayOf(164.81f, 246.94f, 293.66f, 369.99f),          // Em9
        floatArrayOf(110.00f, 164.81f, 246.94f, 293.66f),          // Asus4
    )

    /** Ein Akkord = mehrere Töne, jeder aus drei leicht verstimmten Oszillatoren. */
    private inner class ChordSlot {
        var freqs = FloatArray(0)
        val phases = DoubleArray(5 * 3) { rng.float().toDouble() }
        var level = 0f
        var target = 0f
        fun set(chord: FloatArray) {
            freqs = chord
        }
    }

    private val slots = arrayOf(ChordSlot(), ChordSlot())
    private var active = 0
    private var currentChord = 0
    private var nextChange = 0L
    private var clock = 0L

    private val detuneCents = floatArrayOf(-7f, 0f, 7f)
    private val oscPan = floatArrayOf(0.15f, 0.5f, 0.85f)
    private val detuneDrift = Array(3) { Drift(sr, rng, 3f, 9f, -1f, 1f) }

    // tiefer Grundton (D2), gibt Halt
    private var dronePhase = 0.0
    private val droneSwell = Drift(sr, rng, 6f, 14f, 0.5f, 1f)

    // Filter-"Atmung"
    private val lpL = Biquad(sr)
    private val lpR = Biquad(sr)
    private val brightness = Drift(sr, rng, 5f, 15f)
    private var filterCounter = 0

    // Schimmertöne
    private inner class Shimmer {
        var active = false
        var freq = 0.0
        var phase = 0.0
        var t = 0f
        var attack = 1f
        var hold = 1f
        var release = 1f
        var amp = 0f
        var pan = 0.5f
    }

    private val shimmers = Array(6) { Shimmer() }
    private var nextShimmer = (2f * sr).toLong()

    private val reverb = Reverb(sr, roomSize = 0.93f, damping = 0.45f)

    private val xfadeSamples = 9f * sr

    init {
        slots[0].set(chords[0])
        slots[0].level = 0f
        slots[0].target = 1f
        nextChange = (rng.range(20f, 35f) * sr).toLong()
    }

    private fun changeChord() {
        var c = rng.int(chords.size)
        if (c == currentChord) c = (c + 1 + rng.int(chords.size - 1)) % chords.size
        currentChord = c
        val next = 1 - active
        slots[next].set(chords[c])
        slots[next].target = 1f
        slots[active].target = 0f
        active = next
        nextChange = clock + (rng.range(20f, 40f) * sr).toLong()
    }

    private fun spawnShimmer() {
        val s = shimmers.firstOrNull { !it.active } ?: return
        val chord = chords[currentChord]
        val base = chord[rng.int(chord.size)]
        // zwei bis drei Oktaven höher
        s.freq = base * (if (rng.chance(0.6f)) 4.0 else 8.0)
        while (s.freq > 2400) s.freq /= 2
        s.phase = 0.0
        s.t = 0f
        s.attack = rng.range(1.5f, 3.5f)
        s.hold = rng.range(0.5f, 2f)
        s.release = rng.range(4f, 8f)
        s.amp = rng.range(0.05f, 0.11f)
        s.pan = rng.range(0.15f, 0.85f)
        s.active = true
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val dt = 1f / sr
        val xfStep = 1f / xfadeSamples
        for (i in 0 until frames) {
            if (clock >= nextChange) changeChord()
            if (clock >= nextShimmer) {
                spawnShimmer()
                nextShimmer = clock + (rng.range(2.5f, 9f) * sr).toLong()
            }
            if (filterCounter == 0) {
                val b = brightness.advance(32)
                lpL.lowpass(500f + 1700f * b, 0.6f)
                lpR.lowpass(520f + 1750f * b, 0.6f)
                for (d in detuneDrift) d.advance(32)
            }
            filterCounter = (filterCounter + 1) and 31

            // ---- Akkordflächen ----
            var l = 0f
            var r = 0f
            for (slot in slots) {
                if (slot.level < slot.target) slot.level = minOf(slot.target, slot.level + xfStep)
                else if (slot.level > slot.target) slot.level = maxOf(slot.target, slot.level - xfStep)
                if (slot.level <= 0f) continue
                // gleichmäßige Leistung beim Überblenden
                val g = sin(slot.level * (Math.PI.toFloat() / 2f))
                val noteGain = g / slot.freqs.size
                for (n in slot.freqs.indices) {
                    val f = slot.freqs[n]
                    for (o in 0 until 3) {
                        val cents = detuneCents[o] + 3f * detuneDrift[o].value
                        val fr = f * (1f + cents * 0.000578f)
                        val idx = n * 3 + o
                        var ph = slot.phases[idx] + fr / sr
                        if (ph >= 1.0) ph -= 1.0
                        slot.phases[idx] = ph
                        val s = WarmWave.at(ph) * noteGain
                        l += s * (1f - oscPan[o])
                        r += s * oscPan[o]
                    }
                }
            }
            l = lpL.process(l) * 0.55f
            r = lpR.process(r) * 0.55f

            // ---- Grundton ----
            dronePhase += 73.42 / sr
            if (dronePhase >= 1.0) dronePhase -= 1.0
            val drone = sin(TWO_PI * dronePhase).toFloat() * 0.09f * droneSwell.next()
            l += drone
            r += drone

            // ---- Schimmer ----
            var shL = 0f
            var shR = 0f
            for (s in shimmers) {
                if (!s.active) continue
                val env = when {
                    s.t < s.attack -> (1f - cos(Math.PI.toFloat() * s.t / s.attack)) * 0.5f
                    s.t < s.attack + s.hold -> 1f
                    s.t < s.attack + s.hold + s.release -> {
                        val x = (s.t - s.attack - s.hold) / s.release
                        (1f - x) * (1f - x)
                    }
                    else -> {
                        s.active = false
                        0f
                    }
                }
                s.phase += TWO_PI * s.freq / sr
                if (s.phase > TWO_PI) s.phase -= TWO_PI
                val v = sin(s.phase).toFloat() * env * s.amp
                shL += v * (1f - s.pan)
                shR += v * s.pan
                s.t += dt
            }

            // ---- Raum ----
            reverb.process(l + shL * 2f, r + shR * 2f)
            left[i] = l * 0.6f + shL * 0.4f + reverb.outL * 0.9f
            right[i] = r * 0.6f + shR * 0.4f + reverb.outR * 0.9f
            clock++
        }
    }
}
