package com.example

import android.app.Application
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
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
import com.example.data.model.CuratedTracks
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
                            val current = viewModel.currentScreen.value
                            if (current == ScreenType.PLAYLIST_DETAIL) {
                                viewModel.setScreen(ScreenType.PLAYLISTS)
                            } else {
                                viewModel.setExpanded(false)
                            }
                        } else {
                            viewModel.setExpanded(true)
                        }
                    },
                    onPrevClick = {
                        val now = System.currentTimeMillis()
                        if (now - lastPrevClickTime < 450L) {
                            viewModel.playPrevious()
                        } else {
                            viewModel.updateProgress(0L)
                        }
                        lastPrevClickTime = now
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
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 6 })
    
    // Mapping pages to ScreenType index
    val pageToScreen = listOf(
        ScreenType.EXPLORE,
        ScreenType.LIKED,
        ScreenType.PLAYLISTS,
        ScreenType.SEARCH,
        ScreenType.QUEUE,
        ScreenType.NOW_PLAYING
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
        val displayStr = if (currentTrack != null) {
            "${currentTrack.title} • ${currentTrack.artist}"
        } else {
            "iPod Player"
        }
        Text(
            text = displayStr,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (currentUser != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onLogout() }
            ) {
                Text(
                    text = "Logout",
                    color = iPodAccentBlue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp)
                )
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

    val coverArtHeight by animateDpAsState(
        targetValue = if (isExpanded) 160.dp else 105.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "CoverArtHeightAnimation"
    )

    val coverSpacerHeight by animateDpAsState(
        targetValue = if (isExpanded) 14.dp else 6.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "CoverSpacerHeightAnimation"
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
            // Hidden WebView to keep audio playing
            Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                YouTubeWebViewPlayer(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Cover Art box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverArtHeight)
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

            Spacer(modifier = Modifier.height(coverSpacerHeight))

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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            val width = size.width
                            if (width > 0 && durationMs > 0) {
                                val fraction = offset.x / width
                                val newPosMs = (fraction * durationMs).toLong().coerceIn(0L, durationMs)
                                viewModel.seekTo(newPosMs)
                            }
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressValue)
                            .fillMaxHeight()
                            .background(Color.White, RoundedCornerShape(2.dp))
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatMs(progressMs), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                Text("-" + formatMs(maxOf(0L, durationMs - progressMs)),
                    color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            }

            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = iPodAccentBlue,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "kilobyte's AirPods",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = onShowLyrics, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Comment,
                            contentDescription = "Lyrics",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.setExpanded(true); viewModel.setScreen(ScreenType.QUEUE) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QueueMusic,
                            contentDescription = "Queue",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "Liked Songs",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        if (likedTracks.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("No liked songs yet.", color = Color.Gray, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(likedTracks) { index, track ->
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
                            .clickable { viewModel.selectAndPlayTrack(track, likedTracks) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
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
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                color = Color.White.copy(0.6f),
                                fontSize = 11.sp,
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
                                tint = iPodAccentBlue,
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "Playlists",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

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
                        .clickable { onCreatePlaylistClick() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = iPodAccentBlue,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Create Playlist",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Playlist items (Index shifted by +1)
            itemsIndexed(playlists) { index, playlist ->
                val listIdx = index + 1
                val isFocused = listIdx == focusedIndex

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
                        .clickable { viewModel.selectPlaylist(playlist) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.QueueMusic,
                        contentDescription = null,
                        tint = Color.White.copy(0.6f),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = { viewModel.deletePlaylist(playlist.id) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Playlist",
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Row header with back navigation
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
        ) {
            IconButton(
                onClick = { viewModel.setScreen(ScreenType.PLAYLISTS) },
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = iPodAccentBlue
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = playlist?.name ?: "Playlist Detail",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (songs.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Playlist is empty. Add songs!", color = Color.Gray, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(songs) { index, track ->
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
                            .clickable { viewModel.selectAndPlayTrack(track, songs) }
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
        // Material3 search text field with system keyboard triggers
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("Search YouTube...", color = Color.White.copy(0.4f), fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(0.6f)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.05f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.02f),
                focusedBorderColor = iPodAccentBlue,
                unfocusedBorderColor = Color.White.copy(alpha = 0.15f)
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("youtube_search_input")
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (isSearching) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = iPodAccentBlue, modifier = Modifier.size(30.dp))
            }
        } else if (results.isEmpty() && query.isNotBlank()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("No matching YouTube videos found.", color = Color.Gray, fontSize = 12.sp)
            }
        } else if (results.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("Type above to search online music.", color = Color.Gray, fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(results) { index, track ->
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
                                .size(42.dp)
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
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = "Playback Queue",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(queue) { index, track ->
                val isFocused = index == focusedIndex
                val isActivePlaying = index == activeIndex

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isFocused) Color.White.copy(alpha = 0.15f)
                            else if (isActivePlaying) iPodAccentBlue.copy(alpha = 0.08f)
                            else Color.White.copy(alpha = 0.03f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isFocused) iPodAccentBlue.copy(alpha = 0.7f) 
                                    else if (isActivePlaying) iPodAccentBlue.copy(alpha = 0.3f) 
                                    else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
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
                            .size(36.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            color = if (isActivePlaying) iPodAccentBlue else Color.White,
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

                    if (isActivePlaying) {
                        Text(
                            text = "Playing",
                            color = iPodAccentBlue,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 6.dp)
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
        val currentScreen by viewModel.currentScreen.collectAsState()

        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black)
        ) {
            if (currentScreen != ScreenType.NOW_PLAYING) {
                YouTubeWebViewPlayer(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AsyncImage(
                    model = currentTrack?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
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
