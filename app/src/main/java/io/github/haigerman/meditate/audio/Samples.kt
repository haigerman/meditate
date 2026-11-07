package io.github.haigerman.meditate.audio

/** Dekodierte Aufnahme: 16-Bit-Stereo, verschränkt (L, R, L, R …). */
class Pcm(val data: ShortArray, val sampleRate: Int) {

    /** Einfache lineare Umrechnung auf eine andere Abtastrate (nur falls nötig). */
    fun resampled(target: Int): Pcm {
        if (target == sampleRate) return this
        val inFrames = data.size / 2
        val outFrames = (inFrames.toLong() * target / sampleRate).toInt()
        val out = ShortArray(outFrames * 2)
        val ratio = sampleRate.toDouble() / target
        for (i in 0 until outFrames) {
            val x = i * ratio
            val j = x.toInt().coerceAtMost(inFrames - 2)
            val f = x - j
            for (c in 0..1) {
                val a = data[j * 2 + c]
                val b = data[(j + 1) * 2 + c]
                out[i * 2 + c] = (a + (b - a) * f).toInt().toShort()
            }
        }
        return Pcm(out, target)
    }
}

/**
 * Liefert Aufnahmen per Name (z. B. "brook"). Darf `null` zurückgeben, solange die
 * Aufnahme noch geladen wird – der Klang bleibt dann so lange still.
 * Wird vom Audio-Thread aufgerufen, darf also nicht blockieren.
 */
fun interface SampleProvider {
    fun get(name: String): Pcm?

    companion object {
        val NONE = SampleProvider { null }
    }
}
