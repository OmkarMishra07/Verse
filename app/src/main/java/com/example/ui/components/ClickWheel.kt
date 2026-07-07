package com.example.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.ui.theme.iPodClickWheel
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.MusicPlayerViewModel
import kotlin.math.abs
import kotlin.math.atan2

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClickWheel(
    viewModel: MusicPlayerViewModel,
    modifier: Modifier = Modifier,
    onMenuClick: () -> Unit = {},
    onPrevClick: () -> Unit = {},
    onNextClick: () -> Unit = {},
    onPlayPauseClick: () -> Unit = {},
    onCenterClick: () -> Unit = {}
) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val view = LocalView.current
    var wheelSize by remember { mutableStateOf(IntSize.Zero) }
    val coroutineScope = rememberCoroutineScope()

    // Haptic feedback trigger helper
    val triggerHapticClick = {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    // Rotational gesture tracking state
    var lastAngle by remember { mutableStateOf<Double?>(null) }
    
    // Smooth press scaling states
    val centerInteractionSource = remember { MutableInteractionSource() }
    val isCenterPressed by centerInteractionSource.collectIsPressedAsState()
    val centerScale by animateFloatAsState(
        targetValue = if (isCenterPressed) 0.93f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "CenterScale"
    )

    Box(
        modifier = modifier
            .size(260.dp)
            .shadow(16.dp, CircleShape, ambientColor = Color.Black)
            .border(1.dp, Color(0xFF2A2A2A), CircleShape) // Subtle border matching border-[#2A2A2A]
            .clip(CircleShape)
            .background(iPodClickWheel) // Charcoal matte clickwheel background
            .onSizeChanged { wheelSize = it }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val centerX = wheelSize.width / 2f
                        val centerY = wheelSize.height / 2f
                        lastAngle = Math.toDegrees(
                            atan2(
                                (offset.y - centerY).toDouble(),
                                (offset.x - centerX).toDouble()
                            )
                        )
                    },
                    onDragEnd = { lastAngle = null },
                    onDragCancel = { lastAngle = null },
                    onDrag = { change, _ ->
                        val centerX = wheelSize.width / 2f
                        val centerY = wheelSize.height / 2f
                        val currentX = change.position.x
                        val currentY = change.position.y
                        
                        // Calculate angle of touch
                        val currentAngle = Math.toDegrees(
                            atan2(
                                (currentY - centerY).toDouble(),
                                (currentX - centerX).toDouble()
                            )
                        )
                        
                        lastAngle?.let { last ->
                            var delta = currentAngle - last
                            if (delta > 180) delta -= 360
                            if (delta < -180) delta += 360
                            
                            // Trigger rotational action every 15 degrees
                            val threshold = 15.0
                            if (abs(delta) >= threshold) {
                                val clockwise = delta > 0
                                viewModel.onWheelRotated(clockwise)
                                triggerHapticClick()
                                lastAngle = currentAngle
                                if (viewModel.tutorialState.value == 1) {
                                    viewModel.setTutorialState(2)
                                }
                            }
                        } ?: run {
                            lastAngle = currentAngle
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // TOP: MENU / DASHBOARD BUTTON
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            IconButton(
                onClick = {
                    triggerHapticClick()
                    onMenuClick()
                },
                modifier = Modifier.size(48.dp)
            ) {
                // Classic dashboard/grid icon representing menu
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(modifier = Modifier.size(7.dp).background(Color.White.copy(alpha = 0.8f), CircleShape))
                            Box(modifier = Modifier.size(7.dp).background(Color.White.copy(alpha = 0.8f), CircleShape))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(modifier = Modifier.size(7.dp).background(Color.White.copy(alpha = 0.8f), CircleShape))
                            Box(modifier = Modifier.size(7.dp).background(Color.White.copy(alpha = 0.8f), CircleShape))
                        }
                    }
                }
            }
        }

        // LEFT: PREVIOUS SONG / REWIND
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
        ) {
            IconButton(
                onClick = {
                    triggerHapticClick()
                    onPrevClick()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // RIGHT: NEXT SONG / FAST FORWARD
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
        ) {
            IconButton(
                onClick = {
                    triggerHapticClick()
                    onNextClick()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        // BOTTOM: PLAY / PAUSE
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        ) {
            IconButton(
                onClick = {
                    triggerHapticClick()
                    onPlayPauseClick()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val isLoading by viewModel.isLoading.collectAsState()
                    if (isLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            color = com.example.ui.theme.iPodAccentBlue,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "PlayPause",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // CENTER: SELECT BUTTON
        Surface(
            color = Color(0xFF2A2A2A), // Outer solid boundary matching bg-[#2A2A2A]
            shape = CircleShape,
            modifier = Modifier
                .size(90.dp)
                .scale(centerScale)
                .border(1.dp, Color(0xFF333333), CircleShape) // Bezel border border-[#333]
                .shadow(6.dp, CircleShape, ambientColor = Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var isLongPressed = false
                                
                                val longPressJob = coroutineScope.launch {
                                    delay(400)
                                    isLongPressed = true
                                    triggerHapticClick()
                                    viewModel.toggleQuickAccess(true)
                                }

                                var pointerId = down.id
                                var dragPosition = down.position

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val dragChange = event.changes.firstOrNull { it.id == pointerId }
                                    
                                    if (dragChange == null || !dragChange.pressed) {
                                        break
                                    }
                                    
                                    dragPosition = dragChange.position
                                    
                                    if (isLongPressed) {
                                        val centerX = 90.dp.toPx() / 2f
                                        val centerY = 90.dp.toPx() / 2f
                                        val dx = dragPosition.x - centerX
                                        val dy = dragPosition.y - centerY
                                        
                                        val angleDegrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                                        val normalizedAngle = (angleDegrees + 90.0 + 360.0) % 360.0
                                        val sector = ((normalizedAngle + 30.0) / 60.0).toInt() % 6
                                        
                                        if (sector != viewModel.quickAccessSelection.value) {
                                            viewModel.setQuickAccessSelection(sector)
                                            triggerHapticClick()
                                        }
                                        
                                        dragChange.consume()
                                    }
                                }

                                longPressJob.cancel()
                                if (isLongPressed) {
                                    val targetScreen = viewModel.quickAccessScreens[viewModel.quickAccessSelection.value]
                                    viewModel.setScreen(targetScreen)
                                    viewModel.toggleQuickAccess(false)
                                    if (viewModel.tutorialState.value == 2) {
                                        viewModel.setTutorialState(3)
                                    }
                                } else {
                                    triggerHapticClick()
                                    onCenterClick()
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Inner gradient core: bg-gradient-to-br from-[#2F2F2F] to-[#1A1A1A]
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.05f), CircleShape) // Subtle white highlight border border-white/5
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF2F2F2F), Color(0xFF1A1A1A))
                            )
                        )
                )
            }
        }

        // QUICK ACCESS WHEEL OVERLAY (inside ClickWheel Box)
        val showQuickAccess by viewModel.showQuickAccess.collectAsState()
        val selectionIndex by viewModel.quickAccessSelection.collectAsState()
        val screens = viewModel.quickAccessScreens

        androidx.compose.animation.AnimatedVisibility(
            visible = showQuickAccess,
            enter = androidx.compose.animation.fadeIn(animationSpec = tween(250)),
            exit = androidx.compose.animation.fadeOut(animationSpec = tween(250))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                screens.forEachIndexed { index, screen ->
                    val angleDegrees = index * 60.0 - 90.0
                    val angleRad = Math.toRadians(angleDegrees)
                    val isSelected = index == selectionIndex
                    
                    val itemScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.3f else 0.9f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "RadialItemScale"
                    )
                    val itemColor by animateColorAsState(
                        targetValue = if (isSelected) com.example.ui.theme.iPodAccentBlue else Color.White.copy(alpha = 0.5f),
                        label = "RadialItemColor"
                    )

                    Column(
                        modifier = Modifier
                            .offset(
                                x = (85 * Math.cos(angleRad)).dp,
                                y = (85 * Math.sin(angleRad)).dp
                            )
                            .scale(itemScale),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) com.example.ui.theme.iPodAccentBlue.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f)
                                )
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) com.example.ui.theme.iPodAccentBlue else Color.White.copy(alpha = 0.1f),
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = when (screen) {
                                    com.example.ui.viewmodel.ScreenType.NOW_PLAYING -> Icons.Filled.PlayArrow
                                    com.example.ui.viewmodel.ScreenType.EXPLORE -> Icons.Filled.Explore
                                    com.example.ui.viewmodel.ScreenType.LIKED -> Icons.Filled.Favorite
                                    com.example.ui.viewmodel.ScreenType.PLAYLISTS -> Icons.Filled.QueueMusic
                                    com.example.ui.viewmodel.ScreenType.SEARCH -> Icons.Filled.Search
                                    com.example.ui.viewmodel.ScreenType.QUEUE -> Icons.Filled.List
                                    else -> Icons.Filled.MusicNote
                                },
                                contentDescription = screen.title,
                                tint = itemColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        
                        if (isSelected) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = screen.title,
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
