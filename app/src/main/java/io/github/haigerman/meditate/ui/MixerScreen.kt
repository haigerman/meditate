package io.github.haigerman.meditate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.haigerman.meditate.data.MixRepository
import io.github.haigerman.meditate.data.MixState
import io.github.haigerman.meditate.data.SoundItem
import kotlinx.coroutines.delay

private val timerOptions = listOf(0, 10, 20, 30, 45, 60, 90)

@Composable
fun MixerScreen(onPlayPause: () -> Unit) {
    val state by MixRepository.state.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Night, DeepTeal))),
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(Modifier.statusBarsPadding())
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.sounds, key = { it.id }) { item ->
                    SoundCard(
                        item = item,
                        on = state.isOn(item.id),
                        volume = state.volume(item.id),
                        onToggle = { MixRepository.toggle(item.id) },
                        onVolume = { MixRepository.setVolume(item.id, it) },
                    )
                }
            }
            BottomPanel(state, onPlayPause, Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun Header(modifier: Modifier = Modifier) {
    Column(modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
        Text("Meditate", fontSize = 30.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
        Text(
            "Wähle die Klänge für deine Mischung",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SoundCard(
    item: SoundItem,
    on: Boolean,
    volume: Float,
    onToggle: () -> Unit,
    onVolume: (Float) -> Unit,
) {
    val bg by animateColorAsState(if (on) CardOn else Card.copy(alpha = 0.7f), label = "bg")
    Surface(
        color = bg,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (on) Sand.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(item.emoji, fontSize = 22.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
                    Text(item.subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = on,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Night,
                        checkedTrackColor = Sand,
                    ),
                )
            }
            AnimatedVisibility(
                visible = on,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("🔈", fontSize = 13.sp)
                    Slider(
                        value = volume,
                        onValueChange = onVolume,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Sand,
                            activeTrackColor = Sand,
                            inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                        ),
                    )
                    Text("🔊", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun BottomPanel(state: MixState, onPlayPause: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Night.copy(alpha = 0.85f))
            .padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Schlaf-Timer",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items(timerOptions) { m ->
                FilterChip(
                    selected = state.timerMinutes == m,
                    onClick = { MixRepository.setTimer(m) },
                    label = { Text(if (m == 0) "Aus" else "$m Min") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Sand.copy(alpha = 0.25f),
                        selectedLabelColor = Sand,
                    ),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PlayButton(playing = state.playing, onClick = onPlayPause)
        Spacer(Modifier.height(8.dp))
        TimerText(state)
    }
}

@Composable
private fun TimerText(state: MixState) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.timerEndsAt, state.playing) {
        while (state.playing && state.timerEndsAt > 0) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val text = when {
        state.playing && state.timerEndsAt > 0 -> {
            val rem = ((state.timerEndsAt - now) / 1000).coerceAtLeast(0)
            "Endet in %d:%02d".format(rem / 60, rem % 60)
        }
        state.playing -> "Läuft endlos"
        state.enabled.isEmpty() -> "Wähle mindestens einen Klang"
        else -> "Tippe zum Starten"
    }
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val breath = rememberInfiniteTransition(label = "breath")
    // langsames "Atmen" (4 s ein, 4 s aus), solange die Wiedergabe läuft
    val pulse by breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4000), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(
        Modifier
            .size(96.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            if (playing) {
                drawCircle(Mist.copy(alpha = 0.10f + 0.12f * pulse), radius = r * (0.82f + 0.18f * pulse))
            }
            drawCircle(Sand, radius = r * 0.68f)
            drawCircle(Sand.copy(alpha = 0.4f), radius = r * 0.78f, style = Stroke(width = 2.dp.toPx()))
            val c = center
            if (playing) {
                val w = r * 0.12f
                val h = r * 0.46f
                drawRoundRect(
                    Night, topLeft = Offset(c.x - w * 1.9f, c.y - h / 2),
                    size = androidx.compose.ui.geometry.Size(w * 1.2f, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                )
                drawRoundRect(
                    Night, topLeft = Offset(c.x + w * 0.7f, c.y - h / 2),
                    size = androidx.compose.ui.geometry.Size(w * 1.2f, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2),
                )
            } else {
                val s = r * 0.42f
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(c.x - s * 0.45f, c.y - s * 0.6f)
                    lineTo(c.x + s * 0.65f, c.y)
                    lineTo(c.x - s * 0.45f, c.y + s * 0.6f)
                    close()
                }
                drawPath(path, Night)
            }
        }
    }
}
