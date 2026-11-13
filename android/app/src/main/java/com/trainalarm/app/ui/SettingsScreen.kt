package com.trainalarm.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.trainalarm.app.BuildConfig
import com.trainalarm.app.PermissionKind
import com.trainalarm.app.PermissionStatus
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.TestAlarmState
import com.trainalarm.app.ThemeChoice
import com.trainalarm.app.TimeText
import com.trainalarm.app.alarm.AlarmPermissions
import com.trainalarm.app.alarm.DevAlarmScreen
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.LocalColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(model: SessionModel, settings: SettingsStore, onClose: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by settings.theme.collectAsState()
    val lead by settings.defaultLeadMinutes.collectAsState()
    var showingDev by remember { mutableStateOf(false) }
    BackHandler(onBack = if (showingDev) ({ showingDev = false }) else onClose)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { model.refreshPermissions(); model.refreshTestAlarm() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun fix(kind: PermissionKind, status: PermissionStatus) {
        if (status == PermissionStatus.NOT_DETERMINED) scope.launch { model.requestPermission(kind) }
        else context.startActivity(settingsIntent(context, kind))
    }

    if (showingDev) {
        Column(Modifier.fillMaxSize().background(c.bg).padding(18.dp)) {
            BigButton("Close dev harness", { showingDev = false }, style = ButtonStyle.GHOST)
            Box(Modifier.weight(1f)) { DevAlarmScreen(model.permissionRevision) }
        }
        return
    }

    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(18.dp), Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("SETTINGS", fontFamily = Anton, fontSize = 34.sp, color = c.head)
            Text("Done", fontSize = 17.sp, color = c.head, modifier = Modifier.clickable(role = Role.Button, onClick = onClose).padding(8.dp))
        }
        Section("Appearance") {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                listOf(ThemeChoice.SYSTEM to "System", ThemeChoice.DARK to "Dark", ThemeChoice.LIGHT to "Light").forEach { (choice, label) ->
                    val selected = theme == choice
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) c.accent else c.surface2)
                            .semantics { this.selected = selected; contentDescription = "Theme $label" }
                            .clickable(role = Role.RadioButton) { settings.setTheme(choice) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { Text(label, fontSize = 15.sp, color = if (selected) c.onAccent else c.text) }
                }
            }
        }
        Section("Alert time") {
            LeadRuler(SettingsStore.LEAD_CHOICES, lead, { settings.setDefaultLeadMinutes(it) })
            Text("Minutes before arrival. You can still change it for each journey.", fontSize = 13.sp, color = c.muted)
        }
        Section("Permissions") {
            model.settingsPermissionKinds.forEach { kind ->
                val status = model.permissionStatus(kind)
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clearAndSetSemantics {
                        contentDescription = "${permissionTitle(kind)}. ${permissionReason(kind)} ${permissionStatusWords(status)}."
                    }) {
                        Text(permissionTitle(kind), fontSize = 15.sp, color = c.head)
                        Text(permissionReason(kind), fontSize = 13.sp, color = c.muted)
                    }
                    if (status == PermissionStatus.GRANTED) {
                        Pill("On", modifier = Modifier.clearAndSetSemantics { })
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Pill(permissionPillText(status), PillStyle.BAD, Modifier.clearAndSetSemantics { })
                            Text("Fix", fontSize = 15.sp, color = c.tealText, modifier = Modifier
                                    .semantics { contentDescription = "Fix ${permissionTitle(kind)} permission" }
                                    .clickable(role = Role.Button) { fix(kind, status) }.padding(12.dp))
                        }
                    }
                }
            }
        }
        Section("Test alarm") {
            BigButton("Test alarm in 10 seconds", { scope.launch { model.scheduleTestAlarm() } }, style = ButtonStyle.GHOST, enabled = model.canTestAlarm)
            when (val state = model.testAlarm) {
                TestAlarmState.Idle -> if (!model.canTestAlarm) Text("Not while a journey is running.", fontSize = 13.sp, color = c.muted)
                is TestAlarmState.Scheduled -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Rings at", fontSize = 13.sp, color = c.muted)
                    DotTime(TimeText.clock(state.at), 18.dp, c.head)
                }
                TestAlarmState.Failed -> Text("Could not set the test alarm. Check the permissions above.", fontSize = 13.sp, color = c.bad)
            }
        }
        Section("About") {
            Text("Station data: NaPTAN, Open Government Licence v3.0.", fontSize = 13.sp, color = c.text)
            Text("Train times: Realtime Trains, realtimetrains.co.uk.", fontSize = 13.sp, color = c.text)
            Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = c.muted)
        }
        if (BuildConfig.DEBUG) {
            Text("Developer alarm harness", fontSize = 13.sp, color = c.muted, modifier = Modifier.clickable(role = Role.Button) { showingDev = true }.padding(12.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), fontFamily = Anton, fontSize = 20.sp, color = c.head, modifier = Modifier.semantics { heading() })
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(14.dp), Arrangement.spacedBy(10.dp)) { content() }
    }
}

internal fun permissionPillText(status: PermissionStatus) = if (status == PermissionStatus.DENIED) "Off" else "Not asked"

internal fun permissionStatusWords(status: PermissionStatus) = when (status) {
    PermissionStatus.GRANTED -> "Allowed"
    PermissionStatus.DENIED -> "Not allowed. Turn it on in system settings"
    PermissionStatus.NOT_DETERMINED -> "Not asked yet"
}

private fun permissionTitle(kind: PermissionKind) = when (kind) {
    PermissionKind.ALARM -> "Alarms"
    PermissionKind.NOTIFICATIONS -> "Notifications"
    PermissionKind.LOCATION -> "Location"
    PermissionKind.FULL_SCREEN_INTENT -> "Full-screen alerts"
}

private fun permissionReason(kind: PermissionKind) = when (kind) {
    PermissionKind.ALARM -> "Needed to wake you."
    PermissionKind.NOTIFICATIONS -> "Needed to show the alarm."
    PermissionKind.LOCATION -> "Sharpens the arrival time. Optional."
    PermissionKind.FULL_SCREEN_INTENT -> "Lets the alarm take over the lock screen. Optional."
}

/** The system page where this permission can be switched on, else the app's details page. */
private fun settingsIntent(context: android.content.Context, kind: PermissionKind): Intent {
    val specific = when (kind) {
        PermissionKind.ALARM -> AlarmPermissions.exactAlarmIntent(context)
        PermissionKind.FULL_SCREEN_INTENT -> AlarmPermissions.fullScreenIntentSettings(context)
        else -> null
    }
    return specific ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
}
