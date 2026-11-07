package io.github.haigerman.meditate.audio

import io.github.haigerman.meditate.audio.sounds.Brook
import io.github.haigerman.meditate.audio.sounds.Campfire
import io.github.haigerman.meditate.audio.sounds.Forest
import io.github.haigerman.meditate.audio.sounds.SingingBowls
import io.github.haigerman.meditate.audio.sounds.SoundGenerator
import io.github.haigerman.meditate.audio.sounds.Wind
import io.github.haigerman.meditate.audio.sounds.WindChimes
import kotlin.math.tanh

/** Die eingebauten, synthetisch erzeugten Klänge. */
/** [calibration] gleicht die Grundlautstärken einander an (gemessen im SoundRenderTest). */
enum class SynthSound(val calibration: Float) {
    CAMPFIRE(0.38f),
    BROOK(1.4f),
    WIND(0.14f),
    WIND_CHIMES(0.9f),
    SINGING_BOWLS(2.0f),
    FOREST(1.3f);

    fun create(sampleRate: Int): SoundGenerator = when (this) {
        CAMPFIRE -> Campfire(sampleRate)
        BROOK -> Brook(sampleRate)
        WIND -> Wind(sampleRate)
        WIND_CHIMES -> WindChimes(sampleRate)
        SINGING_BOWLS -> SingingBowls(sampleRate)
        FOREST -> Forest(sampleRate)
    }
}

/**
 * Mischt die Generatoren mit weich geglätteten Lautstärken (keine Knackser beim
 * Ein-/Ausschalten) und einem sanften Limiter am Ende. Rein in Kotlin, ohne Android –
 * so kann es auch in Unit-Tests offline gerendert werden.
 */
class SynthMixer(private val sampleRate: Int, private val blockSize: Int = 1024) {
    private val sounds = SynthSound.entries
    private val generators = arrayOfNulls<SoundGenerator>(sounds.size)
    private val target = FloatArray(sounds.size)
    private val current = FloatArray(sounds.size)

    @Volatile
    var masterTarget = 1f
    private var master = 0f

    /** Dauer für eine volle Lautstärkeänderung 0 → 1 in Sekunden. */
    var rampSeconds = 1.5f
    var masterRampSeconds = 2.5f

    private val tmpL = FloatArray(blockSize)
    private val tmpR = FloatArray(blockSize)

    /** Lautstärke 0..1 (wird intern quadratisch für natürlicheres Empfinden). */
    fun setVolume(sound: SynthSound, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        target[sound.ordinal] = v * v
    }

    /** Setzt die Hauptlautstärke sofort (z. B. 0 vor dem Einblenden). */
    fun setMasterImmediate(v: Float) {
        master = v
        masterTarget = v
    }

    val isSilent: Boolean
        get() = master <= 0.0001f && masterTarget <= 0.0001f

    /** Rendert [frames] Stereo-Frames interleaved (L, R, L, R …) in [out]. */
    fun render(out: FloatArray, frames: Int) {
        java.util.Arrays.fill(out, 0, frames * 2, 0f)
        var offset = 0
        while (offset < frames) {
            val n = minOf(blockSize, frames - offset)
            renderBlock(out, offset, n)
            offset += n
        }
    }

    private fun renderBlock(out: FloatArray, offset: Int, n: Int) {
        val step = n / (rampSeconds * sampleRate)
        for (s in sounds.indices) {
            val from = current[s]
            val to = approach(from, target[s], step)
            current[s] = to
            if (from <= 0f && to <= 0f) continue
            val gen = generators[s] ?: sounds[s].create(sampleRate).also { generators[s] = it }
            gen.render(tmpL, tmpR, n)
            val cal = sounds[s].calibration
            for (i in 0 until n) {
                val g = (from + (to - from) * (i.toFloat() / n)) * cal
                out[(offset + i) * 2] += tmpL[i] * g
                out[(offset + i) * 2 + 1] += tmpR[i] * g
            }
        }

        val mFrom = master
        val mTo = approach(mFrom, masterTarget, n / (masterRampSeconds * sampleRate))
        master = mTo
        for (i in 0 until n) {
            val m = mFrom + (mTo - mFrom) * (i.toFloat() / n)
            val idx = (offset + i) * 2
            out[idx] = softClip(out[idx] * m)
            out[idx + 1] = softClip(out[idx + 1] * m)
        }
    }

    private fun approach(from: Float, to: Float, step: Float): Float =
        if (from < to) minOf(to, from + step) else maxOf(to, from - step)

    private fun softClip(x: Float): Float =
        if (x > -0.6f && x < 0.6f) x else (0.6f + 0.4f * tanh((kotlin.math.abs(x) - 0.6f) / 0.4f)) * kotlin.math.sign(x)
}
