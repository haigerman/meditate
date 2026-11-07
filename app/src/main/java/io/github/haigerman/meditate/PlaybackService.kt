package io.github.haigerman.meditate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.haigerman.meditate.audio.AudioEngine
import io.github.haigerman.meditate.data.MixRepository
import io.github.haigerman.meditate.data.MixState
import io.github.haigerman.meditate.data.SoundSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Vordergrund-Dienst: spielt die Mischung weiter, auch wenn die App geschlossen
 * oder der Bildschirm aus ist.
 */
class PlaybackService : LifecycleService() {

    companion object {
        const val ACTION_PLAY = "io.github.haigerman.meditate.PLAY"
        const val ACTION_PAUSE = "io.github.haigerman.meditate.PAUSE"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 50L
        private const val TRACK_FADE_SECONDS = 2.5f
        private const val TIMER_FADE_MS = 30_000L

        fun play(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY),
            )
        }
    }

    private val engine = AudioEngine()
    private val players = mutableMapOf<String, ExoPlayer>()
    private var trackMaster = 0f
    private var playing = false
    private var foreground = false
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pausedByFocusLoss = false
    private var lastEnabled: Set<String> = emptySet()

    private lateinit var audioManager: AudioManager
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        pausedByFocusLoss = false
                        MixRepository.setPlaying(false)
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (playing) pausedByFocusLoss = true
                        MixRepository.setPlaying(false)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (pausedByFocusLoss) {
                            pausedByFocusLoss = false
                            play(this)
                        }
                    }
                }
            }
            .build()
    }

    /** Kopfhörer abgezogen → pausieren, damit nicht plötzlich der Lautsprecher losgeht. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            MixRepository.setPlaying(false)
        }
    }

    override fun onCreate() {
        super.onCreate()
        MixRepository.init(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createChannel()
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            MixRepository.state.collect { apply(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY -> {
                goForeground(true)
                MixRepository.setPlaying(true)
            }
            ACTION_PAUSE -> {
                MixRepository.setPlaying(false)
                if (ticker?.isActive != true) stopSelf()
            }
            else -> if (!playing) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun apply(s: MixState) {
        // Lautstärken für die Synth-Klänge
        for (item in s.sounds) {
            val src = item.source
            if (src is SoundSource.Synth) {
                engine.mixer.setVolume(src.sound, if (s.isOn(item.id)) s.volume(item.id) else 0f)
            }
        }
        if (s.playing && !playing) startPlayback()
        if (!s.playing && playing) stopPlayback()
        updateTrackVolumes(s)
        if (playing && s.enabled != lastEnabled) goForeground(true) // Text aktualisieren
        lastEnabled = s.enabled
    }

    private fun startPlayback() {
        playing = true
        audioManager.requestAudioFocus(focusRequest)
        acquireWakeLock()
        engine.start()
        goForeground(true)
        startTicker()
    }

    private fun stopPlayback() {
        playing = false
        engine.fadeOutAndStop()
        if (!pausedByFocusLoss) audioManager.abandonAudioFocusRequest(focusRequest)
        startTicker() // Ausblenden der MP3s läuft im Ticker weiter
        goForeground(false)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = lifecycleScope.launch {
            while (isActive) {
                val s = MixRepository.state.value
                var factor = if (playing) 1f else 0f
                if (playing && s.timerEndsAt > 0) {
                    val remaining = s.timerEndsAt - System.currentTimeMillis()
                    if (remaining <= 0) {
                        MixRepository.setPlaying(false)
                        factor = 0f
                    } else if (remaining < TIMER_FADE_MS) {
                        // sanftes, langes Ausblenden in den letzten 30 Sekunden
                        val x = remaining.toFloat() / TIMER_FADE_MS
                        factor = x * x
                    }
                }
                if (playing) engine.mixer.masterTarget = factor

                val step = TICK_MS / 1000f / TRACK_FADE_SECONDS
                trackMaster = if (trackMaster < factor) minOf(factor, trackMaster + step) else maxOf(factor, trackMaster - step)
                updateTrackVolumes(s)

                if (!playing && trackMaster <= 0f && !engine.isRunning) {
                    onFullyStopped()
                    break
                }
                delay(TICK_MS)
            }
        }
    }

    private fun onFullyStopped() {
        players.values.forEach { it.pause() }
        releaseWakeLock()
        // Benachrichtigung bleibt (wegwischbar) zum Fortsetzen stehen; Dienst darf enden.
        stopSelf()
    }

    private fun updateTrackVolumes(s: MixState) {
        for (item in s.sounds) {
            val src = item.source as? SoundSource.Asset ?: continue
            val on = s.isOn(item.id)
            val v = s.volume(item.id)
            val target = if (on) v * v * trackMaster else 0f
            val existing = players[item.id]
            if (target > 0f) {
                val p = existing ?: createPlayer(src.path).also { players[item.id] = it }
                p.volume = target
                if (!p.isPlaying) p.play()
            } else if (existing != null) {
                existing.volume = 0f
                existing.pause()
            }
        }
    }

    private fun createPlayer(path: String): ExoPlayer =
        ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false,
            )
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri("asset:///$path"))
            volume = 0f
            prepare()
        }

    // ---------- Benachrichtigung ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Wiedergabe", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(isPlaying: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val action = if (isPlaying) {
            NotificationCompat.Action(
                0, "Pause",
                PendingIntent.getService(
                    this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_PAUSE),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            NotificationCompat.Action(
                0, "Fortsetzen",
                PendingIntent.getForegroundService(
                    this, 2, Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val s = MixRepository.state.value
        val names = s.sounds.filter { s.isOn(it.id) }.joinToString(" · ") { it.title }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meditate)
            .setContentTitle(if (isPlaying) "Meditation läuft" else "Pausiert")
            .setContentText(names.ifEmpty { "Keine Klänge gewählt" })
            .setContentIntent(open)
            .setOngoing(isPlaying)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action)
            .build()
    }

    private fun goForeground(isPlaying: Boolean) {
        val n = buildNotification(isPlaying)
        if (isPlaying) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
            foreground = true
        } else {
            if (foreground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                foreground = false
            }
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meditate:playback").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        engine.release()
        players.values.forEach { it.release() }
        players.clear()
        releaseWakeLock()
        if (playing) MixRepository.setPlaying(false)
        super.onDestroy()
    }
}
