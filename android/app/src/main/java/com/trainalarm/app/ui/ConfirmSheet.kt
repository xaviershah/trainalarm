package com.trainalarm.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trainalarm.app.PermissionKind
import com.trainalarm.app.SessionModel
import com.trainalarm.app.SettingsStore
import com.trainalarm.app.TimeText
import com.trainalarm.app.theme.Anton
import com.trainalarm.app.theme.LocalColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(model: SessionModel) {
    val c = LocalColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val confirm = model.confirm
    ModalBottomSheet(onDismissRequest = { model.backToSelecting() }, containerColor = c.surface, contentColor = c.text) {
        Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp), Arrangement.spacedBy(14.dp)) {
            Text("Wake me before ${model.to?.name ?: "your stop"}".uppercase(), fontFamily = Anton, fontSize = 30.sp, lineHeight = 30.sp, color = c.head)
            if (confirm != null) {
                val arrival = confirm.arrival
                if (arrival != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlarmDial(model.alarmAt, arrival, confirm.leadMinutes, Modifier.size(150.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Alarm at", fontSize = 12.sp, color = c.muted)
                            model.alarmAt?.let { DotTime(TimeText.clock(it), 42.dp, c.head) } ?: Text("Choose minutes", fontSize = 13.sp, color = c.muted)
                            Text("Arrives", fontSize = 12.sp, color = c.muted)
                            DotTime(TimeText.clock(arrival), 42.dp, c.head.copy(alpha = 0.5f))
                        }
                    }
                    LeadRuler(SettingsStore.LEAD_CHOICES, confirm.leadMinutes, { model.setLead(it) })
                    if (model.ringsStraightAway) Notice("The alarm will ring straight away.")
                } else {
                    Notice("This train does not call at ${model.to?.name ?: "that station"}. Pick another.")
                }
            }
            model.blockedPermission?.let {
                Notice(if (it == PermissionKind.ALARM) "Alarms are off for TrainAlarm. Allow them in Settings so it can wake you." else "A permission is off. Allow it in Settings to continue.")
            }
            model.permissionWarnings.forEach {
                when (it) {
                    PermissionKind.LOCATION -> Notice("Location is off. Live tracking will rely on train data only.")
                    PermissionKind.FULL_SCREEN_INTENT -> Notice("The alarm may not take over the lock screen. Allow full-screen alerts in Settings.")
                    else -> Unit
                }
            }
            BigButton(
                if (model.blockedPermission == null) "Start tracking" else "Allow alarms to continue",
                {
                    if (model.blockedPermission != null) {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    } else {
                        scope.launch { model.start() }
                    }
                },
                enabled = model.canStart
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    val c = LocalColors.current
    Text(text, fontSize = 13.sp, color = c.text,
        modifier = Modifier.fillMaxWidth().border(1.5.dp, c.bad, RoundedCornerShape(14.dp)).padding(12.dp))
}
