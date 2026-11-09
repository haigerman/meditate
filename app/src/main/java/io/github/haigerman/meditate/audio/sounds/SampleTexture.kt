package io.github.haigerman.meditate.audio.sounds

import io.github.haigerman.meditate.audio.Pcm
import io.github.haigerman.meditate.audio.SampleProvider
import io.github.haigerman.meditate.audio.dsp.Rng
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spielt eine echte Aufnahme endlos ab, ohne dass man eine Schleife erkennt:
 * Alle [minSegment]–[maxSegment] Sekunden springt die Wiedergabe an eine andere Stelle
 * und blendet dabei über [crossfade] Sekunden gleichmäßig über.
 *
 * Für jede Sekunde der Aufnahme wird gemerkt, wann sie zuletzt zu hören war. Gesprungen
 * wird bevorzugt dorthin, wo am längsten nichts gespielt wurde – so wiederholt sich
 * nichts kurz hintereinander, und trotzdem ist die Reihenfolge jedes Mal anders.
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
    private var segStart = 0     // wo der aktuelle Abschnitt begonnen hat
    private var nextPos = -1     // zweiter Lesekopf während des Überblendens
    private var remaining = 0    // Frames bis zur nächsten Überblendung
    private var xfadePos = 0
    private val xfadeFrames = (crossfade * sr).toInt()

    private var clock = 0L                      // abgespielte Frames insgesamt
    private var lastPlayed = LongArray(0)       // je Sekunde der Aufnahme: wann zuletzt gehört
    private var segLimitSec = 0                 // nächster Abschnitt endet spätestens vor dieser Sekunde

    /** Nur für Tests: protokolliert jeden gespielten Abschnitt (Start, Ende in der Datei). */
    internal var segmentLog: ((start: Int, end: Int) -> Unit)? = null

    private val secs get() = lastPlayed.size

    private fun start(p: Pcm) {
        pcm = p
        frames = p.data.size / 2
        lastPlayed = LongArray(frames / sr) { NEVER }
        pos = pickStart()
        segStart = pos
        remaining = segmentLength(pos)
    }

    /** Wie lange (in Frames) Sekunde [s] nicht mehr zu hören war. */
    private fun staleness(s: Int): Long = if (lastPlayed[s] == NEVER) FOREVER else clock - lastPlayed[s]

    /**
     * Sucht alle möglichen Startsekunden ab und wählt zufällig eine von denen, deren
     * Mindestabschnitt am längsten nicht gespielt wurde.
     */
    private fun pickStart(): Int {
        val windowSec = (minSegment + crossfade).toInt() + 1
        val lastStart = (secs - windowSec - 1).coerceAtLeast(0)
        val score = LongArray(lastStart + 1)
        var best = 0L
        for (s in 0..lastStart) {
            var m = Long.MAX_VALUE
            for (k in s until minOf(secs, s + windowSec)) m = minOf(m, staleness(k))
            score[s] = m
            if (m > best) best = m
        }
        // alle Kandidaten, die fast so "alt" sind wie der beste – davon einer zufällig
        val threshold = best - best / 5
        var count = 0
        for (v in score) if (v >= threshold) count++
        var pick = rng.int(maxOf(count, 1))
        var chosen = 0
        for (s in score.indices) {
            if (score[s] >= threshold && pick-- == 0) {
                chosen = s
                break
            }
        }
        // den Abschnitt beenden, bevor er in deutlich kürzlicher Gehörtes hineinläuft
        segLimitSec = secs
        for (k in chosen + windowSec until secs) {
            if (staleness(k) < threshold / 2) {
                segLimitSec = k
                break
            }
        }
        return chosen * sr + rng.int(sr)
    }

    /** Länge des Abschnitts ab [p] – zufällig, aber vor Dateiende und Sperrbereich. */
    private fun segmentLength(p: Int): Int {
        val wanted = (rng.range(minSegment, maxSegment) * sr).toInt()
        val limit = minOf(frames, segLimitSec * sr) - p - xfadeFrames - 1
        return minOf(wanted, limit).coerceAtLeast(1)
    }

    private fun mark(framePos: Int) {
        val s = framePos / sr
        if (s < secs) lastPlayed[s] = clock
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
            mark(pos)
            pos++

            if (nextPos >= 0) {
                // gleichmäßige Leistung: cos/sin-Überblendung
                val t = xfadePos.toFloat() / xfadeFrames
                val gOut = cos(t * PI.toFloat() / 2f)
                val gIn = sin(t * PI.toFloat() / 2f)
                l = l * gOut + d[nextPos * 2] * scale * gIn
                r = r * gOut + d[nextPos * 2 + 1] * scale * gIn
                mark(nextPos)
                nextPos++
                if (++xfadePos >= xfadeFrames) {
                    pos = nextPos
                    segStart = pos - xfadeFrames
                    nextPos = -1
                    remaining = segmentLength(pos)
                }
            } else if (--remaining <= 0) {
                segmentLog?.invoke(segStart, pos + xfadeFrames)
                // was während des Ausblendens noch läuft, zählt schon als gehört –
                // sonst könnte direkt dahinter angesetzt werden (gleiches Material doppelt)
                var f = pos
                while (f < pos + xfadeFrames + sr) {
                    mark(f)
                    f += sr
                }
                nextPos = pickStart()
                xfadePos = 0
            }
            clock++
            left[i] = l
            right[i] = r
        }
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val FOREVER = Long.MAX_VALUE / 4
    }
}
