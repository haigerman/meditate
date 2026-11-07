package io.github.haigerman.meditate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import io.github.haigerman.meditate.data.MixRepository
import io.github.haigerman.meditate.ui.MeditateTheme
import io.github.haigerman.meditate.ui.MixerScreen

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* egal – Wiedergabe läuft auch ohne */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MixRepository.init(this)
        enableEdgeToEdge()
        setContent {
            MeditateTheme {
                MixerScreen(onPlayPause = ::togglePlayback)
            }
        }
    }

    private fun togglePlayback() {
        if (MixRepository.state.value.playing) {
            MixRepository.setPlaying(false)
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            PlaybackService.play(this)
        }
    }
}
