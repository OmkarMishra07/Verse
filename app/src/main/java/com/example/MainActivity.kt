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
import com.example.ui.screens.ChatRoomFullScreenOverlay
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.ui.zIndex
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
                                    // Force sync immediately on explicit login
                                    musicViewModel.onUserLoggedIn(user.uid, forceSync = true)
                                    // Directly update StateFlow — no waiting for AuthStateListener
                                    authViewModel.onUserSignedIn(user)
                                }
                            )
                        } else {
                            val context = androidx.compose.ui.platform.LocalContext.current
                            com.example.WebViewHolder.init(context, musicViewModel)
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
                            // The absolute best way to do this on all Android phones:
                            // Pops up a direct system dialog asking "Let app always run in background?"
                            // This flips the master switch for battery optimization (Unrestricted / Allow background activity)
                            val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = android.net.Uri.parse("package:$packageName")
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                // If the phone blocks the direct popup (some custom skins do), 
                                // fallback to the App Info page where the user can click "Battery"
                                val fallbackIntent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                fallbackIntent.data = android.net.Uri.parse("package:$packageName")
                                startActivity(fallbackIntent)
                            } catch (e2: Exception) {
                                android.util.Log.e("MainActivity", "Failed to open settings: ${e2.message}")
                            }
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
    
    // Greeting state
    var showGreeting by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(3000)
        showGreeting = false
    }
    
    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
    
    var customNickname by remember { 
        mutableStateOf(prefs.getString("customNickname_${currentUser?.uid}", null)) 
    }
    var customWelcomeMessage by remember { 
        mutableStateOf(prefs.getString("customWelcomeMessage_${currentUser?.uid}", null)) 
    }

    LaunchedEffect(currentUser) {
        if (currentUser != null) {
            val mFirebaseRemoteConfig = com.google.firebase.remoteconfig.FirebaseRemoteConfig.getInstance()
            val configSettings = com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(43200) // 12 hours cache
                .build()
            mFirebaseRemoteConfig.setConfigSettingsAsync(configSettings)
            
            mFirebaseRemoteConfig.fetchAndActivate()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val specialUids = mFirebaseRemoteConfig.getString("special_welcome_uids")
                        currentUser.uid?.let { uid ->
                            if (specialUids.contains(uid)) {
                                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                                    .collection("users").document(uid).get()
                                    .addOnSuccessListener { doc ->
                                        if (doc.exists()) {
                                            val newNick = doc.getString("customNickname")?.takeIf { it.isNotBlank() }
                                            val newMsg = doc.getString("customWelcomeMessage")?.takeIf { it.isNotBlank() }
                                            
                                            if (customNickname != newNick || customWelcomeMessage != newMsg) {
                                                customNickname = newNick
                                                customWelcomeMessage = newMsg
                                                prefs.edit()
                                                    .putString("customNickname_$uid", newNick)
                                                    .putString("customWelcomeMessage_$uid", newMsg)
                                                    .apply()
                                            }
                                        }
                                    }
                            }
                        }
                    }
                }
        }
    }

    val defaultUserName = currentUser?.displayName?.split(" ")?.firstOrNull() ?: "there"
    val finalUserName = customNickname ?: defaultUserName
    val finalWelcomePrefix = customWelcomeMessage ?: "Welcome,"
    
    val fullGreeting = "$finalWelcomePrefix $finalUserName."
    
    // Dialog states
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var showImportPlaylistDialog by remember { mutableStateOf(false) }
    var playlistToAddTo by remember { mutableStateOf<Track?>(null) }
    var showLyricsDialog by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.example.data.network.UpdateHelper.UpdateInfo?>(null) }
    
    LaunchedEffect(Unit) {
        val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        val info = com.example.data.network.UpdateHelper.checkForUpdates(currentVersion)
        if (info != null && info.isUpdateAvailable) {
            updateInfo = info
        }
    }
    
    // Fetch state for UI
    val isExpanded by viewModel.isExpanded.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()

    // Back navigation logic
    var backPressTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        if (viewModel.isChatOpen.value) {
            viewModel.setIsChatOpen(false)
        } else if (currentScreen != ScreenType.EXPLORE) {
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
    
    // Update Dialog
    if (updateInfo != null) {
        AlertDialog(
            onDismissRequest = { /* Mandatory update, cannot dismiss by tapping outside */ },
            title = { Text("Update Available", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { 
                Text("A new version (${updateInfo!!.latestVersion}) is available! You must update to the latest version to continue.", color = Color.White.copy(alpha=0.9f))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://verse.geetprince.me"))
                        context.startActivity(intent)
                        (context as? android.app.Activity)?.finish()
                    }
                ) {
                    Text("Download Now", color = iPodAccentBlue, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { (context as? android.app.Activity)?.finish() }) {
                    Text("Exit", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E2633)
        )
    }

    val isChatOpen by viewModel.isChatOpen.collectAsState()

    iPodDeviceFrame(
        viewModel = viewModel,
        isExpanded = isExpanded,
        showCreatePlaylistDialog = { showCreatePlaylistDialog = true },
        showImportPlaylistDialog = { showImportPlaylistDialog = true },
        playlistToAddTo = playlistToAddTo,
        onAddTrackToPlaylist = { playlistToAddTo = it },
        onShowLyrics = { showLyricsDialog = true },
        currentUser = currentUser,
        onLogout = onLogout
    )

    AnimatedVisibility(
        visible = isChatOpen,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        ChatRoomFullScreenOverlay(viewModel = viewModel)
    }
    
    val isHistoryOpen by viewModel.isHistoryOpen.collectAsState()
    AnimatedVisibility(
        visible = isHistoryOpen,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        com.example.ui.screens.HistoryFullScreenOverlay(
            viewModel = viewModel, 
            onBack = { viewModel.isHistoryOpen.value = false }
        )
    }

    AnimatedVisibility(
        visible = showGreeting,
        enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(800)),
        exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(800)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fullGreeting,
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp)
            )
        }
    }

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

    if (showImportPlaylistDialog) {
        var importShareCode by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportPlaylistDialog = false },
            title = { Text("Import Shared Playlist", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = importShareCode,
                    onValueChange = { importShareCode = it },
                    label = { Text("Share Code", color = Color.Gray) },
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
                        val code = importShareCode.trim()
                        val parts = code.split("_")
                        if (parts.size == 2) {
                            val ownerId = parts[0].trim()
                            val pid = parts[1].trim().toLongOrNull()
                            if (pid != null) {
                                viewModel.importSharedPlaylist(ownerId, pid)
                                Toast.makeText(context, "Importing playlist...", Toast.LENGTH_SHORT).show()
                                showImportPlaylistDialog = false
                            } else {
                                Toast.makeText(context, "Invalid share code", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Invalid share code", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Import", color = iPodAccentBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportPlaylistDialog = false }) {
                    Text("Cancel", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF1E2633)
        )
    }

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

    if (showLyricsDialog) {
        com.example.ui.components.SyncedLyricsDialog(
            track = currentTrack,
            currentPositionMs = currentPositionMs,
            onDismissRequest = { showLyricsDialog = false },
            onSeek = { ms -> viewModel.seekTo(ms) }
        )
    }
}

@Composable
fun iPodDeviceFrame(
    viewModel: MusicPlayerViewModel,
    isExpanded: Boolean,
    showCreatePlaylistDialog: () -> Unit,
    showImportPlaylistDialog: () -> Unit,
    playlistToAddTo: Track?,
    onAddTrackToPlaylist: (Track) -> Unit,
    onShowLyrics: () -> Unit,
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onLogout: () -> Unit
) {
    val displayWeight = 1.2f
    val wheelSpacing = 12.dp

    val currentScreen by viewModel.currentScreen.collectAsState()
    val isChatOpen by viewModel.isChatOpen.collectAsState()
    val isModernMode by viewModel.isModernMode.collectAsState()

    val showWheel = !isModernMode && !(currentScreen == ScreenType.JAMMING && isChatOpen)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isModernMode) androidx.compose.ui.graphics.SolidColor(Color.Black) else Brush.linearGradient(
                        colors = listOf(iPodChassis, iPodChassisDark)
                    )
                )
                .padding(
                    horizontal = if (isModernMode) 0.dp else 14.dp,
                    vertical = if (isModernMode) 0.dp else 20.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            iPodScreenDisplay(
                viewModel = viewModel,
                isExpanded = isExpanded,
                modifier = Modifier.weight(displayWeight),
                showCreatePlaylistDialog = showCreatePlaylistDialog,
                showImportPlaylistDialog = showImportPlaylistDialog,
                onAddTrackToPlaylist = onAddTrackToPlaylist,
                onShowLyrics = onShowLyrics,
                currentUser = currentUser,
                onLogout = onLogout
            )
    
            AnimatedVisibility(
                visible = showWheel,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(wheelSpacing))
    
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ClickWheel(
                            viewModel = viewModel,
                            modifier = Modifier.padding(bottom = 8.dp),
                            onMenuClick = {
                                if (viewModel.tutorialState.value == 4) {
                                    viewModel.setTutorialState(5)
                                }
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
                                if (viewModel.currentScreen.value == ScreenType.LIBRARY) {
                                    viewModel.setLibraryTab(0)
                                } else {
                                    viewModel.playPrevious()
                                }
                            },
                            onNextClick = { 
                                if (viewModel.currentScreen.value == ScreenType.LIBRARY) {
                                    viewModel.setLibraryTab(1)
                                } else {
                                    viewModel.playNext()
                                }
                            },
                            onPlayPauseClick = { viewModel.togglePlayback() },
                            onCenterClick = { viewModel.onCenterPressed() }
                        )
    
                        VolumeControlPopup(
                            viewModel = viewModel,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = 24.dp)
                        )
                    }
                }
            }
    
            // Removed the ugly fullscreen popup overlay
        }

        val tutState by viewModel.tutorialState.collectAsState()
        if (showWheel && tutState < 6) {
            Box(
                modifier = Modifier
                    .align(if (tutState == 5) Alignment.TopEnd else Alignment.BottomCenter)
                    .padding(
                        top = if (tutState == 5) 80.dp else 0.dp,
                        bottom = if (tutState == 5) 0.dp else 290.dp,
                        start = 24.dp,
                        end = 24.dp
                    )
                    .background(Color(0xE6000000), RoundedCornerShape(12.dp))
                    .border(2.dp, iPodAccentBlue, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Column(horizontalAlignment = if (tutState == 5) Alignment.End else Alignment.CenterHorizontally) {
                    if (tutState == 5) {
                        Text("↗️", fontSize = 28.sp, modifier = Modifier.padding(bottom = 8.dp, end = 8.dp))
                    }
                    Text(if (tutState == 5) "One more thing!" else "Welcome to Verse!", color = iPodAccentBlue, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (tutState == 1) "Try rotating the wheel in a circular motion to scroll through items. 🔄\n(Scroll now to continue)"
                        else if (tutState == 2) "Great! Now press and hold the center button to open the Quick Access Menu. 🎯\n(Long-press now to continue)"
                        else if (tutState == 3) "Awesome! You can also swipe left or right on the screen above the wheel to swap between pages. ↔️\n(Swipe now to continue)"
                        else if (tutState == 4) "Press the Menu button at the top of the wheel to enter a Jam Session! 👆\n(Press Menu now to continue)"
                        else "Prefer a modern look?\nTap your Profile picture at the top right to open Settings!",
                        color = Color.White, textAlign = if (tutState == 5) TextAlign.End else TextAlign.Center, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    if (tutState < 5) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("👇", fontSize = 28.sp)
                    }
                }
            }
        }
        if (isModernMode) {
            GlassBottomNavBar(
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 24.dp, end = 24.dp)
            )
        }
    }
}

@Composable
fun GlassBottomNavBar(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier
) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xB3000000)) // 70% black for frosted glass look
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
            .padding(vertical = 12.dp, horizontal = 20.dp), // reduced padding to fit 5 items
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavBarIcon(
            icon = Icons.Default.Home,
            label = "Explore",
            isSelected = currentScreen == ScreenType.EXPLORE || currentScreen == ScreenType.EXPLORE_SECTION,
            onClick = { viewModel.setScreen(ScreenType.EXPLORE) }
        )
        NavBarIcon(
            icon = Icons.Default.Search,
            label = "Search",
            isSelected = currentScreen == ScreenType.SEARCH,
            onClick = { viewModel.setScreen(ScreenType.SEARCH) }
        )
        NavBarIcon(
            icon = Icons.Default.PlayArrow,
            label = "Player",
            isSelected = currentScreen == ScreenType.NOW_PLAYING || currentScreen == ScreenType.QUEUE,
            onClick = { viewModel.setScreen(ScreenType.NOW_PLAYING) }
        )
        NavBarIcon(
            icon = Icons.Default.Favorite,
            label = "Library",
            isSelected = currentScreen == ScreenType.LIBRARY || currentScreen == ScreenType.PLAYLIST_DETAIL,
            onClick = { viewModel.setScreen(ScreenType.LIBRARY) }
        )
        NavBarIcon(
            icon = Icons.Default.Person, // Jam icon
            label = "Jam",
            isSelected = currentScreen == ScreenType.JAMMING,
            onClick = { viewModel.setScreen(ScreenType.JAMMING) }
        )
    }
}

@Composable
fun NavBarIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isSelected) iPodAccentBlue else Color.Gray,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isSelected) iPodAccentBlue else Color.Gray,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun iPodScreenDisplay(
    viewModel: MusicPlayerViewModel,
    isExpanded: Boolean,
    modifier: Modifier = Modifier,
    showCreatePlaylistDialog: () -> Unit,
    showImportPlaylistDialog: () -> Unit,
    onAddTrackToPlaylist: (Track) -> Unit,
    onShowLyrics: () -> Unit,
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onLogout: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val currentScreen by viewModel.currentScreen.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isModernMode by viewModel.isModernMode.collectAsState()

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 5 })
    
    val pageToScreen = listOf(
        ScreenType.EXPLORE,
        ScreenType.SEARCH,
        ScreenType.NOW_PLAYING,
        ScreenType.LIBRARY,
        ScreenType.JAMMING
    )

    LaunchedEffect(currentScreen) {
        val targetPage = pageToScreen.indexOf(currentScreen)
        if (targetPage != -1 && pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        val targetScreen = pageToScreen[pagerState.settledPage]
        if (viewModel.currentScreen.value != targetScreen && targetScreen != ScreenType.PLAYLIST_DETAIL) {
            viewModel.setScreen(targetScreen)
            if (viewModel.tutorialState.value == 3) {
                viewModel.setTutorialState(4)
            }
        }
    }

    // Force user to land on EXPLORE page on fresh composition (app launch/restart)
    LaunchedEffect(Unit) {
        viewModel.setScreen(ScreenType.EXPLORE)
        pagerState.scrollToPage(0)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (isModernMode) 0.dp else 4.dp, RoundedCornerShape(if (isModernMode) 0.dp else 28.dp), clip = true)
            .clip(RoundedCornerShape(if (isModernMode) 0.dp else 28.dp))
            .background(if (isModernMode) Color.Black else iPodDisplayBg)
    ) {
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

        Column(modifier = Modifier.fillMaxSize()) {
            iPodStatusBar(viewModel, currentTrack, currentUser, onLogout)

            Box(modifier = Modifier.size(1.dp).alpha(0f)) {
                YouTubeWebViewPlayer(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val isOverlayScreen = currentScreen == ScreenType.PLAYLIST_DETAIL || currentScreen == ScreenType.EXPLORE_SECTION || currentScreen == ScreenType.QUEUE
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (isOverlayScreen) 0f else 1f),
                    userScrollEnabled = true,
                    beyondViewportPageCount = 6
                ) { pageIndex ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val pageOffset = (pagerState.currentPage - pageIndex) + pagerState.currentPageOffsetFraction
                                val absOffset = kotlin.math.abs(pageOffset)
                                val fraction = 1f - absOffset.coerceIn(0f, 1f)
                                
                                scaleX = 0.85f + (0.15f * fraction)
                                scaleY = 0.85f + (0.15f * fraction)
                                alpha = 0.3f + (0.7f * fraction)
                            }
                    ) {
                        when (pageToScreen[pageIndex]) {
                            ScreenType.EXPLORE -> ExploreScreen(viewModel = viewModel)
                            ScreenType.SEARCH -> SearchScreen(viewModel = viewModel)
                            ScreenType.NOW_PLAYING -> NowPlayingScreen(
                                viewModel = viewModel,
                                onAddTrackToPlaylist = onAddTrackToPlaylist,
                                onShowLyrics = onShowLyrics
                            )
                            ScreenType.LIBRARY -> LibraryScreen(
                                viewModel = viewModel,
                                onCreatePlaylistClick = showCreatePlaylistDialog,
                                onImportPlaylistClick = showImportPlaylistDialog
                            )
                            ScreenType.JAMMING -> JammingScreen(
                                viewModel = viewModel,
                                onAddTrackToPlaylist = onAddTrackToPlaylist,
                                onShowLyrics = onShowLyrics
                            )
                            else -> {}
                        }
                    }
                }

                if (currentScreen == ScreenType.PLAYLIST_DETAIL) {
                    PlaylistDetailScreen(viewModel = viewModel)
                }
                if (currentScreen == ScreenType.EXPLORE_SECTION) {
                    ExploreSectionScreen(viewModel = viewModel)
                }
                if (currentScreen == ScreenType.QUEUE) {
                    QueueScreen(viewModel = viewModel)
                }
            }

            AnimatedVisibility(
                visible = isExpanded && currentScreen != ScreenType.NOW_PLAYING,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                MiniYouTubePlayerBar(viewModel = viewModel)
            }
            
            val isModernMode by viewModel.isModernMode.collectAsState()
            if (isModernMode) {
                Spacer(modifier = Modifier.height(90.dp))
            }
        }
    }
}

@Composable
fun iPodStatusBar(
    viewModel: MusicPlayerViewModel,
    currentTrack: Track?,
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onLogout: () -> Unit
) {
    var showProfileDialog by remember { mutableStateOf(false) }
    val isModernMode by viewModel.isModernMode.collectAsState()
    val tutState by viewModel.tutorialState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "Unknown"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(Color.Black.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Verse",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.weight(1f))
        
        // History Icon
        IconButton(
            onClick = { viewModel.isHistoryOpen.value = true },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = "History",
                tint = Color.White.copy(alpha = 0.9f)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        
        if (currentUser != null) {
            val photoUrl = currentUser.photoUrl
            val name = currentUser.displayName.takeIf { !it.isNullOrBlank() } 
                ?: currentUser.email 
                ?: "U"
            
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .clickable { 
                        showProfileDialog = true 
                        if (tutState == 5) viewModel.setTutorialState(6)
                    }
            ) {
                if (photoUrl != null) {
                    coil.compose.AsyncImage(
                        model = photoUrl,
                        contentDescription = "Profile",
                        modifier = Modifier
                            .size(26.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color.DarkGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = name.first().uppercase(),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showProfileDialog && currentUser != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showProfileDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD9000000)) // Translucent black background
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 48.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { showProfileDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Profile Section
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val photoUrl = currentUser.photoUrl
                        val name = currentUser.displayName.takeIf { !it.isNullOrBlank() } ?: currentUser.email ?: "User"
                        if (photoUrl != null) {
                            coil.compose.AsyncImage(
                                model = photoUrl,
                                contentDescription = "Profile Picture",
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(iPodAccentBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = name.first().uppercase(),
                                    color = Color.White,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(20.dp))
                        
                        Column {
                            Text(text = name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = currentUser.email ?: "", color = Color.Gray, fontSize = 14.sp)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(40.dp))
                    
                    // Settings Options
                    SettingsItemRow(icon = Icons.Default.Person, title = "Account Details", subtitle = "Manage your account")
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .padding(vertical = 12.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(20.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Modern UI Mode", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                            Text(if (isModernMode) "Full screen with Bottom Nav" else "Classic iPod Wheel", color = Color.Gray, fontSize = 13.sp)
                        }
                        androidx.compose.material3.Switch(
                            checked = isModernMode,
                            onCheckedChange = { 
                                viewModel.setModernMode(it)
                                if (tutState == 6) viewModel.setTutorialState(7)
                            },
                            colors = androidx.compose.material3.SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = iPodAccentBlue,
                                uncheckedThumbColor = Color.Gray,
                                uncheckedTrackColor = Color.DarkGray
                            )
                        )
                    }
                    
                    if (tutState == 6) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .background(Color(0xE6000000), RoundedCornerShape(12.dp))
                                .border(2.dp, iPodAccentBlue, RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                                Text("⬆️", fontSize = 28.sp, modifier = Modifier.padding(bottom = 8.dp, end = 24.dp))
                                Text("Turn this on to use the app in Full Screen mode! You can always switch back to Classic mode here.", color = Color.White, textAlign = TextAlign.End, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    SettingsItemRow(icon = Icons.Default.Info, title = "About Verse", subtitle = "Version $appVersion")
                    
                    Spacer(modifier = Modifier.weight(1f))
                    
                    SettingsItemRow(
                        icon = Icons.Default.ExitToApp,
                        title = "Log Out",
                        subtitle = "Sign out of this device",
                        isDestructive = true,
                        onClick = {
                            showProfileDialog = false
                            onLogout()
                        }
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsItemRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isDestructive) Color.Red else Color.White,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isDestructive) Color.Red else Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            if (subtitle.isNotEmpty()) {
                Text(text = subtitle, color = Color.Gray, fontSize = 13.sp)
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

    val displayFraction = when {
        isDragging -> dragFraction
        durationMs > 0L -> (progressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    val animatedFraction by animateFloatAsState(
        targetValue = displayFraction,
        animationSpec = if (isDragging)
            snap()
        else
            tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "SeekBarAnimation"
    )

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
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(safeBufFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(2.dp))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(iPodAccentBlue, RoundedCornerShape(2.dp))
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = ((widthDp - thumbSize) * animatedFraction.coerceIn(0f, 1f)).coerceAtLeast(0.dp))
                    .size(thumbSize)
                    .background(Color.White, CircleShape)
            )

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

    var showSearch by remember { mutableStateOf(false) }
    var inlineQuery by remember { mutableStateOf("") }
    var inlineResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

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

    val progressValue = if (durationMs > 0) progressMs.toFloat() / durationMs.toFloat() else 0f
    val isLiked = currentTrack?.let { track -> likedTracks.any { it.id == track.id } } ?: false

    if (currentTrack == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Select a song to start", color = Color.Gray)
        }
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        AnimatedVisibility(
            visible = showSearch,
            enter = expandVertically() + fadeIn(),
            exit  = shrinkVertically() + fadeOut(),
            modifier = Modifier.zIndex(1f).align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xB3000000))
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = inlineQuery,
                    onValueChange = { inlineQuery = it },
                    placeholder = {
                        Text("Search YouTube...", color = Color.Gray)
                    },
                    trailingIcon = {
                        IconButton(onClick = {
                            showSearch = false; inlineQuery = ""; inlineResults = emptyList()
                            focusManager.clearFocus()
                        }) {
                            Icon(Icons.Filled.Close, null, tint = Color.White)
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = iPodAccentBlue, unfocusedBorderColor = Color.DarkGray
                    ),
                    modifier = Modifier.fillMaxWidth()
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
                            .heightIn(max = 280.dp)
                            .padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        color = Color.Gray,
                                        fontSize = 12.sp,
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
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = { viewModel.setScreen(ScreenType.QUEUE) }) {
                    Icon(
                        imageVector = Icons.Default.QueueMusic,
                        contentDescription = "Queue",
                        tint = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lyrics",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onShowLyrics() }
                )

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

                val audioManager = context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                var deviceName by remember { mutableStateOf("Speaker") }
                
                LaunchedEffect(Unit) {
                    while(true) {
                        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        val active = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET }
                            ?: devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET }
                            ?: devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        
                        deviceName = active?.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Speaker"
                        kotlinx.coroutines.delay(2000)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        try {
                            val intent = android.content.Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                                putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Media output switcher not found", e)
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = iPodAccentBlue,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = deviceName,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 100.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            val isPlaying by viewModel.isPlaying.collectAsState()
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.playPrevious() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
                }
                FloatingActionButton(
                    onClick = { viewModel.togglePlayback() },
                    containerColor = iPodAccentBlue,
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape
                ) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { viewModel.playNext() }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
        }
    }
}

@Composable
fun VolumeControlPopup(viewModel: MusicPlayerViewModel, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val vol by viewModel.volume.collectAsState()
    
    LaunchedEffect(expanded, vol) {
        if (expanded) {
            kotlinx.coroutines.delay(3000)
            expanded = false
        }
    }
    
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .size(32.dp)
                .background(Color.White.copy(alpha = 0.08f), androidx.compose.foundation.shape.CircleShape)
        ) {
            Icon(
                imageVector = Icons.Filled.VolumeUp,
                contentDescription = "Volume",
                tint = iPodAccentBlue,
                modifier = Modifier.size(16.dp)
            )
        }
        
        androidx.compose.animation.AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .width(32.dp)
                    .height(100.dp)
                    .background(Color.White.copy(0.1f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                val newVol = (vol - dragAmount / 100.dp.toPx()).coerceIn(0f, 1f)
                                viewModel.setVolume(newVol)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val newVol = (1f - (offset.y / 100.dp.toPx())).coerceIn(0f, 1f)
                                viewModel.setVolume(newVol)
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(vol)
                            .align(Alignment.BottomCenter)
                            .background(iPodAccentBlue, RoundedCornerShape(16.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun JammingScreen(
    viewModel: MusicPlayerViewModel,
    onAddTrackToPlaylist: (Track) -> Unit,
    onShowLyrics: () -> Unit
) {
    val context = LocalContext.current
    var inputRoomCode by remember { mutableStateOf("") }
    
    val jammingRoomId by viewModel.jammingRoomId.collectAsState()
    val roomState by viewModel.jammingRoomState.collectAsState()
    val inRoom = jammingRoomId.isNotBlank()

    val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
    val myName = remember { currentUser?.displayName?.takeIf { it.isNotBlank() } ?: "User-${(100..999).random()}" }

    val coroutineScope = rememberCoroutineScope()
    
    // State required for the Jamming screen UI when in a room:
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val hasUnreadMessages by viewModel.hasUnreadMessages.collectAsState()
    val isChatOpen by viewModel.isChatOpen.collectAsState()
    var showShareDialog by remember { mutableStateOf(false) }
    var showJamSearch by remember { mutableStateOf(false) }

    val chatMessages by viewModel.jammingRoomMessages.collectAsState()

    LaunchedEffect(chatMessages.size) {
        if (!isChatOpen && chatMessages.isNotEmpty()) {
            viewModel.setHasUnreadMessages(true)
        }
    }

    if (!inRoom) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("Jamming Session", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Listen together with friends in real-time", color = Color.Gray, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(
                value = inputRoomCode,
                onValueChange = { inputRoomCode = it.uppercase() },
                label = { Text("Room Code", color = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { 
                val code = inputRoomCode.trim()
                if (code.isNotEmpty()) {
                    coroutineScope.launch {
                        val success = com.example.data.remote.JammingService.joinRoom(code, myName)
                        if (success) {
                            viewModel.setJammingRoomId(code)
                        } else {
                            android.widget.Toast.makeText(context, "Failed to join room. Check code.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = iPodAccentBlue), modifier = Modifier.fillMaxWidth(0.6f)) {
                Text("Join Room")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text("OR", color = Color.Gray)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { 
                val charPool : List<Char> = ('A'..'Z') + ('0'..'9')
                val code = (1..5).map { charPool.random() }.joinToString("")
                coroutineScope.launch { 
                    val success = com.example.data.remote.JammingService.createRoom(code, currentUser?.uid ?: "host", myName) 
                    if (success) {
                        viewModel.setJammingRoomId(code)
                    } else {
                        android.widget.Toast.makeText(context, "Failed to create room.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray), modifier = Modifier.fillMaxWidth(0.6f)) {
                Text("Create New Room")
            }
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                "Rooms are automatically deleted after 2 hours of inactivity.",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    } else {
        if (showShareDialog) {
            AlertDialog(
                onDismissRequest = { showShareDialog = false },
                title = { Text("Share Room", color = Color.White) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Room Code: $jammingRoomId", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, "Join my jamming session! Room Code: $jammingRoomId")
                                type = "text/plain"
                            }
                            context.startActivity(android.content.Intent.createChooser(sendIntent, null))
                        }) {
                            Text("Share Link")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showShareDialog = false }) { Text("Close") } },
                containerColor = Color.DarkGray
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("${roomState?.hostName ?: "Unknown"}'s Room", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Code: $jammingRoomId", color = iPodAccentBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { showJamSearch = !showJamSearch }) {
                        Icon(if (showJamSearch) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                    }
                    Box(modifier = Modifier.padding(end = 8.dp).clickable { 
                        viewModel.setIsChatOpen(true)
                        viewModel.setHasUnreadMessages(false)
                    }) {
                        Icon(Icons.Default.Chat, contentDescription = "Chat", tint = Color.White, modifier = Modifier.padding(8.dp))
                        if (hasUnreadMessages) {
                            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(8.dp).clip(CircleShape).background(Color.Red))
                        }
                    }
                    IconButton(onClick = { 
                        coroutineScope.launch { com.example.data.remote.JammingService.leaveRoom(jammingRoomId, myName) }
                        viewModel.setJammingRoomId("")
                    }) {
                        Icon(Icons.Default.ExitToApp, contentDescription = "Leave", tint = Color.Red, modifier = Modifier.size(32.dp))
                    }
                }
            }
            
            val participants = roomState?.participants ?: emptyList()
            if (participants.isNotEmpty()) {
                Text("Listeners (${participants.size})", color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp)
                LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(participants.size) { index ->
                        val participant = participants[index]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color.DarkGray), contentAlignment = Alignment.Center) {
                                Text(participant.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(participant, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(60.dp), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            
            if (currentTrack != null) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), 
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val size = minOf(maxWidth, maxHeight, 300.dp)
                        coil.compose.AsyncImage(
                            model = currentTrack?.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(size)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(currentTrack?.title ?: "", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(currentTrack?.artist ?: "", color = Color.Gray, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    SeekBar(viewModel = viewModel)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(formatMs(currentPositionMs), color = Color.Gray, fontSize = 11.sp)
                        Text(formatMs(durationMs), color = Color.Gray, fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.playPrevious() }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                        FloatingActionButton(
                            onClick = { viewModel.togglePlayback() },
                            containerColor = iPodAccentBlue,
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape
                        ) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(onClick = { viewModel.playNext() }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        } // End of main Column
            
        if (showJamSearch) {
            var jamSearchQuery by remember { mutableStateOf("") }
            var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
            var isSearching by remember { mutableStateOf(false) }
            val focusRequester = remember { FocusRequester() }
            
            LaunchedEffect(showJamSearch) {
                if (showJamSearch) {
                    focusRequester.requestFocus()
                }
            }
            
            val performSearch = {
                if (jamSearchQuery.isNotBlank()) {
                    isSearching = true
                    coroutineScope.launch {
                        try {
                            searchResults = com.example.data.network.YouTubeSearchHelper.search(jamSearchQuery).map { yt -> 
                                Track(id = yt.videoId, title = yt.title, artist = yt.artist, album = "YouTube", thumbnailUrl = yt.thumbnailUrl, duration = yt.duration) 
                            }
                        } finally {
                            isSearching = false
                        }
                    }
                }
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 64.dp)
                    .padding(horizontal = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(16.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xB3000000)) // Semi-transparent for glass effect
                        .padding(12.dp)
                        .heightIn(max = 400.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedTextField(
                            value = jamSearchQuery,
                            onValueChange = { jamSearchQuery = it },
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            placeholder = { Text("Search YouTube...", color = Color.Gray) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { performSearch() }),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = iPodAccentBlue, unfocusedBorderColor = Color.DarkGray
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = { performSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                        }
                    }
                    if (isSearching) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(color = iPodAccentBlue)
                        }
                    } else if (searchResults.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                            items(searchResults.size) { idx ->
                                val track = searchResults[idx]
                                Row(modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.selectAndPlayTrackNoRedirect(track)
                                    showJamSearch = false
                                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    coil.compose.AsyncImage(model = track.thumbnailUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(track.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artist, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } // End of outer Box
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
    val globalOrLocalHits by viewModel.globalOrLocalHits.collectAsState()
    val moodTracks by viewModel.moodTracks.collectAsState()
    val partyTracks by viewModel.partyTracks.collectAsState()
    val isLoading by viewModel.isExploreLoading.collectAsState()
    
    val pageScrollState = rememberScrollState()
    val songsRowState = rememberLazyListState()
    val albumsRowState = rememberLazyListState()
    val releasesRowState = rememberLazyListState()
    val bollywoodRowState = rememberLazyListState()
    val globalLocalRowState = rememberLazyListState()
    val moodRowState = rememberLazyListState()
    val partyRowState = rememberLazyListState()

    val top10RowState = rememberLazyListState()
    val top10Hits = trendingSongs.take(10)

    val flatList = top10Hits + trendingSongs + trendingAlbums + newReleases + bollywoodHits + globalOrLocalHits + moodTracks + partyTracks

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
        } else if (focusedIndex < top10Hits.size + trendingSongs.size + trendingAlbums.size + newReleases.size + bollywoodHits.size) {
            val idx = focusedIndex - top10Hits.size - trendingSongs.size - trendingAlbums.size - newReleases.size
            bollywoodRowState.animateScrollToItem(maxOf(0, idx))
        } else if (focusedIndex < top10Hits.size + trendingSongs.size + trendingAlbums.size + newReleases.size + bollywoodHits.size + globalOrLocalHits.size) {
            val idx = focusedIndex - top10Hits.size - trendingSongs.size - trendingAlbums.size - newReleases.size - bollywoodHits.size
            globalLocalRowState.animateScrollToItem(maxOf(0, idx))
        } else if (focusedIndex < top10Hits.size + trendingSongs.size + trendingAlbums.size + newReleases.size + bollywoodHits.size + globalOrLocalHits.size + moodTracks.size) {
            val idx = focusedIndex - top10Hits.size - trendingSongs.size - trendingAlbums.size - newReleases.size - bollywoodHits.size - globalOrLocalHits.size
            moodRowState.animateScrollToItem(maxOf(0, idx))
        } else if (focusedIndex < flatList.size) {
            val idx = focusedIndex - top10Hits.size - trendingSongs.size - trendingAlbums.size - newReleases.size - bollywoodHits.size - globalOrLocalHits.size - moodTracks.size
            partyRowState.animateScrollToItem(maxOf(0, idx))
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

        if (globalOrLocalHits.isNotEmpty()) {
            val title = if (exploreRegion == MusicPlayerViewModel.ExploreRegion.INDIA) "Global Hits" else "Local Indian Hits"
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("View All", color = iPodAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.openExploreSection(title, globalOrLocalHits) })
            }
            val offset = top10Hits.size + trendingSongs.size + trendingAlbums.size + newReleases.size + bollywoodHits.size
            LazyRow(
                state = globalLocalRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(globalOrLocalHits.take(15)) { index, track ->
                    val isFocused = (index + offset) == focusedIndex
                    SpotifyCard(track = track, isFocused = isFocused) { viewModel.selectAndPlayTrack(track, flatList) }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        if (moodTracks.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Mood & Chill", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("View All", color = iPodAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.openExploreSection("Mood & Chill", moodTracks) })
            }
            val offset = top10Hits.size + trendingSongs.size + trendingAlbums.size + newReleases.size + bollywoodHits.size + globalOrLocalHits.size
            LazyRow(
                state = moodRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(moodTracks.take(15)) { index, track ->
                    val isFocused = (index + offset) == focusedIndex
                    SpotifyCard(track = track, isFocused = isFocused) { viewModel.selectAndPlayTrack(track, flatList) }
                }
            }
            Spacer(modifier = Modifier.height(28.dp))
        }

        if (partyTracks.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Party & Dance Hits", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("View All", color = iPodAccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { viewModel.openExploreSection("Party & Dance Hits", partyTracks) })
            }
            val offset = top10Hits.size + trendingSongs.size + trendingAlbums.size + newReleases.size + bollywoodHits.size + globalOrLocalHits.size + moodTracks.size
            LazyRow(
                state = partyRowState,
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(partyTracks.take(15)) { index, track ->
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
// 3a. LIBRARY SCREEN (Tabs for Liked and Playlists)
// ==========================================
@Composable
fun LibraryScreen(
    viewModel: MusicPlayerViewModel,
    onCreatePlaylistClick: () -> Unit,
    onImportPlaylistClick: () -> Unit
) {
    val selectedTab by viewModel.libraryTab.collectAsState()
    val tabs = listOf("Liked Songs", "Playlists")
    val pagerState = rememberPagerState(initialPage = selectedTab, pageCount = { 2 })

    // Sync tab clicks to pager
    LaunchedEffect(selectedTab) {
        if (pagerState.currentPage != selectedTab) {
            pagerState.animateScrollToPage(selectedTab)
        }
    }
    
    // Sync pager swipes to tab state
    LaunchedEffect(pagerState.currentPage) {
        if (selectedTab != pagerState.currentPage) {
            viewModel.setLibraryTab(pagerState.currentPage)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        androidx.compose.material3.TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = iPodAccentBlue,
            indicator = { tabPositions ->
                androidx.compose.material3.TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = iPodAccentBlue
                )
            }
        ) {
            tabs.forEachIndexed { index, title ->
                androidx.compose.material3.Tab(
                    selected = selectedTab == index,
                    onClick = { viewModel.setLibraryTab(index) },
                    text = { 
                        Text(
                            text = title, 
                            color = if (selectedTab == index) iPodAccentBlue else Color.Gray,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        ) 
                    }
                )
            }
        }

        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().weight(1f)) { page ->
            if (page == 0) {
                LikedScreen(viewModel = viewModel)
            } else {
                PlaylistsScreen(viewModel = viewModel, onCreatePlaylistClick = onCreatePlaylistClick, onImportPlaylistClick = onImportPlaylistClick)
            }
        }
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
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
    onCreatePlaylistClick: () -> Unit,
    onImportPlaylistClick: () -> Unit
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
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
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
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(
                            width = 1.dp,
                            color = Color.Transparent,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onImportPlaylistClick() }
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
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Import Shared Playlist",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Playlist items (Index shifted by +2)
            itemsIndexed(playlists) { i, playlist ->
                val index = i + 2 // Offset by 2 for the Create/Import Playlist items
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
                    Spacer(modifier = Modifier.weight(1f))
                    
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    val context = androidx.compose.ui.platform.LocalContext.current
                    IconButton(
                        onClick = { 
                            val uid = viewModel.currentUserId ?: com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
                            val pid = playlist?.id
                            if (uid != null && pid != null) {
                                val shareCode = "${uid}_${pid}"
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(shareCode))
                                Toast.makeText(context, "Share Code copied!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Sign in to share", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(28.dp).background(Color.Black.copy(alpha=0.3f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
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
    val focusRequester = remember { FocusRequester() }



    val currentScreen by viewModel.currentScreen.collectAsState()

    LaunchedEffect(focusedIndex) {
        if (focusedIndex in results.indices) {
            listState.animateScrollToItem(focusedIndex)
        }
    }

    LaunchedEffect(currentScreen) {
        if (currentScreen == ScreenType.SEARCH) {
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Modern Search Header (Apple Music / Spotify inspired)
        Text(
            text = "Search",
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(bottom = 16.dp, top = 8.dp)
        )

        // Glassmorphism Search Bar
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setSearchQuery(it) },
            placeholder = { Text("What do you want to listen to?", color = Color.White.copy(0.4f), fontSize = 16.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(0.7f)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearchQuery("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = Color.White.copy(0.7f))
                    }
                }
            },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.1f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                focusedBorderColor = iPodAccentBlue.copy(alpha = 0.5f),
                unfocusedBorderColor = Color.Transparent,
                cursorColor = iPodAccentBlue
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .focusRequester(focusRequester)
                .testTag("youtube_search_input")
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (isSearching) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = iPodAccentBlue, modifier = Modifier.size(36.dp))
            }
        } else if (results.isEmpty() && query.isNotBlank()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No results found for \"$query\"", color = Color.Gray, fontSize = 16.sp)
            }
        } else if (results.isEmpty()) {
            // Discover Section - Custom Aesthetic
            Text(
                text = "Discover",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            val genres = listOf(
                "Trending" to listOf(Color(0xFFFF512F), Color(0xFFDD2476)),
                "New Releases" to listOf(Color(0xFF4776E6), Color(0xFF8E54E9)),
                "Chill" to listOf(Color(0xFF00B4DB), Color(0xFF0083B0)),
                "Workout" to listOf(Color(0xFFF09819), Color(0xFFEDDE5D)),
                "Focus" to listOf(Color(0xFF3CA55C), Color(0xFFB5AC49)),
                "Party" to listOf(Color(0xFF833ab4), Color(0xFFfd1d1d))
            )
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(genres.size / 2) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        for (col in 0..1) {
                            val index = row * 2 + col
                            if (index < genres.size) {
                                val item = genres[index]
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            androidx.compose.ui.graphics.Brush.linearGradient(colors = item.second)
                                        )
                                        .clickable { viewModel.setSearchQuery(item.first) }
                                        .padding(16.dp),
                                    contentAlignment = Alignment.BottomStart
                                ) {
                                    Text(
                                        text = item.first,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        style = androidx.compose.ui.text.TextStyle(
                                            shadow = androidx.compose.ui.graphics.Shadow(
                                                color = Color.Black.copy(alpha = 0.2f),
                                                offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                                                blurRadius = 4f
                                            )
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Search Results layout
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                itemsIndexed(results) { index, track ->
                    val isFocused = index == focusedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isFocused) Color.White.copy(alpha = 0.1f)
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
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                color = if (isFocused) iPodAccentBlue else Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = track.artist,
                                color = Color.Gray,
                                fontSize = 14.sp,
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
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.setScreen(ScreenType.NOW_PLAYING) },
                modifier = Modifier.size(36.dp).background(Color.Black.copy(alpha=0.3f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.padding(4.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(queue) { index, track ->
                val isFocused = index == focusedIndex
                val isActivePlaying = index == activeIndex

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
                            color = if (isFocused) Color(0xFF00F2FE).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { viewModel.selectAndPlayTrack(track) }
                        .padding(10.dp),
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
