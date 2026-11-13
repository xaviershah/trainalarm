package com.trainalarm.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.EndReason
import com.trainalarm.app.RouteProgress
import com.trainalarm.app.SessionModel
import com.trainalarm.app.StateCard
import com.trainalarm.app.TimeText
import com.trainalarm.app.TrackingCards
import com.trainalarm.app.TrackingDisplay
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.LocalColors
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TrackingScreen(model: SessionModel) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(1000) } }
    val d = model.display
    val progress = model.confirm?.let { confirm ->
        val from = model.from
        val to = model.to
        if (from != null && to != null) RouteProgress.make(confirm.service.journey.stops, from.id, to.id, d.eta, now) else null
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp), Arrangement.spacedBy(14.dp)) {
            Hero(model, d, now)
            AlarmCard(model, d)
            if (progress != null) RouteStrip(progress, model.from?.name.orEmpty(), model.to?.name.orEmpty())
            TrackingCards.make(d, model.to?.name ?: "Your stop").forEach { StateCardView(it) }
            Text("Keep the app open for live updates.", fontSize = 13.sp, color = c.muted)
        }
        BigButton("I'm off", { scope.launch { model.imOff() } }, Modifier.padding(18.dp))
    }
}

@Composable
private fun Hero(model: SessionModel, d: TrackingDisplay, now: Instant) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(c.teal).padding(18.dp), Arrangement.spacedBy(6.dp)) {
        Text((model.to?.name ?: "").uppercase(), fontFamily = Anton, fontSize = 26.sp, color = c.onTeal)
        val eta = d.eta
        if (eta != null) {
            DotTime(TimeText.countdown(Duration.between(now, eta).seconds), 84.dp, c.onTeal)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("to go. Arrives", fontSize = 13.sp, color = c.onTeal)
                DotTime(TimeText.clock(eta), 22.dp, c.onTeal)
                val delay = d.delayMinutes
                // The ON_TEAL pill would vanish on the teal card, so "early" uses the plain (surface) pill.
                if (delay != null && delay > 0) Pill("$delay min late", PillStyle.LATE)
                else if (delay != null && delay < 0) Pill("${-delay} min early", PillStyle.PLAIN)
            }
        }
    }
}

@Composable
private fun AlarmCard(model: SessionModel, d: TrackingDisplay) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), Arrangement.spacedBy(2.dp)) {
            Text("Alarm", fontSize = 12.sp, color = c.muted)
            d.alarmAt?.let { DotTime(TimeText.clock(it), 34.dp, c.head) }
            model.confirm?.leadMinutes?.let { Text("$it minutes before arrival", fontSize = 13.sp, color = c.muted) }
        }
        if (d.alarmNotSet) Pill("NOT SET", PillStyle.BAD) else Text("Set", fontWeight = FontWeight.Bold, color = c.tealText)
    }
}

@Composable
fun StateCardView(card: StateCard) {
    val c = LocalColors.current
    val prominent = card.tone == StateCard.Tone.WARN || card.tone == StateCard.Tone.INFO
    val background = when (card.tone) {
        StateCard.Tone.WARN -> c.accent
        StateCard.Tone.INFO -> c.teal
        else -> c.surface
    }
    val titleColor = when (card.tone) {
        StateCard.Tone.WARN -> c.onAccent
        StateCard.Tone.INFO -> c.onTeal
        else -> c.head
    }
    val detailColor = when (card.tone) {
        StateCard.Tone.WARN -> c.onAccent
        StateCard.Tone.INFO -> c.onTeal
        else -> c.text
    }
    val outline = if (card.tone == StateCard.Tone.ERROR) Modifier.border(1.5.dp, c.bad, RoundedCornerShape(18.dp)) else Modifier
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background).then(outline).padding(horizontal = 16.dp, vertical = 14.dp),
        Arrangement.spacedBy(3.dp)
    ) {
        if (prominent) Text(card.title.uppercase(), fontFamily = Anton, fontSize = 24.sp, lineHeight = 24.sp, color = titleColor)
        else Text(card.title, fontWeight = FontWeight.Bold, color = titleColor)
        Text(card.detail, fontSize = 13.sp, color = detailColor)
    }
}

@Composable
private fun RouteStrip(progress: RouteProgress, from: String, to: String) {
    val c = LocalColors.current
    val label = buildString {
        append("Route from $from to $to. ${Math.round(progress.fraction * 100)} percent of the way. ")
        progress.nextStop?.let { next ->
            append("Next stop ${next.station.name}")
            next.bestArrival?.let { append(" at ${TimeText.clock(it).plain}") }
            append(". ")
        }
        append(if (progress.stopsLeft == 1) "1 stop to go" else "${progress.stopsLeft} stops to go")
    }
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label }, Arrangement.spacedBy(8.dp)) {
        ProgressTrack(progress.fraction, Modifier.fillMaxWidth().height(22.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(from, fontSize = 12.sp, color = c.muted, modifier = Modifier.weight(1f))
            Text(to, fontSize = 12.sp, color = c.muted)
        }
        progress.nextStop?.let { next ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Next stop", color = c.muted)
                Text(next.station.name, fontWeight = FontWeight.Bold, color = c.head, modifier = Modifier.weight(1f))
                next.bestArrival?.let { DotTime(TimeText.clock(it), 20.dp, c.head) }
            }
        }
        Text(if (progress.stopsLeft == 1) "1 stop to go" else "${progress.stopsLeft} stops to go", fontSize = 13.sp, color = c.muted)
    }
}

@Composable
private fun ProgressTrack(fraction: Double, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Canvas(modifier.semantics { }) {
        val filledUntil = size.width * fraction.toFloat()
        var x = 0.75.dp.toPx()
        while (x < size.width) {
            drawRect(if (x < filledUntil) c.teal else c.muted, Offset(x, 0f), Size(1.5.dp.toPx(), size.height))
            x += 7.dp.toPx()
        }
        val markerX = (filledUntil - 1.5.dp.toPx()).coerceIn(0f, size.width - 3.dp.toPx())
        drawRect(c.accent, Offset(markerX, -3.dp.toPx()), Size(3.dp.toPx(), size.height + 6.dp.toPx()))
    }
}

@Composable
fun EndedScreen(model: SessionModel, reason: EndReason) {
    val card = when (reason) {
        EndReason.ARRIVED -> StateCard(StateCard.Tone.INFO, "Arrived", "${model.to?.name ?: "Your stop"}. Tracking has stopped and the alarm is cleared.")
        EndReason.IM_OFF -> StateCard(StateCard.Tone.PLAIN, "Tracking stopped", "Your alarm is cleared. Safe travels.")
    }
    Column(Modifier.fillMaxSize().padding(18.dp), Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        StateCardView(card)
        BigButton("Back to start", { model.returnHome() }, style = ButtonStyle.GHOST)
    }
}
