package io.github.haigerman.meditate

import io.github.haigerman.meditate.audio.SynthMixer
import io.github.haigerman.meditate.audio.SynthSound
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Rendert jeden Klang offline, prüft auf NaN/Übersteuerung und legt WAV-Vorschauen
 * unter app/build/sound-previews ab (zum Reinhören ohne Handy).
 */
class SoundRenderTest {
    private val sr = 44100
    private val seconds = (System.getProperty("previewSeconds") ?: "20").toInt()

    private fun render(volumes: Map<SynthSound, Float>): FloatArray {
        val mixer = SynthMixer(sr)
        mixer.setMasterImmediate(1f)
        volumes.forEach { (s, v) -> mixer.setVolume(s, v) }
        mixer.rampSeconds = 0.01f
        val frames = sr * seconds
        val out = FloatArray(frames * 2)
        val chunk = FloatArray(4096 * 2)
        var pos = 0
        while (pos < frames) {
            val n = minOf(4096, frames - pos)
            mixer.render(chunk, n)
            System.arraycopy(chunk, 0, out, pos * 2, n * 2)
            pos += n
        }
        return out
    }

    private fun stats(name: String, buf: FloatArray): Pair<Double, Double> {
        var sum = 0.0
        var peak = 0.0
        for (x in buf) {
            assertTrue("$name enthält NaN", !x.isNaN())
            sum += x * x
            peak = maxOf(peak, abs(x).toDouble())
        }
        val rms = sqrt(sum / buf.size)
        println("%-14s RMS %6.1f dBFS   Peak %6.1f dBFS".format(name, 20 * log10(rms + 1e-12), 20 * log10(peak + 1e-12)))
        return rms to peak
    }

    private fun writeWav(file: File, buf: FloatArray) {
        file.parentFile.mkdirs()
        DataOutputStream(FileOutputStream(file).buffered()).use { o ->
            fun i32(v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }
            fun i16(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
            val data = buf.size * 2
            o.writeBytes("RIFF"); i32(36 + data); o.writeBytes("WAVE")
            o.writeBytes("fmt "); i32(16); i16(1); i16(2); i32(sr); i32(sr * 4); i16(4); i16(16)
            o.writeBytes("data"); i32(data)
            for (x in buf) i16((x.coerceIn(-1f, 1f) * 32767).toInt())
        }
    }

    @Test
    fun renderEachSound() {
        val dir = File("build/sound-previews")
        for (s in SynthSound.entries) {
            val buf = render(mapOf(s to 1f))
            val (rms, _) = stats(s.name, buf)
            assertTrue("${s.name} ist stumm", rms > 0.001)
            writeWav(File(dir, "${s.name.lowercase()}.wav"), buf)
        }
        val mix = render(mapOf(SynthSound.CAMPFIRE to 0.7f, SynthSound.FOREST to 0.8f, SynthSound.BROOK to 0.5f, SynthSound.SINGING_BOWLS to 0.6f))
        stats("MIX", mix)
        writeWav(File(dir, "mix.wav"), mix)
    }
}
