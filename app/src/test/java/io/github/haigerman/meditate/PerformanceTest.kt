package io.github.haigerman.meditate

import io.github.haigerman.meditate.audio.SynthMixer
import io.github.haigerman.meditate.audio.SynthSound
import org.junit.Test

class PerformanceTest {
    @Test
    fun allSoundsRealtimeFactor() {
        val sr = 48000
        val mixer = SynthMixer(sr)
        mixer.setMasterImmediate(1f)
        SynthSound.entries.forEach { mixer.setVolume(it, 1f) }
        val buf = FloatArray(2048)
        repeat(200) { mixer.render(buf, 1024) } // Aufwärmen
        val seconds = 60
        val t0 = System.nanoTime()
        repeat(seconds * sr / 1024) { mixer.render(buf, 1024) }
        val ms = (System.nanoTime() - t0) / 1e6
        println("Alle Klänge, ${seconds}s Audio in %.0f ms → %.0fx Echtzeit".format(ms, seconds * 1000 / ms))
    }
}
