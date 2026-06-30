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
    @JavascriptInterface fun onTimeUpdate(time: Float) = onTimeUpdate.invoke(time)
    @JavascriptInterface fun onVideoDuration(d: Float) = onDuration.invoke(d)
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
                AndroidPlayerBridge.onTimeUpdate(player.getCurrentTime());
                AndroidPlayerBridge.onVideoDuration(player.getDuration());
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
//  Main embedded YouTube player — lives ONLY in NowPlayingScreen.
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeWebViewPlayer(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val webView = remember {
        object : WebView(context) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                // Force Chromium to think it's always visible so it doesn't pause media
                super.onWindowVisibilityChanged(android.view.View.VISIBLE)
            }
        }.apply {
            settings.javaScriptEnabled                = true
            settings.domStorageEnabled                = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort                  = true
            settings.loadWithOverviewMode             = true
            clearCache(true)

            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()

            addJavascriptInterface(
                YouTubePlayerBridge(
                    onReady       = { viewModel.setLoading(false) },
                    onStateChange = { code ->
                        when (code) {
                            1 -> { viewModel.setPlaying(true);  viewModel.setLoading(false) }
                            2 -> { viewModel.setPlaying(false); viewModel.setLoading(false) }
                            3 ->   viewModel.setLoading(true)
                            0 ->   post { viewModel.playNext(isAutoPlay = true) }
                        }
                    },
                    onTimeUpdate = { t -> viewModel.updateProgress((t * 1000).toLong()) },
                    onDuration   = { d -> if (d > 0) viewModel.updateDuration((d * 1000).toLong()) },
                    onError      = { err ->
                        viewModel.setLoading(false)
                        Log.e("YTWebView", "Player error: $err")
                    }
                ),
                "AndroidPlayerBridge"
            )
        }
    }

    var lastLoadedId by remember { mutableStateOf("") }

    val track by viewModel.currentTrack.collectAsState()
    LaunchedEffect(track?.id) {
        val id = track?.id ?: return@LaunchedEffect
        if (id == lastLoadedId) return@LaunchedEffect
        lastLoadedId = id
        
        val startSecs = (viewModel.currentPositionMs.value / 1000).toInt()
        val autoplay = if (viewModel.isPlaying.value) 1 else 0
        
        // Use https://localhost as the base to emulate a normal web embed
        val html = buildYouTubeIframeApiHtml(id, startSecs, autoplay)
        webView.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
    }

    // Sync play / pause
    val playing by viewModel.isPlaying.collectAsState()
    LaunchedEffect(playing) {
        val js = if (playing)
            "if(typeof player !== 'undefined' && player.playVideo) player.playVideo();"
        else
            "if(typeof player !== 'undefined' && player.pauseVideo) player.pauseVideo();"
        webView.evaluateJavascript(js, null)
    }

    // Sync volume
    val vol by viewModel.volume.collectAsState()
    LaunchedEffect(vol) {
        // YouTube API volume is 0-100
        val vol100 = (vol * 100).toInt()
        webView.evaluateJavascript(
            "if(typeof player !== 'undefined' && player.setVolume) player.setVolume($vol100);", null
        )
    }

    // Sync seek request
    val seekRequest by viewModel.seekRequestMs.collectAsState()
    LaunchedEffect(seekRequest) {
        val ms = seekRequest ?: return@LaunchedEffect
        webView.evaluateJavascript(
            "if(typeof player !== 'undefined' && player.seekTo) player.seekTo(${ms / 1000f}, true);", null
        )
        viewModel.clearSeekRequest()
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

// ─────────────────────────────────────────────────────────────────────────────
//  Mini Player bar — shown at the bottom when browsing other screens.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MiniYouTubePlayerBar(viewModel: MusicPlayerViewModel) {
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying    by viewModel.isPlaying.collectAsState()

    if (currentTrack == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(Color(0xFF0D0D0D))
            .clickable { viewModel.setExpanded(false) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model              = currentTrack?.thumbnailUrl,
            contentDescription = null,
            contentScale       = ContentScale.Crop,
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
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text     = currentTrack?.artist ?: "",
                color    = Color.White.copy(alpha = 0.55f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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
