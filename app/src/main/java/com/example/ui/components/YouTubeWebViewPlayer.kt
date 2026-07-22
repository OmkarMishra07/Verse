package com.example.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.ui.viewmodel.MusicPlayerViewModel

// ─────────────────────────────────────────────────────────────────────────────
//  Main embedded YouTube player — wraps the singleton WebViewHolder
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeWebViewPlayer(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Ensure the singleton WebView is initialized with this ViewModel
    LaunchedEffect(Unit) {
        com.example.WebViewHolder.init(context, viewModel)
    }

    val track by viewModel.currentTrack.collectAsState()
    val playTrigger by viewModel.playTrigger.collectAsState()

    // Load / switch video whenever the current track changes or a new play is triggered
    LaunchedEffect(track?.id, playTrigger) {
        val id = track?.id ?: return@LaunchedEffect
        com.example.WebViewHolder.init(context, viewModel) // Ensure it's initialized before trying to play
        val startSecs = (viewModel.currentPositionMs.value / 1000).toInt()
        com.example.WebViewHolder.loadVideo(id, startSecs, viewModel.isPlaying.value)
    }

    // Sync play / pause state to the WebView
    val playing by viewModel.isPlaying.collectAsState()
    LaunchedEffect(playing) {
        if (playing) com.example.WebViewHolder.play()
        else com.example.WebViewHolder.pause()
    }

    // Sync volume
    val vol by viewModel.volume.collectAsState()
    LaunchedEffect(vol) {
        com.example.WebViewHolder.setVolume((vol * 100).toInt())
    }

    // Sync seek request
    val seekRequest by viewModel.seekRequestMs.collectAsState()
    LaunchedEffect(seekRequest) {
        val ms = seekRequest ?: return@LaunchedEffect
        com.example.WebViewHolder.seekTo(ms / 1000f)
        viewModel.clearSeekRequest()
    }

    val webView = com.example.WebViewHolder.getWebView()
    if (webView != null) {
        AndroidView(factory = { webView }, modifier = modifier)
    }
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
            .padding(horizontal = 24.dp, vertical = 8.dp) // Match bottom nav side padding
            .shadow(8.dp, RoundedCornerShape(28.dp), spotColor = Color.Black.copy(alpha = 0.6f)) // Floating shadow
            .height(56.dp) // Slightly shorter for a lighter feel
            .clip(RoundedCornerShape(28.dp)) // Pill-shape to match bottom nav
            .background(Color(0xB3000000)) // Frosted glass look
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
        coil.compose.AsyncImage(
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
                imageVector        = if (isPlaying) androidx.compose.material.icons.Icons.Default.Pause else androidx.compose.material.icons.Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint               = Color.White,
                modifier           = Modifier.size(26.dp)
            )
        }
        IconButton(onClick = { viewModel.playNext() }) {
            Icon(
                imageVector        = androidx.compose.material.icons.Icons.Default.SkipNext,
                contentDescription = "Next",
                tint               = Color.White.copy(alpha = 0.7f),
                modifier           = Modifier.size(22.dp)
            )
        }
    }
}
