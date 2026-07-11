package com.example.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@Composable
fun rememberBatteryPercentage(): Int {
    val context = LocalContext.current
    var batteryPct by remember { mutableStateOf(100) }

    DisposableEffect(context) {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level != -1 && scale != -1) {
                        batteryPct = (level * 100) / scale
                    }
                }
            }
        }
        context.registerReceiver(receiver, intentFilter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    return batteryPct
}

@Composable
fun BatteryProfileButton(
    nickname: String,
    modifier: Modifier = Modifier
) {
    val batteryPct = rememberBatteryPercentage()
    var showOverlay by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(20.dp))
                .clickable { showOverlay = true }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BatteryFull,
                contentDescription = "Battery",
                tint = if (batteryPct > 20) Color.Green else Color.Red,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "$batteryPct%",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    if (showOverlay) {
        BatteryStatusOverlay(
            batteryPct = batteryPct,
            nickname = nickname,
            onDismiss = { showOverlay = false }
        )
    }
}

@Composable
fun BatteryStatusOverlay(
    batteryPct: Int,
    nickname: String,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.zIndex(100f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .padding(32.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF1E1E1E).copy(alpha = 0.9f))
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                    .clickable(enabled = false) {} // block clicks from dismissing
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Battery Graphic
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(batteryPct / 100f)
                                .background(if (batteryPct > 20) Color(0xFF4CAF50) else Color(0xFFF44336))
                        )
                    }
                    // Battery Tip
                    Box(
                        modifier = Modifier
                            .offset(y = (-168).dp)
                            .width(30.dp)
                            .height(8.dp)
                            .background(Color.White.copy(alpha = 0.8f), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = "Hey ${nickname.takeIf { it.isNotBlank() } ?: "there"},",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Your battery percentage is $batteryPct%",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}
