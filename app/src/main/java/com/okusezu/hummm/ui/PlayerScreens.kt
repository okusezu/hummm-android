package com.okusezu.hummm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MiniPlayer(vm: AppViewModel) {
    val t = vm.current ?: return
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().clickable { vm.playerPage = PlayerPage.NOW_PLAYING; vm.playerOpen = true },
    ) {
        Column {
            val frac = if (vm.durationMs > 0) vm.positionMs.toFloat() / vm.durationMs else 0f
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(2.dp), strokeCap = StrokeCap.Butt, gapSize = 0.dp, drawStopIndicator = {})
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(t.thumbnail, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WaveLogo(live = vm.isPlaying, width = 32.dp, height = 14.dp)
                IconButton(onClick = vm::togglePlay) {
                    if (vm.buffering && !vm.isPlaying) Spinner()
                    else Icon(if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (vm.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = vm::next) { Icon(Icons.Default.SkipNext, "Next") }
            }
        }
    }
}
