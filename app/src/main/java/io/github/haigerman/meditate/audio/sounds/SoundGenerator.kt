package io.github.haigerman.meditate.audio.sounds

/**
 * Ein endlos laufender, prozedural erzeugter Klang. Es gibt keine Schleife im eigentlichen
 * Sinn – jeder Generator erzeugt fortlaufend neues, nie identisch wiederholtes Material.
 */
interface SoundGenerator {
    /** Überschreibt left/right (jeweils mindestens [frames] lang) mit neuen Samples. */
    fun render(left: FloatArray, right: FloatArray, frames: Int)
}
