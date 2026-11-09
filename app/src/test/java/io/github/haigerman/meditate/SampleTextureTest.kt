package io.github.haigerman.meditate

import io.github.haigerman.meditate.audio.Pcm
import io.github.haigerman.meditate.audio.SampleProvider
import io.github.haigerman.meditate.audio.sounds.SampleTexture
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prüft, dass sich beim endlosen Abspielen nichts kurz hintereinander wiederholt. */
class SampleTextureTest {
    private val sr = 48000

    private fun minRepeatGapSeconds(fileSeconds: Int, make: (SampleProvider) -> SampleTexture): Double {
        val pcm = Pcm(ShortArray(fileSeconds * sr * 2), sr)
        val tex = make(SampleProvider { pcm })
        // (Start in Datei, Ende in Datei, Zeitpunkt in der Wiedergabe)
        val log = mutableListOf<LongArray>()
        var played = 0L
        tex.segmentLog = { s, e -> log += longArrayOf(s.toLong(), e.toLong(), played) }
        val l = FloatArray(1024)
        val r = FloatArray(1024)
        repeat(20 * 60 * sr / 1024) {
            tex.render(l, r, 1024)
            played += 1024
        }
        var minGap = Double.MAX_VALUE
        for (i in log.indices) for (j in i + 1 until log.size) {
            val a = log[i]
            val b = log[j]
            val oStart = maxOf(a[0], b[0])
            val oEnd = minOf(a[1], b[1])
            if (oEnd - oStart > sr) { // mehr als 1 s identisches Material
                // wann wurde dieses Material jeweils gehört? (Abschnitt endet bei Zeitpunkt [2])
                val x = (oStart + oEnd) / 2
                val ta = a[2] - (a[1] - x)
                val tb = b[2] - (b[1] - x)
                val g = (tb - ta).toDouble() / sr
                minGap = minOf(minGap, g)
            }
        }
        println("Datei ${fileSeconds}s, ${log.size} Abschnitte in 20 min, früheste Wiederholung nach %.0f s".format(minGap))
        return minGap
    }

    @Test
    fun forestDoesNotRepeatSoon() {
        val gap = minRepeatGapSeconds(152) {
            SampleTexture(sr, it, "forest", minSegment = 20f, maxSegment = 45f, crossfade = 4f, seed = 131L)
        }
        assertTrue(gap > 60)
    }

    @Test
    fun brookDoesNotRepeatSoon() {
        val gap = minRepeatGapSeconds(42) { SampleTexture(sr, it, "brook", minSegment = 8f, maxSegment = 16f, crossfade = 3f) }
        assertTrue(gap > 18)
    }
}
