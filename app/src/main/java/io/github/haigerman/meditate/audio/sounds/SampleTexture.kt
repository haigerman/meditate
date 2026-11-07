package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.Pcm
import io.github.haigerman.meditate.audio.SampleProvider
import io.github.haigerman.meditate.audio.dsp.Rng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spielt eine echte Aufnahme endlos ab, ohne dass man eine Schleife erkennt:
 * Alle [minSegment]–[maxSegment] Sekunden springt die Wiedergabe an eine zufällige
 * andere Stelle und blendet dabei über [crossfade] Sekunden gleichmäßig über.
 */
class SampleTexture(
    private val sr: Int,
    private val samples: SampleProvider,
    private val name: String,
    private val minSegment: Float = 10f,
    private val maxSegment: Float = 25f,
    private val crossfade: Float = 3f,
    seed: Long = 97L,
) : SoundGenerator {
    private val rng = Rng(seed)
    private var pcm: Pcm? = null
    private var frames = 0

    private var pos = 0          // aktueller Lesekopf (Frame-Index)
    private var nextPos = -1     // zweiter Lesekopf während des Überblendens
    private var remaining = 0    // Frames bis zur nächsten Überblendung
    private var xfadePos = 0
    private val xfadeFrames = (crossfade * sr).toInt()

    private fun start(p: Pcm) {
        pcm = p
        frames = p.data.size / 2
        pos = pickStart(-1)
        remaining = segmentFrom(pos)
    }

    /** Zufällige Startstelle, möglichst weit weg von der aktuellen. */
    private fun pickStart(avoid: Int): Int {
        val maxStart = (frames - (minSegment + crossfade) * sr).toInt().coerceAtLeast(1)
        var best = rng.int(maxStart)
        if (avoid >= 0) {
            repeat(6) {
                if (abs(best - avoid) > 6 * sr) return best
                best = rng.int(maxStart)
            }
        }
        return best
    }

    private fun segmentFrom(p: Int): Int {
        val wanted = (rng.range(minSegment, maxSegment) * sr).toInt()
        // so begrenzen, dass die Überblendung noch vor dem Dateiende fertig ist
        return minOf(wanted, frames - p - xfadeFrames - 1).coerceAtLeast(1)
    }

    override fun render(left: FloatArray, right: FloatArray, frames: Int) {
        val p = pcm ?: samples.get(name)?.also { start(it) }
        if (p == null) {
            // Aufnahme wird noch geladen
            java.util.Arrays.fill(left, 0, frames, 0f)
            java.util.Arrays.fill(right, 0, frames, 0f)
            return
        }
        val d = p.data
        val scale = 1f / 32768f
        for (i in 0 until frames) {
            var l = d[pos * 2] * scale
            var r = d[pos * 2 + 1] * scale
            pos++

            if (nextPos >= 0) {
                // gleichmäßige Leistung: cos/sin-Überblendung
                val t = xfadePos.toFloat() / xfadeFrames
                val gOut = cos(t * PI.toFloat() / 2f)
                val gIn = sin(t * PI.toFloat() / 2f)
                l = l * gOut + d[nextPos * 2] * scale * gIn
                r = r * gOut + d[nextPos * 2 + 1] * scale * gIn
                nextPos++
                if (++xfadePos >= xfadeFrames) {
                    pos = nextPos
                    nextPos = -1
                    remaining = segmentFrom(pos)
                }
            } else if (--remaining <= 0) {
                nextPos = pickStart(pos)
                xfadePos = 0
            }
            left[i] = l
            right[i] = r
        }
    }
}
