package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.Track
import com.example.data.network.LRCLibHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin

data class LyricLine(val timeMs: Long, val text: String)

fun parseLrc(lrc: String): List<LyricLine> {
    val lines = mutableListOf<LyricLine>()
    val regex = Regex("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\](.*)")
    
    lrc.split("\n").forEach { line ->
        val match = regex.find(line)
        if (match != null) {
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val millisecondsStr = match.groupValues[3]
            // LRCLib gives 2 or 3 digits for ms
            val milliseconds = if (millisecondsStr.length == 2) millisecondsStr.toLong() * 10 else millisecondsStr.toLong()
            
            val timeMs = (minutes * 60 * 1000) + (seconds * 1000) + milliseconds
            val text = match.groupValues[4].trim()
            lines.add(LyricLine(timeMs, text))
        } else if (!line.startsWith("[") && line.trim().isNotEmpty()) {
            // For plain lyrics, just put them without timestamps
            lines.add(LyricLine(-1L, line.trim()))
        }
    }
    return lines
}

@Composable
fun SyncedLyricsDialog(
    track: Track?,
    currentPositionMs: Long,
    onDismissRequest: () -> Unit,
    onSeek: (Long) -> Unit
) {
    var lyricsData by remember(track?.id) { mutableStateOf<List<LyricLine>>(emptyList()) }
    var isLoading by remember(track?.id) { mutableStateOf(true) }
    var error by remember(track?.id) { mutableStateOf<String?>(null) }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(track) {
        if (track == null) {
            error = "No track selected"
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        error = null
        try {
            var cleanTitle = track.title
                // Strip standard tags
                .replace(Regex("(?i)\\(.*?official.*?\\)|\\[.*?official.*?\\]|\\(.*?lyric.*?\\)|\\[.*?lyric.*?\\]|\\(.*?video.*?\\)|\\[.*?video.*?\\]|\\(.*?audio.*?\\)|\\[.*?audio.*?\\]"), "")
                // Strip "ft." or "feat."
                .replace(Regex("(?i)ft\\..*|feat\\..*"), "")
                // Strip everything after a pipe "|" (Very common in Hindi songs: "Title | Movie | Actors")
                .replace(Regex("\\|.*"), "")
                // Strip everything after a hyphen if it looks like extra metadata (but keep it if it's short)
                .replace(Regex(" - .*"), "")
                // Strip common Hindi tags
                .replace(Regex("(?i)full video|full song|video song|audio song|lyrical video|lyrical"), "")
                .trim()
                
            // If channel is a music label (like T-Series), LRCLib won't match the artist. 
            // We pass an empty string for artist if it's a known label, forcing LRCLib to search by title only.
            val labels = listOf("T-Series", "Zee Music Company", "Sony Music India", "YRF", "Speed Records", "Desi Melodies")
            val cleanArtist = if (labels.any { track.artist.contains(it, ignoreCase = true) }) "" else track.artist

            val lyricsStr = LRCLibHelper.fetchLyrics(cleanTitle, cleanArtist)
            if (lyricsStr.isNullOrEmpty()) {
                error = "No lyrics found for this track."
            } else {
                lyricsData = parseLrc(lyricsStr)
                if (lyricsData.isEmpty()) {
                    error = "Failed to parse lyrics."
                }
            }
        } catch (e: Exception) {
            error = "Error fetching lyrics."
        } finally {
            isLoading = false
        }
    }

    // Auto-scroll logic
    val activeIndex = if (lyricsData.isNotEmpty() && lyricsData.first().timeMs != -1L) {
        // Find the last line whose time is <= current position
        val idx = lyricsData.indexOfLast { it.timeMs <= currentPositionMs }
        if (idx == -1) 0 else idx
    } else {
        -1
    }

    LaunchedEffect(activeIndex) {
        if (activeIndex > 0 && !listState.isScrollInProgress) {
            // Scroll to the active line, placing it roughly in the center (adjusting index offset)
            val centerOffset = 3 // Offset to keep it vertically centered
            val targetIndex = maxOf(0, activeIndex - centerOffset)
            listState.animateScrollToItem(targetIndex)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Blurred Album Art Background
            if (track?.thumbnailUrl != null) {
                coil.compose.AsyncImage(
                    model = track.thumbnailUrl,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(radius = 60.dp, edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
                )
            }
            // Dark Gradient Overlay for readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 40.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lyrics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else if (error != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error!!, color = Color.LightGray, fontSize = 18.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 40.dp, bottom = 200.dp) // padding so last line can reach center
                    ) {
                        itemsIndexed(lyricsData) { index, line ->
                            val isActive = index == activeIndex
                            val isPlain = line.timeMs == -1L
                            
                            // Animations for smooth transitions
                            val targetAlpha = if (isActive || isPlain) 1f else 0.4f
                            val animatedAlpha by animateFloatAsState(targetValue = targetAlpha, label = "alpha")
                            
                            val targetScale = if (isActive && !isPlain) 1.05f else 1f
                            val animatedScale by animateFloatAsState(targetValue = targetScale, label = "scale")
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null, // Disable ripple for cleaner look
                                        onClick = {
                                            if (!isPlain) onSeek(line.timeMs)
                                        }
                                    )
                                    .padding(vertical = 12.dp)
                            ) {
                                Text(
                                    text = line.text.ifEmpty { " " }, // Preserve empty lines
                                    color = Color.White.copy(alpha = animatedAlpha),
                                    fontSize = if (isPlain) 20.sp else 28.sp,
                                    fontWeight = if (isActive && !isPlain) FontWeight.ExtraBold else FontWeight.Bold,
                                    lineHeight = 36.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            scaleX = animatedScale
                                            scaleY = animatedScale
                                            transformOrigin = TransformOrigin(0f, 0.5f) // Scale from left
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
