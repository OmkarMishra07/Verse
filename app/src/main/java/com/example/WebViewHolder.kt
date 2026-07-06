package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.ui.viewmodel.MusicPlayerViewModel
import com.google.firebase.crashlytics.FirebaseCrashlytics

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
    private var lastLoadedVideoId: String? = null
    private var lastAutoplay: Boolean = true
    private var lastLoadTime: Long = 0L

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

            addJavascriptInterface(PlayerBridge(viewModel), "AndroidPlayerBridge")
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
        // No-op: JavascriptInterface is added once during initInService.
        // Re-adding it causes "Unknown object: 1" errors in Chromium.
    }

    fun getWebView(): WebView? = webView

    /**
     * Load a video or switch to a different video.
     * On first call: loads the full YouTube IFrame API page.
     * Subsequent calls: uses player.loadVideoById() to switch without reloading the page.
     */
    fun loadVideo(videoId: String, startSeconds: Int = 0, autoplay: Boolean = true) {
        val wv = webView ?: return

        // Deduplicate rapid dual-calls from UI and Background Service
        val now = System.currentTimeMillis()
        // If the new call has autoplay=true but the old one had autoplay=false, let it pass to upgrade the video to playing
        if (videoId == lastLoadedVideoId && (!autoplay || lastAutoplay) && (now - lastLoadTime) < 2000L) {
            Log.d("WebViewHolder", "Ignoring duplicate loadVideo for $videoId")
            return
        }
        lastLoadedVideoId = videoId
        lastAutoplay = autoplay
        lastLoadTime = now

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
          try {
            if (player && typeof player.getCurrentTime === 'function' && typeof player.getDuration === 'function') {
              if (window.AndroidPlayerBridge) {
                AndroidPlayerBridge.onTimeUpdate(player.getCurrentTime().toString());
                AndroidPlayerBridge.onVideoDuration(player.getDuration().toString());
                if (typeof player.getVideoLoadedFraction === 'function') {
                  AndroidPlayerBridge.onVideoLoadedFraction(player.getVideoLoadedFraction().toString());
                }
              }
            }
          } catch (e) {
            console.error("Timer error: ", e);
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
 * JS -> Kotlin bridge for the WebView player.
 */
class PlayerBridge(private val fallbackViewModel: MusicPlayerViewModel? = null) {
    private val activeViewModel: MusicPlayerViewModel?
        get() = MusicPlayerViewModel.instance ?: fallbackViewModel

    // YouTube IFrame API error codes
    // 2: invalid parameter, 5: HTML5 error, 100: video not found,
    // 101/150: video not embeddable (most common for copyright/region blocks)
    private fun ytErrorDescription(code: Int) = when (code) {
        2    -> "invalid_param"
        5    -> "html5_error"
        100  -> "video_not_found"
        101, 150 -> "not_embeddable"
        else -> "unknown_$code"
    }

    @JavascriptInterface fun onPlayerReady() {
        FirebaseCrashlytics.getInstance().log("YT player ready")
        activeViewModel?.setLoading(false)
    }

    @JavascriptInterface fun onStateChange(state: Int) {
        val vm = activeViewModel ?: return
        val trackId = vm.currentTrack.value?.id ?: "none"
        when (state) {
            1 -> {
                FirebaseCrashlytics.getInstance().log("YT state: PLAYING track=$trackId")
                FirebaseCrashlytics.getInstance().setCustomKey("playback_state", "playing")
                vm.setPlaying(true)
                vm.setLoading(false)
            }
            2 -> {
                FirebaseCrashlytics.getInstance().log("YT state: PAUSED track=$trackId")
                FirebaseCrashlytics.getInstance().setCustomKey("playback_state", "paused")
                vm.setPlaying(false)
                vm.setLoading(false)
            }
            3 -> {
                FirebaseCrashlytics.getInstance().log("YT state: BUFFERING track=$trackId")
                FirebaseCrashlytics.getInstance().setCustomKey("playback_state", "buffering")
                vm.setLoading(true)
            }
            0 -> {
                val pos = vm.currentPositionMs.value
                val dur = vm.durationMs.value
                // Guard against false-positive ENDED events fired during seek-to-start
                // (where pos would be near 0 and dur is large).
                // Tolerance is 30s to account for:
                //   - 500ms polling lag in position updates
                //   - Duration not yet updated from YouTube (still at default 180000ms)
                //   - Variable song lengths where the last poll may lag behind
                // We skip only if pos is very close to 0 (clearly a false-positive on seek).
                val isNearStart = pos < 3000L
                if (!isNearStart) {
                    FirebaseCrashlytics.getInstance().log("YT state: ENDED track=$trackId pos=${pos}ms dur=${dur}ms -> advancing")
                    FirebaseCrashlytics.getInstance().setCustomKey("playback_state", "ended")
                    vm.playNext(isAutoPlay = true)
                } else {
                    Log.w("PlayerBridge", "Ignored false-positive ENDED event (position=$pos, duration=$dur) — pos near start")
                    FirebaseCrashlytics.getInstance().log("YT state: false-positive ENDED suppressed pos=${pos}ms")
                    // Auto-resume if it was a false positive pause during seek
                    if (vm.isPlaying.value) {
                        vm.setPlaying(true)
                    }
                }
            }
        }
    }

    @JavascriptInterface fun onTimeUpdate(timeStr: String) {
        val time = timeStr.toDoubleOrNull() ?: 0.0
        activeViewModel?.updateProgress((time * 1000).toLong())
    }

    @JavascriptInterface fun onVideoDuration(dStr: String) {
        val d = dStr.toDoubleOrNull() ?: 0.0
        activeViewModel?.updateDuration((d * 1000).toLong())
    }

    @JavascriptInterface fun onVideoLoadedFraction(fractionStr: String) {
        val fraction = fractionStr.toFloatOrNull() ?: 0f
        activeViewModel?.updateBufferedFraction(fraction)
    }

    @JavascriptInterface fun onPlayerError(error: Int) {
        val vm = activeViewModel
        val trackId = vm?.currentTrack?.value?.id ?: "none"
        val desc = ytErrorDescription(error)
        Log.e("PlayerBridge", "YT player error $error ($desc) for track=$trackId")
        FirebaseCrashlytics.getInstance().log("YT player error $error ($desc) track=$trackId")
        FirebaseCrashlytics.getInstance().setCustomKey("playback_state", "error")
        FirebaseCrashlytics.getInstance().setCustomKey("current_track_id", trackId)
        // Report as non-fatal so we see frequency in dashboard without crashing
        FirebaseCrashlytics.getInstance().recordException(
            RuntimeException("YouTubePlayerError: $desc (code=$error) track=$trackId")
        )
        vm?.setLoading(false)
        // If video is not embeddable or not found, skip to next automatically
        if (error == 100 || error == 101 || error == 150) {
            FirebaseCrashlytics.getInstance().log("Auto-skipping unplayable track=$trackId")
            vm?.playNext(isAutoPlay = true)
        }
    }
}
