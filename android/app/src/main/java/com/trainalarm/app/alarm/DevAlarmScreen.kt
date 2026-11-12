package com.trainalarm.app.alarm

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Throwaway dev harness for the Stage 3 device checklist (debug builds only). Not product UI (Stage 4). */
@Composable
fun DevAlarmScreen(refreshKey: Int) {
    val context = LocalContext.current
    val scheduler = remember { ExactAlarmScheduler(SystemExactAlarmGateway(context)) }
    val scope = rememberCoroutineScope()
    var seconds by remember { mutableStateOf("60") }
    var status by remember { mutableStateOf("Idle") }
    var launcherResults by remember { mutableIntStateOf(0) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        launcherResults += 1
    }
    val clock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    // Re-read when the activity resumes (refreshKey) or a permission dialog returns (launcherResults).
    val exactAllowed = remember(refreshKey, launcherResults) { AlarmPermissions.canScheduleExactAlarms(context) }
    val notificationsAllowed = remember(refreshKey, launcherResults) { AlarmPermissions.notificationsGranted(context) }
    val fullScreenAllowed = remember(refreshKey, launcherResults) { AlarmPermissions.canUseFullScreenIntent(context) }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Dev alarm (debug builds only)")
        OutlinedTextField(value = seconds, onValueChange = { seconds = it }, label = { Text("Seconds from now (0 allowed)") })
        Button(onClick = {
            scope.launch {
                val fireAt = Instant.now().plusSeconds(seconds.toLongOrNull() ?: 60)
                status = try {
                    scheduler.schedule(fireAt)
                    "Scheduled for ${clock.format(fireAt)}"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Error: ${e::class.simpleName}: ${e.message}"
                }
            }
        }) { Text("Schedule alarm") }
        Button(onClick = { scope.launch { scheduler.cancel(); status = "Cancelled" } }) { Text("Cancel alarm") }

        Text("Exact alarms: ${if (exactAllowed) "allowed" else "DENIED"}")
        AlarmPermissions.exactAlarmIntent(context)?.let { intent ->
            Button(onClick = { context.startActivity(intent) }) { Text("Open exact-alarm settings") }
        }
        Text("Notifications: ${if (notificationsAllowed) "allowed" else "DENIED"}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Button(onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Ask for notifications")
            }
        }
        Text("Full-screen alarm: ${if (fullScreenAllowed) "allowed" else "DENIED"}")
        AlarmPermissions.fullScreenIntentSettings(context)?.let { intent ->
            Button(onClick = { context.startActivity(intent) }) { Text("Open full-screen settings") }
        }
        Text(status)
    }
}
