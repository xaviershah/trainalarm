package com.trainalarm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.TimeText
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.AppColors
import com.trainalarm.app.theme.Palette
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay

/** Theme-independent: the dark core with an orange and teal glow, the countdown and one big button. */
@Composable
fun RingingScreen(destination: String, eta: Instant?, onOff: () -> Unit) {
    val c = remember { AppColors(Palette.dark) }
    val cream = Color(0xFFF3ECE3)
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(1000) } }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(c.accent.copy(alpha = 0.55f), Color.Transparent), center = Offset(300f, 700f), radius = 900f)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(
            listOf(c.teal.copy(alpha = 0.6f), Color.Transparent), center = Offset(800f, 1700f), radius = 1000f)))
        Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.spacedBy(18.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
            Text("NEARLY THERE", fontFamily = Anton, fontSize = 40.sp, color = cream, textAlign = TextAlign.Center)
            if (destination.isNotEmpty()) Text(destination.uppercase(), fontFamily = Anton, fontSize = 24.sp, color = cream.copy(alpha = 0.85f), textAlign = TextAlign.Center)
            if (eta != null && eta.isAfter(now)) {
                DotTime(TimeText.countdown(Duration.between(now, eta).seconds), 110.dp, cream)
                Text("to go", fontSize = 15.sp, color = cream.copy(alpha = 0.8f))
            } else if (eta != null) {
                Text("ARRIVING NOW", fontFamily = Anton, fontSize = 30.sp, color = cream)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(24.dp)) { BigButton("I'm off", onOff, style = ButtonStyle.CREAM) }
    }
}
