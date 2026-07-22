package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.viewmodel.MusicPlayerViewModel

/**
 * No-op composable — kept for API compatibility.
 * Previously rendered a YouTube IFrame WebView. Now that playback uses ExoPlayer
 * with direct audio streams, no WebView rendering is needed.
 */
@Composable
fun YouTubeWebViewPlayer(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
    // Intentionally empty — ExoPlayer handles all playback natively.
}

// ─────────────────────────────────────────────────────────────────────────────
//  Mini Player bar — shown at the bottom when browsing other screens.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MiniYouTubePlayerBar(viewModel: MusicPlayerViewModel) {
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying    by viewModel.isPlaying.collectAsState()
    val jammingRoomId by viewModel.jammingRoomId.collectAsState()

    if (currentTrack == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .shadow(8.dp, RoundedCornerShape(28.dp), spotColor = Color.Black.copy(alpha = 0.6f))
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xB3000000))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(28.dp))
            .clickable { 
                if (jammingRoomId.isNotBlank()) {
                    viewModel.setScreen(com.example.ui.viewmodel.ScreenType.JAMMING)
                } else {
                    viewModel.setScreen(com.example.ui.viewmodel.ScreenType.NOW_PLAYING)
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model              = currentTrack?.thumbnailUrl,
            contentDescription = null,
            contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
            modifier           = Modifier
                .size(44.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = currentTrack?.title ?: "",
                color      = Color.White,
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines   = 1,
                overflow   = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text     = if (jammingRoomId.isNotBlank()) "Playing in room $jammingRoomId" else (currentTrack?.artist ?: ""),
                color    = if (jammingRoomId.isNotBlank()) Color(0xFF33B5E5) else Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = { viewModel.togglePlayback() }) {
            Icon(
                imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint               = Color.White,
                modifier           = Modifier.size(26.dp)
            )
        }
        IconButton(onClick = { viewModel.playNext() }) {
            Icon(
                imageVector        = Icons.Default.SkipNext,
                contentDescription = "Next",
                tint               = Color.White.copy(alpha = 0.7f),
                modifier           = Modifier.size(22.dp)
            )
        }
    }
}
