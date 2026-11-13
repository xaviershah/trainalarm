package com.trainalarm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.BoardState
import com.trainalarm.app.NearestFailure
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SessionPhase
import com.trainalarm.app.TimeText
import com.trainalarm.app.model.Service
import com.trainalarm.app.model.Station
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.LocalColors
import java.time.Instant
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(model: SessionModel, liveDataSetUp: Boolean, isDemo: Boolean, openSettings: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf<Boolean?>(null) }   // true = From, false = To
    LaunchedEffect(model.from?.id, model.to?.id) { model.loadBoard() }

    // Pull to refresh; the model's 20 s cooldown still applies.
    PullToRefreshBox(isRefreshing = model.board is BoardState.Loading, onRefresh = { scope.launch { model.loadBoard() } }) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TrainAlarm".uppercase(), fontFamily = Anton, fontSize = 20.sp, color = c.head)
            if (isDemo) Pill("Demo data")
            Box(Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface).clickable(onClick = openSettings), Alignment.Center) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.head)
            }
        }
        Text("Where to?".uppercase(), fontFamily = Anton, fontSize = 58.sp, lineHeight = 58.sp, color = c.head)
        StationField("From", model.from) { picking = true }
        if (model.from == null) NearestChip(model) { scope.launch { model.useNearestStation() } }
        StationField("To", model.to) { picking = false }
        if (liveDataSetUp) BoardSection(model) else SetupCard()
    }
    }
    picking?.let { isFrom -> StationSearchSheet(model, isFrom) { picking = null } }
    if (model.phase == SessionPhase.Confirming) ConfirmSheet(model)
}

@Composable
private fun StationField(title: String, station: Station?, onClick: () -> Unit) {
    val c = LocalColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, fontSize = 12.sp, color = c.muted)
        Text(station?.name ?: "Choose a station", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (station == null) c.muted else c.head)
    }
}

@Composable
private fun NearestChip(model: SessionModel, onClick: () -> Unit) {
    val c = LocalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (model.isFindingNearest) "Finding your nearest station..." else "Use my nearest station",
            fontSize = 13.sp, color = c.text,
            modifier = Modifier.border(1.5.dp, c.muted.copy(alpha = 0.6f), CircleShape).clip(CircleShape)
                .clickable(enabled = !model.isFindingNearest, onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp)
        )
        model.nearestFailure?.let {
            Text(
                if (it == NearestFailure.PERMISSION_DENIED) "Location is off. Search for your station instead."
                else "Could not find you yet. Search for your station instead.",
                fontSize = 13.sp, color = c.muted
            )
        }
    }
}

@Composable
private fun SetupCard() {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp), Arrangement.spacedBy(6.dp)) {
        Text("Live data is not set up", fontWeight = FontWeight.Bold, color = c.head)
        Text("This build has no train data token, so it cannot show departures yet.", fontSize = 13.sp, color = c.text)
    }
}

@Composable
private fun BoardSection(model: SessionModel) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (val board = model.board) {
            BoardState.Idle -> Text("Choose where you are and where you are going.", fontSize = 13.sp, color = c.muted)
            BoardState.Loading -> Box(Modifier.fillMaxWidth().padding(top = 12.dp), Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            is BoardState.Loaded -> {
                Text("Trains from ${model.from?.name.orEmpty()} calling at ${model.to?.name.orEmpty()}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.muted)
                board.services.forEach { service -> BoardRow(service) { scope.launch { model.choose(service) } } }
            }
            BoardState.Empty -> Message("No trains found", "No trains call at ${model.to?.name ?: "that station"} soon. Try again in a few minutes.") { scope.launch { model.loadBoard(force = true) } }
            is BoardState.Failed -> Message("Could not load trains", board.message) { scope.launch { model.loadBoard(force = true) } }
            BoardState.RateLimited -> Message("The train data is busy", "Wait a moment, then try again.") { scope.launch { model.loadBoard() } }   // keeps the cooldown
        }
    }
}

@Composable
private fun Message(title: String, detail: String, retry: () -> Unit) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp), Arrangement.spacedBy(8.dp)) {
        Text(title, fontWeight = FontWeight.Bold, color = c.head)
        Text(detail, fontSize = 13.sp, color = c.text)
        BigButton("Try again", retry, style = ButtonStyle.GHOST)
    }
}

@Composable
private fun BoardRow(service: Service, onClick: () -> Unit) {
    val c = LocalColors.current
    val stop = service.journey.stops[0]
    val departure = stop.estimatedDeparture ?: stop.scheduledDeparture
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            if (departure != null) {
                DotTime(TimeText.clock(departure), 34.dp, c.head)
                TimeText.dateLabel(departure, Instant.now())?.let { Text(it, fontSize = 11.sp, color = c.muted) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(service.operatorName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.text)
            Text("towards ${service.journey.destination.name}", fontSize = 13.sp, color = c.muted)
        }
        val late = stop.scheduledDeparture?.let { s -> stop.estimatedDeparture?.let { e -> Math.round((e.epochSecond - s.epochSecond) / 60.0).toInt() } } ?: 0
        when {
            stop.isCancelled -> Pill("Cancelled", PillStyle.BAD)
            late > 0 -> Pill("+$late min", PillStyle.LATE)
            else -> Pill("On time")
        }
    }
}
