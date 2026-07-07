package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import com.example.data.model.Track
import com.example.ui.viewmodel.MusicPlayerViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatRoomFullScreenOverlay(viewModel: MusicPlayerViewModel) {
    val jammingRoomId by viewModel.jammingRoomId.collectAsState()
    val roomState by viewModel.jammingRoomState.collectAsState()
    val chatMessages by remember(jammingRoomId) {
        if (jammingRoomId.isNotBlank()) com.example.data.remote.JammingService.listenToMessages(jammingRoomId) 
        else kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    
    val currentTrack by viewModel.currentTrack.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
    val myName = remember { currentUser?.displayName?.takeIf { it.isNotBlank() } ?: "User-${(100..999).random()}" }
    
    var chatMsg by remember { mutableStateOf("") }
    var replyToMsg by remember { mutableStateOf<com.example.data.remote.ChatMessage?>(null) }
    var showChatSearch by remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Keep typing status updated
    LaunchedEffect(chatMsg) {
        if (jammingRoomId.isNotBlank()) {
            com.example.data.remote.JammingService.setTypingStatus(jammingRoomId, myName, chatMsg.isNotBlank())
        }
    }
    
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(
        modifier = Modifier.fillMaxSize().imePadding().background(Color.Black)
    ) {
        // Translucent background theme
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
                            Color(0xFF007AFF).copy(alpha = 0.35f),
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
            // 1. Header (Balanced)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161616).copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .windowInsetsPadding(WindowInsets.statusBars),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { viewModel.setIsChatOpen(false) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${roomState?.hostName ?: "Jam"} Room", 
                        color = Color.White, 
                        fontSize = 18.sp, 
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Code: $jammingRoomId", 
                        color = Color(0xFF00C853), // Modern green accent
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                IconButton(onClick = { showChatSearch = !showChatSearch }) {
                    Icon(Icons.Default.Search, contentDescription = "Search Song", tint = Color.White)
                }
            }

            // 2. Music Player (Fixed at top of chat)
            if (showChatSearch) {
                var chatSearchQuery by remember { mutableStateOf("") }
                var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
                var isSearching by remember { mutableStateOf(false) }
                
                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(12.dp).heightIn(max = 250.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = chatSearchQuery,
                            onValueChange = { chatSearchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search YouTube...", color = Color.Gray) },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                                focusedBorderColor = Color(0xFF00C853), unfocusedBorderColor = Color.DarkGray
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = {
                            if (chatSearchQuery.isNotBlank()) {
                                isSearching = true
                                coroutineScope.launch {
                                    try {
                                        searchResults = com.example.data.network.YouTubeSearchHelper.search(chatSearchQuery).map { yt -> 
                                            Track(id = yt.videoId, title = yt.title, artist = yt.artist, album = "YouTube", thumbnailUrl = yt.thumbnailUrl, duration = yt.duration) 
                                        }
                                    } finally {
                                        isSearching = false
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                        }
                    }
                    if (isSearching) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF00C853))
                        }
                    } else if (searchResults.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                            items(searchResults.size) { idx ->
                                val track = searchResults[idx]
                                Row(modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.selectAndPlayTrackNoRedirect(track)
                                    showChatSearch = false
                                }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AsyncImage(model = track.thumbnailUrl, contentDescription = null, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)))
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
            } else if (currentTrack != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF161616).copy(alpha = 0.5f)).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(model = currentTrack!!.thumbnailUrl, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(currentTrack!!.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(currentTrack!!.artist, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { viewModel.playPrevious() }) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Prev", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.togglePlayback() }) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause", tint = Color.White)
                    }
                    IconButton(onClick = { viewModel.playNext() }) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                    }
                }
                HorizontalDivider(color = Color.DarkGray.copy(alpha=0.5f), thickness = 1.dp)
            }

            // 3. Chat Messages Area
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                reverseLayout = true
            ) {
                val reversedMessages = chatMessages.reversed()
                
                item { Spacer(modifier = Modifier.height(4.dp)) }

                items(reversedMessages.size) { index ->
                    val msg = reversedMessages[index]
                    val isMe = msg.senderName == myName
                    val isSystem = msg.isSystemMessage || msg.senderName == "System"
                    var showEmojiPicker by remember { mutableStateOf(false) }

                    if (isSystem) {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                            Text(
                                msg.message,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .background(Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    } else {
                        val showName = !isMe && (index == reversedMessages.lastIndex || reversedMessages[index + 1].senderName != msg.senderName)
                        
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                        ) {
                            if (showName) {
                                Text(
                                    msg.senderName, 
                                    color = Color(0xFF00C853),
                                    fontSize = 12.sp, 
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                                )
                            }
                            
                            Box {
                                Column(
                                    modifier = Modifier
                                        .background(
                                            if (isMe) Color(0xFF005C4B) else Color(0xFF202C33),
                                            RoundedCornerShape(
                                                topStart = 16.dp, 
                                                topEnd = 16.dp, 
                                                bottomStart = if (isMe) 16.dp else 4.dp, 
                                                bottomEnd = if (isMe) 4.dp else 16.dp
                                            )
                                        )
                                        .pointerInput(Unit) {
                                            detectTapGestures(
                                                onLongPress = { showEmojiPicker = true },
                                                onDoubleTap = { replyToMsg = msg }
                                            )
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    if (msg.replyToMessageId != null) {
                                        val quotedMsg = chatMessages.find { it.id == msg.replyToMessageId }
                                        if (quotedMsg != null) {
                                            Box(
                                                modifier = Modifier
                                                    .widthIn(max = 250.dp)
                                                    .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                                    .padding(8.dp)
                                                    .padding(bottom = 4.dp)
                                            ) {
                                                Column {
                                                    Text(quotedMsg.senderName, color = Color(0xFF00C853), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                    Text(quotedMsg.message, color = Color.LightGray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }
                                    
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(msg.message, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            timeFormat.format(Date(msg.timestamp)),
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(bottom = 1.dp)
                                        )
                                    }
                                }

                                if (showEmojiPicker) {
                                    Row(modifier = Modifier.background(Color(0xFF333333), RoundedCornerShape(20.dp)).padding(4.dp).offset(y = (-30).dp)) {
                                        listOf("👍", "❤️", "😂", "🔥", "🎵").forEach { emoji ->
                                            Text(emoji, fontSize = 22.sp, modifier = Modifier.clickable {
                                                showEmojiPicker = false
                                                coroutineScope.launch { com.example.data.remote.JammingService.addReaction(jammingRoomId, msg.id, emoji, myName) }
                                            }.padding(8.dp))
                                        }
                                    }
                                }
                            }
                            
                            if (msg.reactions.isNotEmpty()) {
                                Row(modifier = Modifier.padding(top = 2.dp)) {
                                    msg.reactions.forEach { (emoji, users) ->
                                        if (users.isNotEmpty()) {
                                            Text(
                                                "$emoji ${users.size}",
                                                fontSize = 11.sp,
                                                color = Color.White,
                                                modifier = Modifier.background(Color(0xFF2A2A2A), RoundedCornerShape(12.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }

            // 4. Reply Bar
            if (replyToMsg != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(replyToMsg!!.senderName, color = Color(0xFF00C853), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(replyToMsg!!.message, color = Color.LightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { replyToMsg = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel Reply", tint = Color.Gray)
                    }
                }
            }

            // 5. Input Bar (Cohesive Pill Design)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161616).copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF202C33), RoundedCornerShape(24.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = chatMsg,
                        onValueChange = { chatMsg = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message", color = Color.Gray, fontSize = 16.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (chatMsg.isNotBlank()) Color(0xFF00C853) else Color(0xFF202C33))
                        .clickable(enabled = chatMsg.isNotBlank()) {
                            val msgToSend = chatMsg
                            val replyId = replyToMsg?.id
                            chatMsg = ""
                            replyToMsg = null
                            coroutineScope.launch { 
                                com.example.data.remote.JammingService.sendMessage(jammingRoomId, myName, msgToSend, replyToMessageId = replyId) 
                                com.example.data.remote.JammingService.setTypingStatus(jammingRoomId, myName, false)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = if (chatMsg.isNotBlank()) Color.White else Color.Gray, modifier = Modifier.size(20.dp).offset(x = 2.dp))
                }
            }
        }
    }
}
