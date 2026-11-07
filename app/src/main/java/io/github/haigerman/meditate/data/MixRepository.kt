package io.github.haigerman.meditate.data

import android.content.Context
import android.content.SharedPreferences
import io.github.haigerman.meditate.audio.SynthSound
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface SoundSource {
    data class Synth(val sound: SynthSound) : SoundSource
    data class Asset(val path: String) : SoundSource
}

data class SoundItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val emoji: String,
    val source: SoundSource,
)

data class MixState(
    val sounds: List<SoundItem> = emptyList(),
    val enabled: Set<String> = emptySet(),
    val volumes: Map<String, Float> = emptyMap(),
    val playing: Boolean = false,
    /** 0 = kein Timer */
    val timerMinutes: Int = 0,
    /** System.currentTimeMillis(), wann der Timer abläuft; 0 = keiner aktiv */
    val timerEndsAt: Long = 0L,
) {
    fun volume(id: String): Float = volumes[id] ?: DEFAULT_VOLUME
    fun isOn(id: String): Boolean = id in enabled

    companion object {
        const val DEFAULT_VOLUME = 0.7f
    }
}

/** Zentrale, app-weite Quelle für den Mixer-Zustand – geteilt von UI und Service. */
object MixRepository {
    private val _state = MutableStateFlow(MixState())
    val state: StateFlow<MixState> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("mix", Context.MODE_PRIVATE)
        prefs = p
        val sounds = SoundCatalog.load(context)
        val first = !p.contains("enabled")
        val enabled = if (first) setOf("forest", "brook") else p.getStringSet("enabled", emptySet())!!.toSet()
        val volumes = sounds.associate { it.id to p.getFloat("vol_${it.id}", MixState.DEFAULT_VOLUME) }
        _state.value = MixState(
            sounds = sounds,
            enabled = enabled,
            volumes = volumes,
            timerMinutes = p.getInt("timer", 0),
        )
    }

    fun toggle(id: String) {
        _state.update { s -> s.copy(enabled = if (id in s.enabled) s.enabled - id else s.enabled + id) }
        save()
    }

    fun setVolume(id: String, v: Float) {
        _state.update { it.copy(volumes = it.volumes + (id to v)) }
        save()
    }

    fun setPlaying(playing: Boolean) {
        _state.update { s ->
            if (s.playing == playing) s
            else s.copy(
                playing = playing,
                timerEndsAt = if (playing && s.timerMinutes > 0) System.currentTimeMillis() + s.timerMinutes * 60_000L else 0L,
            )
        }
    }

    fun setTimer(minutes: Int) {
        _state.update { s ->
            s.copy(
                timerMinutes = minutes,
                timerEndsAt = if (s.playing && minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L,
            )
        }
        save()
    }

    private fun save() {
        val s = _state.value
        prefs?.edit()?.apply {
            putStringSet("enabled", s.enabled)
            s.volumes.forEach { (k, v) -> putFloat("vol_$k", v) }
            putInt("timer", s.timerMinutes)
            apply()
        }
    }
}

object SoundCatalog {
    fun load(context: Context): List<SoundItem> {
        val builtIn = listOf(
            SoundItem("campfire", "Lagerfeuer", "Knistern & Flammen", "🔥", SoundSource.Synth(SynthSound.CAMPFIRE)),
            SoundItem("brook", "Wasser", "Plätschernder Gebirgsbach", "💧", SoundSource.Synth(SynthSound.BROOK)),
            SoundItem("wind", "Wind", "Sanfte Böen", "🌬️", SoundSource.Synth(SynthSound.WIND)),
            SoundItem("chimes", "Windspiel", "Pentatonische Röhren", "🎐", SoundSource.Synth(SynthSound.WIND_CHIMES)),
            SoundItem("bowls", "Klangschalen", "Tiefe, schwebende Töne", "🔔", SoundSource.Synth(SynthSound.SINGING_BOWLS)),
            SoundItem("forest", "Wald", "Vogelgezwitscher", "🌲", SoundSource.Synth(SynthSound.FOREST)),
            SoundItem("spheres", "Sphären", "Schwebende Klangflächen", "✨", SoundSource.Synth(SynthSound.SPHERES)),
        )
        val tracks = (context.assets.list("tracks") ?: emptyArray())
            .filter { it.endsWith(".mp3", ignoreCase = true) }
            .sorted()
            .map { file ->
                val name = file.substringBeforeLast('.').replace('_', ' ').trim()
                    .replaceFirstChar { it.uppercase() }
                SoundItem("track:$file", name, "Aufnahme", "🎵", SoundSource.Asset("tracks/$file"))
            }
        return builtIn + tracks
    }
}
