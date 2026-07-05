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
            val lyricsStr = LRCLibHelper.fetchLyrics(track.title, track.artist)
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
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xEE000000)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Lyrics", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    IconButton(onClick = onDismissRequest) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else if (error != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error!!, color = Color.Gray, fontSize = 18.sp, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 120.dp) // padding so last line can reach center
                    ) {
                        itemsIndexed(lyricsData) { index, line ->
                            val isActive = index == activeIndex
                            val isPlain = line.timeMs == -1L
                            
                            val textColor = if (isActive || isPlain) Color.White else Color.Gray
                            val fontSize = if (isActive && !isPlain) 24.sp else 18.sp
                            val fontWeight = if (isActive && !isPlain) FontWeight.Bold else FontWeight.Normal
                            
                            Text(
                                text = line.text.ifEmpty { " " }, // Preserve empty lines
                                color = textColor,
                                fontSize = fontSize,
                                fontWeight = fontWeight,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        if (line.timeMs != -1L) {
                                            onSeek(line.timeMs)
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}
