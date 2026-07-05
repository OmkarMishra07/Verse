package com.example

import android.app.Application
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import android.media.AudioManager
import android.media.AudioDeviceInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.local.Playlist

import com.example.data.model.Track
import com.example.data.model.toTrack
import com.example.ui.components.ClickWheel
import com.example.ui.components.MiniYouTubePlayerBar
import com.example.ui.components.YouTubeWebViewPlayer
import com.example.ui.components.AuthScreen
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.auth.FirebaseAuth
import com.example.ui.theme.iPodAccentBlue
import com.example.ui.theme.iPodChassis
import com.example.ui.theme.iPodChassisDark
import com.example.ui.theme.iPodDisplayBg
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.MusicPlayerViewModel
import com.example.ui.viewmodel.ScreenType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

        checkBatteryOptimizations()
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize(), containerColor = Color(0xFF0F0F0F)) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        val authViewModel: AuthViewModel = viewModel()
                        val currentUser by authViewModel.currentUser.collectAsState()
                        val musicViewModel: com.example.ui.viewmodel.MusicPlayerViewModel = viewModel()

                        LaunchedEffect(currentUser) {
                            val user = currentUser
                            if (user != null) {
                                com.example.data.remote.FirestoreService.initUserProfile(user)
                                musicViewModel.onUserLoggedIn(user.uid)
                            } else {
                                musicViewModel.onUserLoggedOut()
                            }
                        }

                        if (currentUser == null) {
                            AuthScreen(
                                onAuthSuccess = { user ->
                                    // Directly update StateFlow — no waiting for AuthStateListener
                                    authViewModel.onUserSignedIn(user)
                                }
                            )
                        } else {
                            iPodPlayerApp(
                                currentUser = currentUser,
                                onLogout = {
                                    authViewModel.signOut()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Optimize Background Playback")
                    .setMessage("To prevent the Android system from cutting off music playback in the background, please disable battery optimization for Verse.")
                    .setPositiveButton("Configure") { _, _ ->
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to open settings: ${e.message}")
                        }
                    }
                    .setNegativeButton("Not Now", null)
                    .show()
            }
        }
    }
}

@Composable
fun iPodPlayerApp(
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: MusicPlayerViewModel = viewModel()
    
    // Dialog states
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToAddTo by remember { mutableStateOf<Track?>(null) }
    var showLyricsDialog by remember { mutableStateOf(false) }
    
    // Fetch state for UI
    val isExpanded by viewModel.isExpanded.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val playlists by viewModel.playlists.collectAsState()

    // Back navigation logic
    var backPressTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (currentScreen != ScreenType.EXPLORE) {
            viewModel.setScreen(ScreenType.EXPLORE)
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - backPressTime < 2000) {
                (context as? android.app.Activity)?.finish()
            } else {
                backPressTime = currentTime
                Toast.makeText(context, "Swipe again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Base layout: Metallic iPod Hardware Device Frame
    iPodDeviceFrame(
        viewModel = viewModel,
        isExpanded = isExpanded,
        showCreatePlaylistDialog = { showCreatePlaylistDialog = true },
        playlistToAddTo = playlistToAddTo,
        onAddTrackToPlaylist = { playlistToAddTo = it },
        onShowLyrics = { showLyricsDialog = true },
        currentUser = currentUser,
        onLogout = onLogout
    )

    // Dialogue Overlay: Create Playlist
    if (showCreatePlaylistDialog) {
        var newPlaylistName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreatePlaylistDialog = false },
            title = { Text("Create New Playlist", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text("Playlist Name", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = iPodAccentBlue,
                        unfocusedBorderColor = Color.Gray
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            viewModel.createPlaylist(newPlaylistName)
                            showCreatePlaylistDialog = false
                            Toast.makeText(context, "Playlist created", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Create", color = iPodAccentBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreatePlaylistDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E2633)
        )
    }

    // Dialogue Overlay: Add to Playlist
    playlistToAddTo?.let { track ->
        AlertDialog(
            onDismissRequest = { playlistToAddTo = null },
            title = { Text("Add to Playlist", color = Color.White) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (playlists.isEmpty()) {
                        Text("No playlists found. Create one first!", color = Color.Gray)
                    } else {
                        playlists.forEach { playlist ->
                            TextButton(
                                onClick = {
                                    viewModel.addTrackToPlaylist(track, playlist.id)
                                    playlistToAddTo = null
                                    Toast.makeText(context, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(playlist.name, color = Color.White, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { playlistToAddTo = null }) {
                    Text("Close", color = iPodAccentBlue)
                }
            },
            containerColor = Color(0xFF1E2633)
        )
    }

    // Dialogue Overlay: Lyrics Screen
    if (showLyricsDialog) {
        val lyrics = remember(currentTrack) {
            getLyricsForTrack(currentTrack)
        }
        AlertDialog(
            onDismissRequest = { showLyricsDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Lyrics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    IconButton(onClick = { showLyricsDialog = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = lyrics,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 16.sp,
                        lineHeight = 26.sp,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            },
            confirmButton = {},
            containerColor = Color(0xFF151C26)
        )
    }
}

@Composable
fun iPodDeviceFrame(
    viewModel: MusicPlayerViewModel,
    isExpanded: Boolean,
    showCreatePlaylistDialog: () -> Unit,
    playlistToAddTo: Track?,
    onAddTrackToPlaylist: (Track) -> Unit,
    onShowLyrics: () -> Unit,
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onLogout: () -> Unit
) {
    val displayWeight by animateFloatAsState(
        targetValue = if (isExpanded) 1.2f else 0.75f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "DisplayWeightAnimation"
    )

    val wheelSpacing by animateDpAsState(
        targetValue = if (isExpanded) 12.dp else 28.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "WheelSpacingAnimation"
    )

    var lastPrevClickTime by remember { mutableStateOf(0L) }
    var isFullScreen by remember { mutableStateOf(false) }
    val currentScreen by viewModel.currentScreen.collectAsState()
    val showFullscreenBtn = !isExpanded && currentScreen == ScreenType.NOW_PLAYING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(iPodChassis, iPodChassisDark)
                )
            )
            .padding(horizontal = 14.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // IPOD SCREEN DISPLAY
        iPodScreenDisplay(
            viewModel = viewModel,
            isExpanded = isExpanded,
            modifier = Modifier.weight(displayWeight),
            showCreatePlaylistDialog = showCreatePlaylistDialog,
            onAddTrackToPlaylist = onAddTrackToPlaylist,
            onShowLyrics = onShowLyrics,
            onDisplayClick = { isFullScreen = !isFullScreen },
            currentUser = currentUser,
            onLogout = onLogout
        )

        AnimatedVisibility(
            visible = !isFullScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(wheelSpacing))

                // FULLSCREEN TOGGLE ROW — visible only on Now Playing collapsed view
                if (showFullscreenBtn) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Search shortcut
                        IconButton(
                            onClick = { viewModel.setExpanded(true); viewModel.setScreen(ScreenType.SEARCH) },
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        // Fullscreen toggle
                        IconButton(
                            onClick = { isFullScreen = !isFullScreen },
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        ) {
                            Icon(
                                imageVector = if (isFullScreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = if (isFullScreen) "Exit Fullscreen" else "Fullscreen",
                                tint = iPodAccentBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // TAC-TILE CLICK WHEEL
                ClickWheel(
                    viewModel = viewModel,
                    modifier = Modifier.padding(bottom = 8.dp),
                    onMenuClick = {
                        if (isExpanded) {
                            if (viewModel.currentScreen.value == ScreenType.JAMMING) {
                                viewModel.setScreen(ScreenType.NOW_PLAYING)
                            } else {
                                viewModel.setScreen(ScreenType.JAMMING)
                            }
                        } else {
                            viewModel.setExpanded(true)
                            viewModel.setScreen(ScreenType.JAMMING)
                        }
                    },
                    onPrevClick = {
                        viewModel.playPrevious()
                    },
                    onNextClick = { viewModel.playNext() },
                    onPlayPauseClick = { viewModel.togglePlayback() },
                    onCenterClick = { viewModel.onCenterPressed() }
                )
            }
        }

        AnimatedVisibility(
            visible = isFullScreen,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable { isFullScreen = false }
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.FullscreenExit,
                    contentDescription = "Exit Fullscreen",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Tap display or here to exit fullscreen",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun iPodScreenDisplay(
    viewModel: MusicPlayerViewModel,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    showCreatePlaylistDialog: () -> Unit,
    onAddTrackToPlaylist: (Track) -> Unit,
    onShowLyrics: () -> Unit,
    onDisplayClick: () -> Unit,
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onLogout: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    // Horizontal pager state for swipeable pages
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 7 })
    
    // Mapping pages to ScreenType index
    val pageToScreen = listOf(
        ScreenType.EXPLORE,
        ScreenType.LIKED,
        ScreenType.PLAYLISTS,
        ScreenType.SEARCH,
        ScreenType.QUEUE,
        ScreenType.NOW_PLAYING,
        ScreenType.JAMMING
    )

    // Sync ViewModel screen modifications to Pager
    LaunchedEffect(currentScreen) {
        val targetPage = pageToScreen.indexOf(currentScreen)
        if (targetPage != -1 && pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    // Sync Pager swipes to ViewModel screen using settledPage so it doesn't get stuck mid-scroll
    LaunchedEffect(pagerState.settledPage) {
        val targetScreen = pageToScreen[pagerState.settledPage]
        if (viewModel.currentScreen.value != targetScreen && targetScreen != ScreenType.PLAYLIST_DETAIL) {
            viewModel.setScreen(targetScreen)
        }
    }

    // Background Artwork-driven Blur effect
    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(28.dp), clip = true)
            .clip(RoundedCornerShape(28.dp))
            .background(iPodDisplayBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDisplayClick()
            }
    ) {
        // 1. ALBUM ART WORK BLURRED BACKGROUND (ADAPTIVE AMBIENT GLOW)
        AnimatedContent(
            targetState = currentTrack?.thumbnailUrl,
            transitionSpec = {
                fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
            },
            label = "GlowBackgroundTransition"
        ) { imageUrl ->
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(36.dp)
                        .graphicsLayer(alpha = 0.35f)
                )
            }
        }

        // 1B. AMBIENT RADIAL GLOW (Immersive crimson overlay centered at the screen middle)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            iPodAccentBlue.copy(alpha = 0.35f),
                            Color.Transparent
                        )
                    )
                )
        )

        // 2. GLASS OVERLAY
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.03f),
                            Color.Black.copy(alpha = 0.4f)
                        )
                    )
                )
        )

        // 3. ACTUAL DISPLAY LAYOUT
        Column(modifier = Modifier.fillMaxSize()) {
            // A. TOP APPLE-STYLE STATUS BAR
            iPodStatusBar(currentTrack, currentUser, onLogout)

            // Hidden WebView at root to keep audio playing globally across screens
            Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                YouTubeWebViewPlayer(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // B. SCREEN INNER CONTENT (PAGER OR SUBVIEW)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val isOverlayScreen = currentScreen == ScreenType.PLAYLIST_DETAIL || currentScreen == ScreenType.EXPLORE_SECTION
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isOverlayScreen) 0f else 1f),
                    userScrollEnabled = isExpanded,
                    beyondViewportPageCount = 6
                ) { pageIndex ->
                    when (pageToScreen[pageIndex]) {
                        ScreenType.EXPLORE -> ExploreScreen(viewModel = viewModel)
                        ScreenType.LIKED -> LikedScreen(viewModel = viewModel)
                        ScreenType.PLAYLISTS -> PlaylistsScreen(
                            viewModel = viewModel,
                            onCreatePlaylistClick = showCreatePlaylistDialog
                        )
                        ScreenType.SEARCH -> SearchScreen(viewModel = viewModel)
                        ScreenType.QUEUE -> QueueScreen(viewModel = viewModel)
                        ScreenType.NOW_PLAYING -> NowPlayingScreen(
                            viewModel = viewModel,
                            onAddTrackToPlaylist = onAddTrackToPlaylist,
                            onShowLyrics = onShowLyrics
                        )
                        ScreenType.JAMMING -> JammingScreen(
                            viewModel = viewModel,
                            onAddTrackToPlaylist = onAddTrackToPlaylist,
                            onShowLyrics = onShowLyrics
                        )
                        else -> {}
                    }
                }

                if (currentScreen == ScreenType.PLAYLIST_DETAIL) {
                    PlaylistDetailScreen(viewModel = viewModel)
                }
                if (currentScreen == ScreenType.EXPLORE_SECTION) {
                    ExploreSectionScreen(viewModel = viewModel)
                }
            }

            // C. MINI PLAYER (DOCKED AT THE BOTTOM WHILE BROWSING)
            AnimatedVisibility(
                visible = isExpanded && currentScreen != ScreenType.NOW_PLAYING,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                MiniYouTubePlayerBar(viewModel = viewModel)
            }
        }

    }
}

@Composable
fun iPodStatusBar(
    currentTrack: Track?,
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onLogout: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Color.Black.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Verse",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        if (currentUser != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { onLogout() }
            ) {
                val photoUrl = currentUser.photoUrl
                val name = currentUser.displayName.takeIf { !it.isNullOrBlank() } 
                    ?: currentUser.email 
                    ?: "U"
                
                if (photoUrl != null) {
                    coil.compose.AsyncImage(
                        model = photoUrl,
                        contentDescription = "Profile",
                        modifier = Modifier
                            .size(16.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.first().uppercase(),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Text(
                    text = "Logout",
                    color = iPodAccentBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun SeekBar(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
    val progressMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val bufferedFraction by viewModel.bufferedFraction.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    // Compute display fraction
    val displayFraction = when {
        isDragging -> dragFraction
        durationMs > 0L -> (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    // Smooth animation during playback; instant snap during drag
    val animatedFraction by animateFloatAsState(
        targetValue = displayFraction,
        animationSpec = if (isDragging)
            snap()
        else
            tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "SeekBarAnimation"
    )

    // If buffer data not coming in, show full bar so thumb is never hidden
    val safeBufFraction = if (bufferedFraction <= 0.01f) 1f else bufferedFraction

    val focusRequester = remember { FocusRequester() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .focusRequester(focusRequester)
            .focusable()
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = progressMs.toFloat(),
                    range = 0f..maxOf(1f, durationMs.toFloat()),
                    steps = 0
                )
                stateDescription = "${formatMs(progressMs)} of ${formatMs(durationMs)}"
                setProgress { targetValue ->
                    val targetMs = targetValue.toLong().coerceIn(0L, durationMs)
                    viewModel.seekTo(targetMs)
                    true
                }
            }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val stepMs = if (keyEvent.isShiftPressed) 15000L else 5000L
                    when (keyEvent.key) {
                        Key.DirectionLeft -> {
                            val t = (progressMs - stepMs).coerceAtLeast(0L)
                            viewModel.seekTo(t); true
                        }
                        Key.DirectionRight -> {
                            val t = (progressMs + stepMs).coerceAtMost(durationMs)
                            viewModel.seekTo(t); true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val widthDp = with(LocalDensity.current) { constraints.maxWidth.toDp() }
        val thumbSize = 14.dp

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                // Drag gesture — updates dragFraction live while finger moves
                .pointerInput(durationMs, widthPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / widthPx).coerceIn(0f, 1f)
                            focusRequester.requestFocus()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / widthPx).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val seekMs = (dragFraction * durationMs).toLong().coerceIn(0L, durationMs)
                            isDragging = false
                            viewModel.seekTo(seekMs)
                        },
                        onDragCancel = {
                            val seekMs = (dragFraction * durationMs).toLong().coerceIn(0L, durationMs)
                            isDragging = false
                            viewModel.seekTo(seekMs)
                        }
                    )
                }
                // Tap gesture — only fires when finger doesn't move (not a drag)
                .pointerInput(durationMs, widthPx) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (durationMs > 0 && widthPx > 0) {
                                val fraction = (offset.x / widthPx).coerceIn(0f, 1f)
                                val seekMs = (fraction * durationMs).toLong().coerceIn(0L, durationMs)
                                viewModel.seekTo(seekMs)
                                focusRequester.requestFocus()
                            }
                        }
                    )
                }
        ) {
            // Track background
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
            ) {
                // Buffered range
                Box(
                    modifier = Modifier
                        .fillMaxWidth(safeBufFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                )
                // Played progress
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(iPodAccentBlue, RoundedCornerShape(2.dp))
                )
            }

            // Thumb
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = ((widthDp - thumbSize) * animatedFraction.coerceIn(0f, 1f)).coerceAtLeast(0.dp))
                    .size(thumbSize)
                    .background(Color.White, CircleShape)
            )

            // Drag tooltip
            if (isDragging && durationMs > 0) {
                val tooltipMs = (dragFraction * durationMs).toLong()
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(
                            x = ((widthDp - 44.dp) * dragFraction).coerceAtLeast(0.dp),
                            y = (-26).dp
                        )
                        .background(Color.Black.copy(0.85f), RoundedCornerShape(4.dp))
                        .border(0.5.dp, Color.White.copy(0.2f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = formatMs(tooltipMs),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}




// ==========================================
// 1. NOW PLAYING SCREEN (COMPACT & DETAILS)
// ==========================================
@Composable
fun NowPlayingScreen(
    viewModel: MusicPlayerViewModel,
    onAddTrackToPlaylist: (Track) -> Unit,
    onShowLyrics: () -> Unit
) {
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val progressMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val likedTracks by viewModel.likedTracks.collectAsState()
    val isExpanded by viewModel.isExpanded.collectAsState()
    val repeatMode by viewModel.repeatMode.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableStateOf(0L) }
    val displayPositionMs = if (isDragging) dragPositionMs else progressMs

    val coverArtHeight by animateDpAsState(
        targetValue = if (isExpanded) 180.dp else 150.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CoverArtHeightAnimation"
    )

    val metaSpacerHeight by animateDpAsState(
        targetValue = if (isExpanded) 10.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "MetaSpacerHeightAnimation"
    )

    // ── Inline search state ──────────────────────────────────────────────────
    var showSearch by remember { mutableStateOf(false) }
    var inlineQuery by remember { mutableStateOf("") }
    var inlineResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // Audio device state
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager }
    var outputDeviceName by remember { mutableStateOf("Speaker") }
    
    LaunchedEffect(Unit) {
        while(true) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val activeDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                ?: devices.firstOrNull()
            outputDeviceName = activeDevice?.productName?.toString() ?: "Speaker"
            kotlinx.coroutines.delay(2000)
        }
    }

    // Debounced search when query changes
    LaunchedEffect(inlineQuery) {
        if (inlineQuery.isBlank()) { inlineResults = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(600)
        isSearching = true
        try {
            val results = com.example.data.network.YouTubeSearchHelper.search(inlineQuery)
            inlineResults = results.map { it.toTrack() }
        } catch (_: Exception) {}
        isSearching = false
    }
    // ────────────────────────────────────────────────────────────────────────

    val progressValue = if (durationMs > 0) progressMs.toFloat() / durationMs.toFloat() else 0f
    val isLiked = currentTrack?.let { track -> likedTracks.any { it.id == track.id } } ?: false

    if (currentTrack == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select a song to start", color = Color.Gray)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        // ── INLINE SEARCH BAR ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = showSearch,
            enter = expandVertically() + fadeIn(),
            exit  = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = inlineQuery,
                    onValueChange = { inlineQuery = it },
                    placeholder = {
                        Text("Search YouTube…", color = Color.White.copy(0.4f), fontSize = 12.sp)
                    },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, null, tint = Color.White.copy(0.5f),
                            modifier = Modifier.size(16.dp))
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            showSearch = false; inlineQuery = ""; inlineResults = emptyList()
                            focusManager.clearFocus()
                        }) {
                            Icon(Icons.Filled.Close, null, tint = Color.White.copy(0.5f),
                                modifier = Modifier.size(14.dp))
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = iPodAccentBlue,
                        unfocusedBorderColor = Color.White.copy(0.2f),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        cursorColor          = iPodAccentBlue,
                        focusedContainerColor   = Color.White.copy(0.05f),
                        unfocusedContainerColor = Color.White.copy(0.03f)
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )

                if (isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        color = iPodAccentBlue
                    )
                }

                if (inlineResults.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(inlineResults) { _, track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        focusManager.clearFocus()
                                        showSearch = false
                                        inlineQuery = ""
                                        inlineResults = emptyList()
                                        viewModel.selectAndPlayTrack(track)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = track.thumbnailUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        color = Color.White.copy(0.55f),
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = track.duration,
                                    color = Color.White.copy(0.35f),
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // ── TOP HALF: Cover Art + meta ──────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cover Art box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Takes remaining space to not waste any vertical space
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(1f) // Ensures 1:1 perfect square
                        .shadow(12.dp, RoundedCornerShape(12.dp), clip = false)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF111111)),
                    contentAlignment = Alignment.Center
                ) {
                    if (currentTrack != null) {
                        AsyncImage(
                            model = currentTrack!!.thumbnailUrl,
                            contentDescription = "Cover Art",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // SONG METADATA PANEL
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = currentTrack?.title ?: "",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    // Search icon shortcut in metadata panel
                    IconButton(
                        onClick = { showSearch = !showSearch },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(
                            imageVector = if (showSearch) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = if (showSearch) iPodAccentBlue else Color.White.copy(0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = currentTrack?.artist ?: "",
                    color = Color.White.copy(0.8f),
                    fontSize = 14.sp,
                    maxLines = 1
                )

                Text(
                    text = currentTrack?.album ?: "",
                    color = Color.White.copy(0.5f),
                    fontSize = 12.sp,
                    maxLines = 1
                )

                Spacer(modifier = Modifier.height(metaSpacerHeight))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier
                            .background(Color.White.copy(0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "Dolby Atmos",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(
                        onClick = { viewModel.toggleShuffle() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        val isShuffle by viewModel.isShuffleEnabled.collectAsState()
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffle) iPodAccentBlue else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleLikeTrack(currentTrack!!) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (isLiked) iPodAccentBlue else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.toggleRepeatMode() },
                        modifier = Modifier.size(32.dp)
                    ) {
                        val (icon, tint) = when (repeatMode) {
                            com.example.ui.viewmodel.RepeatMode.ALL -> androidx.compose.material.icons.Icons.Filled.Repeat to iPodAccentBlue
                            com.example.ui.viewmodel.RepeatMode.ONE -> androidx.compose.material.icons.Icons.Filled.RepeatOne to iPodAccentBlue
                            com.example.ui.viewmodel.RepeatMode.OFF -> androidx.compose.material.icons.Icons.Filled.Repeat to Color.White.copy(alpha = 0.3f)
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = "Repeat Mode",
                            tint = tint,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { onAddTrackToPlaylist(currentTrack!!) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AddCircle,
                            contentDescription = "Add to playlist",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // ── BOTTOM: Progress + controls ────────────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SeekBar(viewModel = viewModel)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatMs(displayPositionMs), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                Text(formatMs(durationMs), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            }

            // Bottom Actions (Lyrics, Queue, Devices, Volume)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Lyrics
                Text(
                    text = "Lyrics",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onShowLyrics() }
                )

                // Queue
                Text(
                    text = "Queue",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        viewModel.setExpanded(true)
                        viewModel.setScreen(ScreenType.QUEUE)
                    }
                )

                // Devices
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { /* Could show output device details */ }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = iPodAccentBlue,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = "Devices",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Volume Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VolumeDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(10.dp)
                    )
                    val vol by viewModel.volume.collectAsState()
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(3.dp)
                            .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(1.dp))
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val newVol = (offset.x / 36.dp.toPx()).coerceIn(0f, 1f)
                                    viewModel.setVolume(newVol)
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(vol)
                                .fillMaxHeight()
                                .background(iPodAccentBlue, RoundedCornerShape(1.dp))
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 1.5 JAMMING SCREEN
// ==========================================
@Composable
fun JammingScreen(
    viewModel: MusicPlayerViewModel,
    onAddTrackToPlaylist: (Track) -> Unit,
    onShowLyrics: () -> Unit
) {
    val context = LocalContext.current
    var inRoom by remember { mutableStateOf(false) }
    var roomCode by remember { mutableStateOf("") }
    var showChat by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var isHost by remember { mutableStateOf(false) }
    
    var myName by remember { mutableStateOf("User-${(100..999).random()}") }
    
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val progressMs by viewModel.currentPositionMs.collectAsState()

    val roomState by remember(inRoom, roomCode) {
        if (inRoom && roomCode.isNotBlank()) com.example.data.remote.JammingService.listenToRoom(roomCode) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)

    val chatMessages by remember(inRoom, roomCode) {
        if (inRoom && roomCode.isNotBlank()) com.example.data.remote.JammingService.listenToMessages(roomCode) else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())

    val coroutineScope = rememberCoroutineScope()

    // Host: Sync immediately on track or playing state change
    LaunchedEffect(isHost, currentTrack, isPlaying) {
        if (isHost && inRoom && roomCode.isNotBlank()) {
            com.example.data.remote.JammingService.updateRoomState(roomCode, currentTrack, isPlaying, viewModel.currentPositionMs.value)
        }
    }
    
    // Host: Sync position periodically
    LaunchedEffect(isHost, inRoom, roomCode) {
        if (isHost && inRoom && roomCode.isNotBlank()) {
            while (true) {
                com.example.data.remote.JammingService.updateRoomState(roomCode, currentTrack, isPlaying, viewModel.currentPositionMs.value)
                kotlinx.coroutines.delay(10000)
            }
        }
    }

    // Guest: Sync from Firestore
    LaunchedEffect(roomState) {
        if (!isHost && inRoom && roomState != null) {
            val state = roomState!!
            if (state.currentTrackId.isNotBlank() && currentTrack?.id != state.currentTrackId) {
                val track = com.example.data.model.Track(
                    id = state.currentTrackId,
                    title = state.currentTrackTitle,
                    artist = state.currentTrackArtist,
                    thumbnailUrl = state.currentTrackThumbnail,
                    duration = state.currentTrackDuration,
                    album = ""
                )
                viewModel.selectAndPlayTrack(track)
            }
            if (state.isPlaying != isPlaying) {
                viewModel.setPlaying(state.isPlaying)
            }
            val elapsed = System.currentTimeMillis() - state.updatedAt
            val expectedMs = if (state.isPlaying) state.positionMs + elapsed else state.positionMs
            if (Math.abs(expectedMs - progressMs) > 3000) {
                viewModel.seekTo(expectedMs)
            }
        }
    }

    if (!inRoom) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Jamming Session", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(20.dp))
            OutlinedTextField(
                value = myName,
                onValueChange = { myName = it },
                label = { Text("Your Name", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = roomCode,
                onValueChange = { roomCode = it },
                label = { Text("Room Code", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { 
                val code = roomCode.trim()
                val name = myName.trim()
                if (code.isNotEmpty() && name.isNotEmpty()) {
                    coroutineScope.launch {
                        val success = com.example.data.remote.JammingService.joinRoom(code, name)
                        if (success) {
                            roomCode = code
                            myName = name
                            isHost = false
                            inRoom = true
                        } else {
                            android.widget.Toast.makeText(context, "Failed to join room. Check code or connection.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = iPodAccentBlue)) {
                Text("Join Room")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { 
                val code = "ROOM-${(1000..9999).random()}"
                val name = myName.trim()
                if (name.isNotEmpty()) {
                    coroutineScope.launch { 
                        val success = com.example.data.remote.JammingService.createRoom(code, "host", name) 
                        if (success) {
                            roomCode = code
                            myName = name
                            isHost = true
                            inRoom = true
                        } else {
                            android.widget.Toast.makeText(context, "Failed to create room.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)) {
                Text("Create Room")
            }
        }
    } else {
        if (showShareDialog) {
            AlertDialog(
                onDismissRequest = { showShareDialog = false },
                title = { Text("Share Room", color = Color.White) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Room Code: $roomCode", color = Color.White)
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(modifier = Modifier.size(150.dp).background(Color.White), contentAlignment = Alignment.Center) {
                            Text("QR Code\n(Placeholder)", color = Color.Black, textAlign = TextAlign.Center)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, "Join my jamming session! Room Code: $roomCode")
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                        }) {
                            Text("Share Link")
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showShareDialog = false }) {
                        Text("Close")
                    }
                },
                containerColor = Color.DarkGray
            )
        }

        if (showChat) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Chat - Room: $roomCode", color = Color.White, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { showChat = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close Chat", tint = Color.White)
                    }
                }
                if (roomState?.participants?.isNotEmpty() == true) {
                    Text("Joined: ${roomState!!.participants.joinToString()}", color = iPodAccentBlue, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                }
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(chatMessages.size) { index ->
                        val msg = chatMessages[index]
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("${msg.senderName}: ", color = iPodAccentBlue, fontWeight = FontWeight.Bold)
                            Text(msg.message, color = Color.White)
                        }
                    }
                }
                var chatMsg by remember { mutableStateOf("") }
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = chatMsg,
                        onValueChange = { chatMsg = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a message...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    IconButton(onClick = {
                        if (chatMsg.isNotBlank()) {
                            coroutineScope.launch { com.example.data.remote.JammingService.sendMessage(roomCode, myName, chatMsg) }
                            chatMsg = ""
                        }
                    }) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = iPodAccentBlue)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                NowPlayingScreen(
                    viewModel = viewModel,
                    onAddTrackToPlaylist = onAddTrackToPlaylist,
                    onShowLyrics = onShowLyrics
                )
                
                Row(modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    IconButton(onClick = { showShareDialog = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
                    }
                    IconButton(onClick = { 
                        coroutineScope.launch { com.example.data.remote.JammingService.leaveRoom(roomCode, myName) }
                        inRoom = false 
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Leave", tint = Color.Red)
                    }
                }
                
                FloatingActionButton(
                    onClick = { showChat = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 48.dp).size(48.dp),
                    containerColor = iPodAccentBlue
                ) {
                    Icon(Icons.Default.Chat, contentDescription = "Open Chat", tint = Color.White)
                }
            }
        }
    }
}

// ==========================================
// 2. EXPLORE PAGE
// ==========================================
@Composable
fun ExploreScreen(viewModel: MusicPlayerViewModel) {
    val focusedIndex by viewModel.focusedIndex.collectAsState()
    val exploreRegion by viewModel.exploreRegion.collectAsState()
    val trendingSongs by viewModel.trendingSongs.collectAsState()
    val trendingAlbums by viewModel.trendingAlbums.collectAsState()
    val newReleases by viewModel.newReleases.collectAsState()
    val bollywoodHits by viewModel.bollywoodHits.collectAsState()
    val isLoading by viewModel.isExploreLoading.collectAsState()
    
    val pageScrollState = rememberScrollState()
    val songsRowState = rememberLazyListState()
    val albumsRowState = rememberLazyListState()
    val releasesRowState = rememberLazyListState()
    val bollywoodRowState = rememberLazyListState()

    val top10RowState = rememberLazyListState()
    val top10Hits = trendingSongs.take(10)

    val flatList = top10Hits + trendingSongs + trendingAlbums + newReleases + bollywoodHits

    LaunchedEffect(focusedIndex) {
        if (flatList.isEmpty()) return@LaunchedEffect
        
        if (focusedIndex < top10Hits.size) {
            top10RowState.animateScrollToItem(maxOf(0, focusedIndex))
        } else if (focusedIndex < top10Hits.size + trendingSongs.size) {
            val idx = focusedIndex - top10Hits.size
            songsRowState.animateScrollToItem(maxOf(0, idx))
        } else if (focusedIndex < top10Hits.size + trendingSongs.size + trendingAlbums.size) {
            val idx = focusedIndex - top10Hits.size - trendingSongs.size
            albumsRowState.animateScrollToItem(maxOf(0, idx))
        } else if (focusedIndex < top10Hits.size + trendingSongs.size + trendingAlbums.size + newReleases.size) {
            val idx = focusedIndex - top10Hits.size - trendingSongs.size - trendingAlbums.size
            releasesRowState.animateScrollToItem(maxOf(0, idx))
        } else if (focusedIndex < flatList.size) {
            val idx = focusedIndex - top10Hits.size - trendingSongs.size - trendingAlbums.size - newReleases.size
            bollywoodRowState.animateScrollToItem(maxOf(0, idx))
        }
    }

    if (isLoading && flatList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = iPodAccentBlue, modifier = Modifier.size(32.dp))
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(pageScrollState)
            .padding(vertical = 16.dp)
    ) {
        // Top Header: Search Bar & Region Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Fake Search Bar
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
                    .clickable { viewModel.setScreen(ScreenType.SEARCH) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White.copy(0.6f), modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("What do you want to listen to?", color = Color.White.copy(0.5f), fontSize = 12.sp)
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Region Toggle Button
            val isIndia = exploreRegion == MusicPlayerViewModel.ExploreRegion.INDIA
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .clickable { 
                        viewModel.setExploreRegion(if (isIndia) MusicPlayerViewModel.ExploreRegion.GLOBAL else MusicPlayerViewModel.ExploreRegion.INDIA)
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (isIndia) "IN" else "GL",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (top10Hits.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Top 10 Hits", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("View All", color = iPodAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.openExploreSection("Top 10 Hits", top10Hits) })
            }
            LazyRow(
                state = top10RowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(top10Hits) { index, track ->
                    val isFocused = index == focusedIndex
                    RankedSpotifyCard(track = track, rank = index + 1, isFocused = isFocused) { viewModel.selectAndPlayTrack(track, flatList) }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        if (trendingSongs.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Top Trending Songs", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("View All", color = iPodAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.openExploreSection("Top Trending Songs", trendingSongs) })
            }
            val offset = top10Hits.size
            LazyRow(
                state = songsRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(trendingSongs.take(15)) { index, track ->
                    val isFocused = (index + offset) == focusedIndex
                    SpotifyCard(track = track, isFocused = isFocused) { viewModel.selectAndPlayTrack(track, flatList) }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
        
        if (trendingAlbums.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Trending Albums", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("View All", color = iPodAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.openExploreSection("Trending Albums", trendingAlbums) })
            }
            val offset = top10Hits.size + trendingSongs.size
            LazyRow(
                state = albumsRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(trendingAlbums.take(15)) { index, track ->
                    val isFocused = (index + offset) == focusedIndex
                    SpotifyCard(track = track, isFocused = isFocused) { viewModel.selectAndPlayTrack(track, flatList) }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        if (newReleases.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("New Releases", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("View All", color = iPodAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.openExploreSection("New Releases", newReleases) })
            }
            val offset = top10Hits.size + trendingSongs.size + trendingAlbums.size
            LazyRow(
                state = releasesRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(newReleases.take(15)) { index, track ->
                    val isFocused = (index + offset) == focusedIndex
                    SpotifyCard(track = track, isFocused = isFocused) { viewModel.selectAndPlayTrack(track, flatList) }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        if (bollywoodHits.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Bollywood Hits", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("View All", color = iPodAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.openExploreSection("Bollywood Hits", bollywoodHits) })
            }
            val offset = top10Hits.size + trendingSongs.size + trendingAlbums.size + newReleases.size
            LazyRow(
                state = bollywoodRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(bollywoodHits.take(15)) { index, track ->
                    val isFocused = (index + offset) == focusedIndex
                    SpotifyCard(track = track, isFocused = isFocused) { viewModel.selectAndPlayTrack(track, flatList) }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun SpotifyCard(track: Track, isFocused: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) iPodAccentBlue else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .shadow(4.dp, RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = track.title,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = track.artist,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ==========================================
// 3. LIKED SONGS SCREEN
// ==========================================
@Composable
fun LikedScreen(viewModel: MusicPlayerViewModel) {
    val likedTracks by viewModel.likedTracks.collectAsState()
    val focusedIndex by viewModel.focusedIndex.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        if (focusedIndex in likedTracks.indices) {
            listState.animateScrollToItem(focusedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Unique Glassmorphic Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Liked Songs Cover
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF))))
                        .shadow(12.dp, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Liked Songs",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${likedTracks.size} songs",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                
                // Play Button
                if (likedTracks.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF00F2FE), Color(0xFF4FACFE))))
                            .shadow(8.dp, CircleShape)
                            .clickable { viewModel.selectAndPlayTrack(likedTracks.first(), likedTracks) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }

        if (likedTracks.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Songs you like will appear here", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("Save songs by tapping the heart icon.", color = Color.Gray, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(likedTracks) { index, track ->
                    val isFocused = index == focusedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isFocused) Color.White.copy(alpha = 0.2f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isFocused) Color(0xFF00F2FE).copy(alpha = 0.5f) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.selectAndPlayTrack(track, likedTracks) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = if (isFocused) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.5f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(28.dp)
                        )

                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = track.title,
                                color = if (isFocused) iPodAccentBlue else Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = Color.White.copy(0.6f),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { viewModel.toggleLikeTrack(track) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                tint = Color(0xFF1DB954), // Spotify Green heart
                                contentDescription = "Liked",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 4. PLAYLISTS SCREEN
// ==========================================
@Composable
fun PlaylistsScreen(
    viewModel: MusicPlayerViewModel,
    onCreatePlaylistClick: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsState()
    val focusedIndex by viewModel.focusedIndex.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        if (focusedIndex in 0..playlists.size) {
            listState.animateScrollToItem(focusedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Unique Glassmorphic Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Your Library",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 0: Create Playlist Trigger
            item {
                val isFocused = focusedIndex == 0
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isFocused) Color.White.copy(alpha = 0.2f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isFocused) Color(0xFF00F2FE).copy(alpha = 0.5f) else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onCreatePlaylistClick() }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(Color.White.copy(0.2f), Color.White.copy(0.05f)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Create Playlist",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Playlist items (Index shifted by +1)
            itemsIndexed(playlists) { i, playlist ->
                val index = i + 1 // Offset by 1 for the Create Playlist item
                val isFocused = index == focusedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isFocused) Color.White.copy(alpha = 0.2f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isFocused) Color(0xFF00F2FE).copy(alpha = 0.5f) else Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { viewModel.selectPlaylist(playlist) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Generate a pseudo-random gradient for each playlist based on its ID
                    val pid = playlist.id.toInt()
                    val colors = listOf(
                        Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF3F51B5), Color(0xFF00BCD4),
                        Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFF44336), Color(0xFF009688)
                    )
                    val color1 = colors[(pid * 3) % colors.size]
                    val color2 = colors[(pid * 7 + 1) % colors.size]

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(color1, color2)))
                            .shadow(4.dp, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            color = if (isFocused) Color(0xFF00F2FE) else Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Playlist • You",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                    IconButton(
                        onClick = { viewModel.deletePlaylist(playlist.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Playlist",
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 4B. PLAYLIST DETAIL SCREEN (NESTED)
// ==========================================
@Composable
fun PlaylistDetailScreen(viewModel: MusicPlayerViewModel) {
    val playlist by viewModel.selectedPlaylist.collectAsState()
    val songs by viewModel.selectedPlaylistSongs.collectAsState()
    val focusedIndex by viewModel.focusedIndex.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        if (focusedIndex in songs.indices) {
            listState.animateScrollToItem(focusedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Unique Glassmorphic Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    IconButton(
                        onClick = { viewModel.setScreen(ScreenType.PLAYLISTS) },
                        modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha=0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                }
                
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Playlist Cover (Gradient based on ID, same as PlaylistsScreen)
                    val pid = (playlist?.id ?: 0).toInt()
                    val colors = listOf(
                        Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF3F51B5), Color(0xFF00BCD4),
                        Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFF44336), Color(0xFF009688)
                    )
                    val color1 = colors[(pid * 3) % colors.size]
                    val color2 = colors[(pid * 7 + 1) % colors.size]
                    
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(color1, color2)))
                            .shadow(12.dp, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist?.name ?: "Playlist Detail",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Playlist • ${songs.size} songs",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    
                    if (songs.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(Color(0xFF00F2FE), Color(0xFF4FACFE))))
                                .shadow(8.dp, CircleShape)
                                .clickable { viewModel.selectAndPlayTrack(songs.first(), songs) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }
            }
        }

        if (songs.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Playlist is empty. Add songs!", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(songs) { index, track ->
                    val isFocused = index == focusedIndex

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isFocused) Color.White.copy(alpha = 0.2f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isFocused) Color(0xFF00F2FE).copy(alpha = 0.5f) else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { viewModel.selectAndPlayTrack(track, songs) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = if (isFocused) Color(0xFF00F2FE) else Color.White.copy(alpha = 0.5f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(28.dp)
                        )
                        
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = Color.White.copy(0.6f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(
                            onClick = { playlist?.let { viewModel.removeTrackFromPlaylist(track, it.id) } },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.RemoveCircle,
                                contentDescription = "Remove",
                                tint = Color.Red.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// 5. SEARCH PAGE
// ==========================================
@Composable
fun SearchScreen(viewModel: MusicPlayerViewModel) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearchLoading.collectAsState()
    val focusedIndex by viewModel.focusedIndex.collectAsState()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        if (focusedIndex in results.indices) {
            listState.animateScrollToItem(focusedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Modern Search Header
        Text(
            text = "Search",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 12.dp, top = 12.dp)
        )

        // Material3 search text field with system keyboard triggers
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("What do you want to listen to?", color = Color.White.copy(0.6f), fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("youtube_search_input")
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (isSearching) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = iPodAccentBlue, modifier = Modifier.size(30.dp))
            }
        } else if (results.isEmpty() && query.isNotBlank()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No matching YouTube videos found.", color = Color.White, fontSize = 14.sp)
            }
        } else if (results.isEmpty()) {
            // Browse All Section
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Browse All",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            val genres = listOf("Pop", "Hip-Hop", "Rock", "Electronic", "Classical", "Jazz", "Bollywood", "K-Pop")
            val colors = listOf(Color(0xFFE91E63), Color(0xFF9C27B0), Color(0xFF3F51B5), Color(0xFF00BCD4), Color(0xFF4CAF50), Color(0xFFFF9800), Color(0xFFF44336), Color(0xFF009688))
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(genres.size / 2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        for (col in 0..1) {
                            val index = row * 2 + col
                            if (index < genres.size) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(90.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors[index % colors.size])
                                        .clickable { viewModel.setSearchQuery(genres[index]) }
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = genres[index],
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(results) { index, track ->
                    val isFocused = index == focusedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isFocused) Color.White.copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                            .clickable {
                                focusManager.clearFocus()
                                viewModel.selectAndPlayTrack(track)
                            }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = if (isFocused) iPodAccentBlue else Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = Color.White.copy(0.6f),
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = track.duration,
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 6. QUEUE SCREEN
// ==========================================
@Composable
fun QueueScreen(viewModel: MusicPlayerViewModel) {
    val queue by viewModel.queue.collectAsState()
    val activeIndex by viewModel.currentQueueIndex.collectAsState()
    val focusedIndex by viewModel.focusedIndex.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        if (focusedIndex in queue.indices) {
            listState.animateScrollToItem(focusedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // Spotify-inspired Header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            Text(
                text = "Queue",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(queue) { index, track ->
                val isFocused = index == focusedIndex
                val isActivePlaying = index == activeIndex

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isFocused) Color.White.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                        .clickable { viewModel.selectAndPlayTrack(track) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = track.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = if (isActivePlaying) Color(0xFF1DB954) else if (isFocused) iPodAccentBlue else Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            color = Color.White.copy(0.6f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isActivePlaying) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Playing",
                            tint = Color(0xFF1DB954),
                            modifier = Modifier.padding(end = 6.dp).size(20.dp)
                        )
                    }

                    IconButton(
                        onClick = { viewModel.removeFromQueue(track.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RemoveCircle,
                            contentDescription = "Remove from Queue",
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// 7. MINI PLAYER DOCK
// ==========================================
@Composable
fun MiniPlayerDock(viewModel: MusicPlayerViewModel) {
    MiniYouTubePlayerBar(viewModel = viewModel)
}

@Composable
fun _MiniPlayerDockOld(viewModel: MusicPlayerViewModel) {
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

    if (currentTrack == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Color.Black.copy(alpha = 0.6f))
            .border(width = (0.5).dp, color = Color.White.copy(0.12f))
            .clickable { viewModel.setScreen(ScreenType.NOW_PLAYING) }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black)
        ) {
            AsyncImage(
                model = currentTrack?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentTrack?.title ?: "",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = currentTrack?.artist ?: "",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(
            onClick = { viewModel.togglePlayback() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "PlayPause",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ==========================================
// UTILS & HELPER DATA
// ==========================================
private fun formatMs(ms: Long): String {
    val totalSecs = ms / 1000
    val mins = totalSecs / 60
    val secs = totalSecs % 60
    return String.format("%01d:%02d", mins, secs)
}

private fun getLyricsForTrack(track: Track?): String {
    if (track == null) return "No track selected"
    return when (track.id) {
        "S9O_W6y0668" -> """
            [Chorus]
            Good day in my mind, safe to take a step out
            Get some air now, let your edge out
            Too late, I'm already in the backseat
            My heart's a runaway, I'm in the front seat
            Still wanna try, still believe in
            Good days, always, always
            Inside my mind, always, always
            
            [Verse 1]
            I worry about the future, I worry about the past
            I worry about the things that aren't meant to last
            But today is a good day, today is a good day
            I wanna see you happy, I wanna see you shine
            I wanna hold your hand and tell you that you're mine
            Good days are coming, good days are coming...
        """.trimIndent()
        "MSRcC6shJD4" -> """
            [Verse 1]
            I'm still a fan even though I was salty
            Hate to see you with some other broad, know you happy
            Hate to see you happy, hate to see you shine
            I'm still a fan, I'm still a fan
            
            [Chorus]
            I might kill my ex, not the best idea
            His new girlfriend's next, how'd I get here?
            I might kill my ex, I still love him though
            Rather be in jail than alone
            I did it all for love, I did it all for love...
        """.trimIndent()
        "4NRXx6U8ABQ" -> """
            [Verse 1]
            I've been on my own for long enough
            Maybe you can show me how to love, maybe
            I'm going through withdrawals
            You don't even have to do too much
            You can turn me on with just a touch, baby
            
            [Chorus]
            I look around and Sin City's cold and empty
            No one's around to judge me
            I can't see clearly when you're gone
            I said, ooh, I'm blinded by the lights
            No, I can't sleep until I feel your touch...
        """.trimIndent()
        else -> """
            [Verse 1]
            (Instrumental Intro)
            I hear the music playing in the distance
            It calls me to a place of sweet relief
            No worries here, no troubles in my mind
            Just the smooth rhythm of the beat
            
            [Chorus]
            Let the sound take over your soul
            Let the melodies make you whole
            iPod Player, playing on repeat
            Nothing else can match this retro heat...
        """.trimIndent()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Composable
fun RankedSpotifyCard(track: Track, rank: Int, isFocused: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .width(220.dp)
            .height(70.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.05f))
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) iPodAccentBlue else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable { onClick() }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.Center
        )
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(54.dp)
                .clip(RoundedCornerShape(6.dp))
                .shadow(4.dp, RoundedCornerShape(6.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ExploreSectionScreen(viewModel: MusicPlayerViewModel) {
    val title by viewModel.sectionDetailTitle.collectAsState()
    val tracks by viewModel.sectionDetailTracks.collectAsState()
    val focusedIndex by viewModel.focusedIndex.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(focusedIndex) {
        if (focusedIndex in tracks.indices) {
            listState.animateScrollToItem(focusedIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        ) {
            IconButton(
                onClick = { viewModel.setScreen(ScreenType.EXPLORE) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = iPodAccentBlue
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(tracks) { index, track ->
                val isFocused = index == focusedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isFocused) Color.White.copy(alpha = 0.15f)
                            else Color.White.copy(alpha = 0.03f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isFocused) iPodAccentBlue.copy(alpha = 0.7f) else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { viewModel.selectAndPlayTrack(track, tracks) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = track.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
