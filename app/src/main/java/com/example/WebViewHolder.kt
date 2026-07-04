package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.ui.viewmodel.MusicPlayerViewModel

/**
 * Singleton WebView that lives at the SERVICE level.
 *
 * Key principle: the WebView must be owned by VerseMusicService (a foreground service)
 * — not by an Activity or a Composable. When the Activity is paused (user switches apps),
 * Android throttles all resources associated with that Activity including WebViews.
 * A foreground MediaSessionService is protected from this and stays alive.
 *
 * This object is initialized inside VerseMusicService.onCreate() and destroyed in
 * VerseMusicService.onDestroy(). The Composable YouTubeWebViewPlayer simply
 * renders it for display when the app is visible.
 */
object WebViewHolder {

    @SuppressLint("StaticFieldLeak")
    private var webView: WebView? = null
    private var isInitialized = false
    private var hasLoadedFirstVideo = false

    /**
     * Initialize the WebView inside the foreground service context.
     * Must be called from VerseMusicService.onCreate().
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun initInService(serviceContext: Context, viewModel: MusicPlayerViewModel?) {
        if (isInitialized) return
        isInitialized = true

        webView = object : WebView(serviceContext) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                // Always tell Chromium the view is visible so JS/media is never throttled
                super.onWindowVisibilityChanged(android.view.View.VISIBLE)
            }
            override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
                super.onVisibilityChanged(changedView, android.view.View.VISIBLE)
            }
            override fun dispatchWindowVisibilityChanged(visibility: Int) {
                super.dispatchWindowVisibilityChanged(android.view.View.VISIBLE)
            }
        }.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()

            if (viewModel != null) {
                addJavascriptInterface(PlayerBridge(viewModel), "AndroidPlayerBridge")
            }
        }

        Log.d("WebViewHolder", "WebView initialized inside service")
    }

    /**
     * Fallback: initialize from Activity context if service hasn't started yet.
     * Uses applicationContext to prevent Activity context leaking.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context, viewModel: MusicPlayerViewModel) {
        if (isInitialized) return
        Log.w("WebViewHolder", "Initializing WebView from Activity (service not yet started)")
        initInService(context.applicationContext, viewModel)
    }

    /** Called by MusicPlayerViewModel when viewModel is ready (after service starts). */
    fun attachViewModel(viewModel: MusicPlayerViewModel) {
        val wv = webView ?: return
        // Re-add the JS bridge with the live ViewModel
        wv.post {
            try {
                wv.addJavascriptInterface(PlayerBridge(viewModel), "AndroidPlayerBridge")
            } catch (e: Exception) {
                Log.e("WebViewHolder", "Failed to attach ViewModel: ${e.message}")
            }
        }
    }

    fun getWebView(): WebView? = webView

    /**
     * Load a video or switch to a different video.
     * On first call: loads the full YouTube IFrame API page.
     * Subsequent calls: uses player.loadVideoById() to switch without reloading the page.
     */
    fun loadVideo(videoId: String, startSeconds: Int = 0, autoplay: Boolean = true) {
        val wv = webView ?: return

        wv.post {
            if (!hasLoadedFirstVideo) {
                hasLoadedFirstVideo = true
                val html = buildIframeHtml(videoId, startSeconds, if (autoplay) 1 else 0)
                wv.loadDataWithBaseURL("https://localhost", html, "text/html", "UTF-8", null)
            } else {
                val js = if (autoplay) {
                    "if(typeof player!=='undefined'&&player.loadVideoById) player.loadVideoById('$videoId',$startSeconds);"
                } else {
                    "if(typeof player!=='undefined'&&player.cueVideoById) player.cueVideoById('$videoId',$startSeconds);"
                }
                wv.evaluateJavascript(js, null)
            }
        }
    }

    fun play() {
        webView?.post {
            webView?.evaluateJavascript(
                "if(typeof player!=='undefined'&&player.playVideo) player.playVideo();", null
            )
        }
    }

    fun pause() {
        webView?.post {
            webView?.evaluateJavascript(
                "if(typeof player!=='undefined'&&player.pauseVideo) player.pauseVideo();", null
            )
        }
    }

    fun seekTo(seconds: Float) {
        webView?.post {
            webView?.evaluateJavascript(
                "if(typeof player!=='undefined'&&player.seekTo) player.seekTo($seconds,true);", null
            )
        }
    }

    fun setVolume(volume: Int) {
        webView?.post {
            webView?.evaluateJavascript(
                "if(typeof player!=='undefined'&&player.setVolume) player.setVolume($volume);", null
            )
        }
    }

    fun destroy() {
        webView?.destroy()
        webView = null
        isInitialized = false
        hasLoadedFirstVideo = false
        Log.d("WebViewHolder", "WebView destroyed")
    }

    private fun buildIframeHtml(videoId: String, startSeconds: Int, autoplay: Int): String = """
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
          height: '100%', width: '100%',
          videoId: '$videoId',
          playerVars: {
            'playsinline': 1, 'autoplay': $autoplay,
            'controls': 1, 'rel': 0, 'modestbranding': 1, 'fs': 0,
            'origin': 'https://localhost', 'start': $startSeconds
          },
          events: {
            'onReady': onPlayerReady,
            'onStateChange': onPlayerStateChange,
            'onError': onPlayerError
          }
        });
      }

      function onPlayerReady(event) {
        if (window.AndroidPlayerBridge) AndroidPlayerBridge.onPlayerReady();
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
        if      (event.data == YT.PlayerState.PLAYING)   AndroidPlayerBridge.onStateChange(1);
        else if (event.data == YT.PlayerState.PAUSED)    AndroidPlayerBridge.onStateChange(2);
        else if (event.data == YT.PlayerState.BUFFERING) AndroidPlayerBridge.onStateChange(3);
        else if (event.data == YT.PlayerState.ENDED)     AndroidPlayerBridge.onStateChange(0);
      }

      function onPlayerError(event) {
        if (window.AndroidPlayerBridge) AndroidPlayerBridge.onPlayerError(event.data);
      }
    </script>
  </body>
</html>
    """.trimIndent()
}

/**
 * JS → Kotlin bridge for the WebView player.
 */
class PlayerBridge(private val viewModel: MusicPlayerViewModel) {
    @JavascriptInterface fun onPlayerReady()           = viewModel.setLoading(false)
    @JavascriptInterface fun onStateChange(state: Int) {
        when (state) {
            1 -> { viewModel.setPlaying(true);  viewModel.setLoading(false) }
            2 -> { viewModel.setPlaying(false); viewModel.setLoading(false) }
            3 -> viewModel.setLoading(true)
            0 -> {
                val pos = viewModel.currentPositionMs.value
                val dur = viewModel.durationMs.value
                // Only automatically advance if we are within 5 seconds of the end of the song
                if (dur > 0 && Math.abs(dur - pos) <= 5000) {
                    viewModel.playNext(isAutoPlay = true)
                } else {
                    Log.w("PlayerBridge", "Ignored false-positive ENDED event (position=$pos, duration=$dur)")
                    // Auto-resume if it was a false positive pause during seek
                    if (viewModel.isPlaying.value) {
                        viewModel.setPlaying(true)
                    }
                }
            }
        }
    }
    @JavascriptInterface fun onTimeUpdate(time: Float)  = viewModel.updateProgress((time * 1000).toLong())
    @JavascriptInterface fun onVideoDuration(d: Float)  = viewModel.updateDuration((d * 1000).toLong())
    @JavascriptInterface fun onPlayerError(error: Int) { viewModel.setLoading(false) }
}
