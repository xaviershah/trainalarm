package com.trainalarm.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.trainalarm.app.alarm.AlarmPermissions
import com.trainalarm.app.tracking.PlatformLocationSource
import kotlinx.coroutines.CompletableDeferred

/** The OS operations the gateway needs, so its policy is tested without Android. */
interface PermissionPlatform {
    fun isGranted(kind: PermissionKind): Boolean

    /** Shows the system prompt or settings screen for [kind] and returns once the user is back. */
    suspend fun prompt(kind: PermissionKind)
}

/** Remembers which permissions have been asked for, so a refusal is not re-prompted automatically. */
interface AskedStore {
    fun wasAsked(kind: PermissionKind): Boolean
    fun markAsked(kind: PermissionKind)
}

class KeyValuesAskedStore(private val store: KeyValues) : AskedStore {
    override fun wasAsked(kind: PermissionKind) = store.getInt("asked.${kind.name}") == 1
    override fun markAsked(kind: PermissionKind) = store.putInt("asked.${kind.name}", 1)
}

/**
 * The real Android permissions. Required: exact alarms and notifications. Optional: location (while
 * in use only) and, on Android 14 and later, full-screen intent. A permission is prompted once; after
 * a refusal it reads as DENIED and is never re-prompted automatically.
 */
class SystemPermissions(private val platform: PermissionPlatform, private val asked: AskedStore) : PermissionsGateway {
    override val requiredKinds = listOf(PermissionKind.ALARM, PermissionKind.NOTIFICATIONS)
    override val optionalKinds = listOf(PermissionKind.LOCATION, PermissionKind.FULL_SCREEN_INTENT)

    override fun status(kind: PermissionKind): PermissionStatus = when {
        platform.isGranted(kind) -> PermissionStatus.GRANTED
        asked.wasAsked(kind) -> PermissionStatus.DENIED
        else -> PermissionStatus.NOT_DETERMINED
    }

    override suspend fun request(kind: PermissionKind): PermissionStatus {
        if (platform.isGranted(kind)) return PermissionStatus.GRANTED
        if (asked.wasAsked(kind)) return PermissionStatus.DENIED
        asked.markAsked(kind)
        platform.prompt(kind)
        return if (platform.isGranted(kind)) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }
}

/** Shows runtime prompts and system settings screens, suspending until the user returns. */
interface PermissionPrompter {
    suspend fun requestRuntimePermission(permission: String)
    suspend fun openAndAwaitReturn(intent: Intent)
}

class AndroidPermissionPlatform(private val context: Context, private val prompter: PermissionPrompter) : PermissionPlatform {
    override fun isGranted(kind: PermissionKind): Boolean = when (kind) {
        PermissionKind.ALARM -> AlarmPermissions.canScheduleExactAlarms(context)
        PermissionKind.NOTIFICATIONS -> AlarmPermissions.notificationsGranted(context)
        PermissionKind.FULL_SCREEN_INTENT -> AlarmPermissions.canUseFullScreenIntent(context)
        PermissionKind.LOCATION ->
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun prompt(kind: PermissionKind) {
        when (kind) {
            PermissionKind.ALARM -> AlarmPermissions.exactAlarmIntent(context)?.let { prompter.openAndAwaitReturn(it) }
            PermissionKind.NOTIFICATIONS -> prompter.requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
            PermissionKind.LOCATION -> prompter.requestRuntimePermission(Manifest.permission.ACCESS_FINE_LOCATION)
            PermissionKind.FULL_SCREEN_INTENT -> AlarmPermissions.fullScreenIntentSettings(context)?.let { prompter.openAndAwaitReturn(it) }
        }
    }
}

/** Create this in `Activity.onCreate`, before the activity is started: it registers result launchers. */
class ActivityPermissionPrompter(activity: ComponentActivity) : PermissionPrompter {
    private var runtimeResult: CompletableDeferred<Unit>? = null
    private var returnResult: CompletableDeferred<Unit>? = null

    private val runtimeLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { runtimeResult?.complete(Unit) }
    private val settingsLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { returnResult?.complete(Unit) }

    override suspend fun requestRuntimePermission(permission: String) {
        val done = CompletableDeferred<Unit>().also { runtimeResult = it }
        runtimeLauncher.launch(permission)
        done.await()
    }

    override suspend fun openAndAwaitReturn(intent: Intent) {
        val done = CompletableDeferred<Unit>().also { returnResult = it }
        settingsLauncher.launch(intent)
        done.await()
    }
}

/** Lets the model start and stop the platform GPS source. */
fun PlatformLocationSource.asControl(): LocationControl = object : LocationControl {
    override fun start(): Boolean = this@asControl.start()
    override fun stop() = this@asControl.stop()
}
