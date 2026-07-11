package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.remote.JammingRoom

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JammingParticipantsOverlay(
    roomState: JammingRoom,
    myName: String,
    currentTrackThumbnail: String?,
    onClose: () -> Unit,
    onKick: (String) -> Unit
) {
    BackHandler { onClose() }
    
    var searchQuery by remember { mutableStateOf("") }
    
    val participants = roomState.participants.filter {
        it.contains(searchQuery, ignoreCase = true)
    }
    val isHost = roomState.hostName == myName

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
    ) {
        // Translucent Background
        AnimatedContent(
            targetState = currentTrackThumbnail,
            transitionSpec = {
                fadeIn(animationSpec = androidx.compose.animation.core.tween(500)) togetherWith fadeOut(animationSpec = androidx.compose.animation.core.tween(500))
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
                        .graphicsLayer(alpha = 0.4f)
                )
            }
        }
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.3f),
                            Color.Black.copy(alpha = 0.8f)
                        )
                    )
                )
        )
        
        Column(modifier = Modifier.fillMaxSize().padding(top = 40.dp)) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Participants", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("${roomState.participants.size} Users", color = Color.Gray, fontSize = 14.sp)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
            
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search...", color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00C853),
                    unfocusedBorderColor = Color.DarkGray
                )
            )
            
            HorizontalDivider(color = Color.DarkGray.copy(alpha=0.5f), thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))
            
            // List
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                items(participants) { participant ->
                    var showOptions by remember { mutableStateOf(false) }
                    
                    val isParticipantHost = participant == roomState.hostName
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (isHost && !isParticipantHost) {
                                        showOptions = true
                                    }
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2C2C2C)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(participant.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        }
                        
                        Spacer(modifier = Modifier.width(16.dp))
                        
                        // Name and Badges
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = participant,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (isParticipantHost) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("👑 Host", color = Color(0xFFFFD700), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF00C853)))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Listening", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                    
                    if (showOptions) {
                        AlertDialog(
                            onDismissRequest = { showOptions = false },
                            title = { Text("Manage User", color = Color.White) },
                            text = { Text("Are you sure you want to kick $participant from the room?", color = Color.LightGray) },
                            confirmButton = {
                                TextButton(onClick = { 
                                    onKick(participant)
                                    showOptions = false
                                }) {
                                    Text("Kick", color = Color.Red)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showOptions = false }) {
                                    Text("Cancel", color = Color.White)
                                }
                            },
                            containerColor = Color(0xFF1E1E1E)
                        )
                    }
                }
            }
        }
    }
}
