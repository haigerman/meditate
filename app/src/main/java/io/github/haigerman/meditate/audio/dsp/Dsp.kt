package io.github.haigerman.meditate.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

const val TWO_PI = (2.0 * PI)

/** Schneller Xorshift-Zufallsgenerator (keine Allokationen, deterministisch per Seed). */
class Rng(seed: Long) {
    private var s: Long = if (seed == 0L) 0x2545F4914F6CDD1DL else seed

    fun nextLong(): Long {
        var x = s
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        s = x
        return x
    }

    /** [0, 1) */
    fun float(): Float = (nextLong() ushr 40).toInt() / 16777216f

    /** [-1, 1) */
    fun bipolar(): Float = float() * 2f - 1f

    fun range(a: Float, b: Float): Float = a + (b - a) * float()

    fun chance(p: Float): Boolean = float() < p

    fun int(n: Int): Int = ((nextLong() ushr 33) % n).toInt()
}

/** Rosa Rauschen (Paul Kellet, "economy"-Variante). */
class PinkNoise(private val rng: Rng) {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f

    fun next(): Float {
        val w = rng.bipolar()
        b0 = 0.99765f * b0 + w * 0.0990460f
        b1 = 0.96300f * b1 + w * 0.2965164f
        b2 = 0.57000f * b2 + w * 1.0526913f
        return (b0 + b1 + b2 + w * 0.1848f) * 0.22f
    }
}

/** Braunes Rauschen (leckender Integrator). */
class BrownNoise(private val rng: Rng) {
    private var y = 0f
    fun next(): Float {
        y = (y + rng.bipolar() * 0.02f) * 0.998f
        return y * 3.5f
    }
}

/** Einpoliger Tiefpass. */
class OnePole(sampleRate: Int, cutoff: Float) {
    private val sr = sampleRate.toFloat()
    private var a = 0f
    private var y = 0f

    init {
        setCutoff(cutoff)
    }

    fun setCutoff(fc: Float) {
        a = (1.0 - exp(-TWO_PI * fc / sr)).toFloat()
    }

    fun lp(x: Float): Float {
        y += a * (x - y)
        return y
    }

    fun hp(x: Float): Float = x - lp(x)
}

/** RBJ-Biquad. */
class Biquad(sampleRate: Int) {
    private val sr = sampleRate.toDouble()
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double) {
        b0 = (nb0 / na0).toFloat()
        b1 = (nb1 / na0).toFloat()
        b2 = (nb2 / na0).toFloat()
        a1 = (na1 / na0).toFloat()
        a2 = (na2 / na0).toFloat()
    }

    /** Bandpass mit konstanter Spitzenverstärkung 0 dB. */
    fun bandpass(f: Float, q: Float): Biquad {
        val w = TWO_PI * f.coerceIn(10f, (sr * 0.45).toFloat()) / sr
        val alpha = sin(w) / (2.0 * q)
        set(alpha, 0.0, -alpha, 1 + alpha, -2 * cos(w), 1 - alpha)
        return this
    }

    fun lowpass(f: Float, q: Float = 0.707f): Biquad {
        val w = TWO_PI * f.coerceIn(10f, (sr * 0.45).toFloat()) / sr
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        return this
    }

    fun highpass(f: Float, q: Float = 0.707f): Biquad {
        val w = TWO_PI * f.coerceIn(10f, (sr * 0.45).toFloat()) / sr
        val alpha = sin(w) / (2.0 * q)
        val c = cos(w)
        set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        return this
    }

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}

/**
 * Langsam driftender Zufallswert in [0, 1]: wandert mit weicher (Cosinus-)Interpolation
 * zu immer neuen Zielwerten. Ideal für Böen, Flackern, Intensitäten.
 */
class Drift(
    private val sampleRate: Int,
    private val rng: Rng,
    private val minSeconds: Float,
    private val maxSeconds: Float,
    private val lo: Float = 0f,
    private val hi: Float = 1f,
) {
    private var from = rng.range(lo, hi)
    private var to = rng.range(lo, hi)
    private var pos = 0f
    private var step = newStep()

    private fun newStep(): Float = 1f / (rng.range(minSeconds, maxSeconds) * sampleRate)

    var value: Float = from
        private set

    fun next(): Float {
        pos += step
        if (pos >= 1f) {
            pos -= 1f
            from = to
            to = rng.range(lo, hi)
            step = newStep()
        }
        val t = (1f - cos(pos * PI.toFloat())) * 0.5f
        value = from + (to - from) * t
        return value
    }

    /** Für Blockverarbeitung: n Samples auf einmal weiterschalten. */
    fun advance(n: Int): Float {
        pos += step * n
        while (pos >= 1f) {
            pos -= 1f
            from = to
            to = rng.range(lo, hi)
            step = newStep()
        }
        val t = (1f - cos(pos * PI.toFloat())) * 0.5f
        value = from + (to - from) * t
        return value
    }
}

/**
 * Gedämpfter Sinus-Resonator (Modalsynthese). Ein "Anschlag" fügt Energie hinzu,
 * danach klingt der Ton exponentiell aus. Sehr günstig: 4 Multiplikationen pro Sample.
 */
class Resonator(sampleRate: Int, freq: Double, t60Seconds: Double) {
    private val c: Double
    private val s: Double
    private val d: Double
    private var re = 0.0
    private var im = 0.0

    init {
        val w = TWO_PI * freq / sampleRate
        c = cos(w)
        s = sin(w)
        // Amplitude fällt in t60 Sekunden um 60 dB
        d = Math.pow(10.0, -3.0 / (t60Seconds * sampleRate))
    }

    fun strike(amp: Double) {
        // Phasenrichtig addieren, damit sich mehrfache Anschläge nicht auslöschen
        val mag = kotlin.math.sqrt(re * re + im * im)
        if (mag > 1e-9) {
            re += amp * re / mag
            im += amp * im / mag
        } else {
            re += amp
        }
    }

    val energy: Double get() = re * re + im * im

    fun next(): Float {
        val nre = (re * c - im * s) * d
        val nim = (re * s + im * c) * d
        re = nre
        im = nim
        return im.toFloat()
    }
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
