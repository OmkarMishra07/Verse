package com.example.ui.components

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
//  JavaScript → Kotlin bridge
// ─────────────────────────────────────────────────────────────────────────────
class YouTubePlayerBridge(
    private val onReady: () -> Unit,
    private val onStateChange: (Int) -> Unit,
    private val onTimeUpdate: (Float) -> Unit,
    private val onDuration: (Float) -> Unit,
    private val onError: (Int) -> Unit
) {
    @JavascriptInterface fun onPlayerReady()            = onReady()
    @JavascriptInterface fun onStateChange(state: Int) = onStateChange.invoke(state)
    @JavascriptInterface fun onTimeUpdate(timeStr: String) = onTimeUpdate.invoke(timeStr.toFloatOrNull() ?: 0f)
    @JavascriptInterface fun onVideoDuration(dStr: String) = onDuration.invoke(dStr.toFloatOrNull() ?: 0f)
    @JavascriptInterface fun onPlayerError(error: Int) = onError.invoke(error)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Official YouTube Iframe API HTML
// ─────────────────────────────────────────────────────────────────────────────
private fun buildYouTubeIframeApiHtml(videoId: String, startSeconds: Int, autoplay: Int): String = """
<!DOCTYPE html>
<html>
  <head>
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <style>
      body, html { width: 100%; height: 100%; margin: 0; padding: 0; background-color: #000; overflow: hidden; }
      #player { width: 100%; height: 100%; }
    </style>
  </head>
  <body>
    <div id="player"></div>
    <script>
      var tag = document.createElement('script');
      tag.src = "https://www.youtube.com/iframe_api";
      var firstScriptTag = document.getElementsByTagName('script')[0];
      firstScriptTag.parentNode.insertBefore(tag, firstScriptTag);

      var player;
      function onYouTubeIframeAPIReady() {
        player = new YT.Player('player', {
          height: '100%',
          width: '100%',
          videoId: '$videoId',
          playerVars: {
            'playsinline': 1,
            'autoplay': $autoplay,
            'controls': 1,
            'rel': 0,
            'modestbranding': 1,
            'fs': 0,
            'origin': 'https://localhost',
            'start': $startSeconds
          },
          events: {
            'onReady': onPlayerReady,
            'onStateChange': onPlayerStateChange,
            'onError': onPlayerError
          }
        });
      }

      function onPlayerReady(event) {
        if (window.AndroidPlayerBridge) {
            AndroidPlayerBridge.onPlayerReady();
        }
        event.target.playVideo();
        setInterval(function() {
          if (player && player.getCurrentTime && player.getPlayerState() == 1) {
            if (window.AndroidPlayerBridge) {
                AndroidPlayerBridge.onTimeUpdate(player.getCurrentTime().toString());
                AndroidPlayerBridge.onVideoDuration(player.getDuration().toString());
                if (typeof player.getVideoLoadedFraction === 'function') {
                    AndroidPlayerBridge.onVideoLoadedFraction(player.getVideoLoadedFraction().toString());
                }
            }
          }
        }, 500);
      }

      function onPlayerStateChange(event) {
        if (!window.AndroidPlayerBridge) return;
        if (event.data == YT.PlayerState.PLAYING) {
          AndroidPlayerBridge.onStateChange(1);
        } else if (event.data == YT.PlayerState.PAUSED) {
          AndroidPlayerBridge.onStateChange(2);
        } else if (event.data == YT.PlayerState.BUFFERING) {
          AndroidPlayerBridge.onStateChange(3);
        } else if (event.data == YT.PlayerState.ENDED) {
          AndroidPlayerBridge.onStateChange(0);
        }
      }
      
      function onPlayerError(event) {
        if (window.AndroidPlayerBridge) {
            AndroidPlayerBridge.onPlayerError(event.data);
        }
      }
    </script>
  </body>
</html>
""".trimIndent()

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

    // Display the singleton WebView inside this composable slot
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
            .height(64.dp)
            .background(Color(0xFF0D0D0D))
            .clickable { 
                if (jammingRoomId.isNotBlank()) {
                    viewModel.setScreen(com.example.ui.viewmodel.ScreenType.JAMMING)
                } else {
                    viewModel.setScreen(com.example.ui.viewmodel.ScreenType.NOW_PLAYING)
                }
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        coil.compose.AsyncImage(
            model              = currentTrack?.thumbnailUrl,
            contentDescription = null,
            contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
            modifier           = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
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
