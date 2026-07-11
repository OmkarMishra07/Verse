package com.example.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ChooseVibeScreen(onVibeChosen: (Boolean) -> Unit) {
    var showContent by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(300)
        showContent = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F0F)),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visible = showContent,
            enter = androidx.compose.animation.fadeIn(animationSpec = tween(1000)) + 
                    androidx.compose.animation.slideInVertically(initialOffsetY = { 50 }, animationSpec = tween(1000))
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    "Choose Your Vibe",
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    "How do you want to experience Verse?",
                    color = Color.Gray,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 48.dp)
                )

                // Classic Wheel Option
                VibeOptionCard(
                    title = "Classic Wheel",
                    description = "The nostalgic, tactile iPod experience.",
                    gradientColors = listOf(Color(0xFF2A2A2A), Color(0xFF1A1A1A)),
                    borderColor = Color(0xFF333333),
                    onClick = { onVibeChosen(false) }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Modern UI Option
                VibeOptionCard(
                    title = "Modern Full Screen",
                    description = "Sleek, glassmorphism UI with bottom navigation.",
                    gradientColors = listOf(Color(0xFF1E3A8A).copy(alpha = 0.3f), Color(0xFF0F172A).copy(alpha = 0.3f)),
                    borderColor = Color(0xFF3B82F6).copy(alpha = 0.5f),
                    onClick = { onVibeChosen(true) }
                )
            }
        }
    }
}

@Composable
fun VibeOptionCard(
    title: String,
    description: String,
    gradientColors: List<Color>,
    borderColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(gradientColors))
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .padding(24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column {
            Text(
                title,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                description,
                color = Color.LightGray,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        }
    }
}
