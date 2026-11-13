package com.trainalarm.app

enum class PermissionKind { ALARM, NOTIFICATIONS, LOCATION, FULL_SCREEN_INTENT }
enum class PermissionStatus { GRANTED, DENIED, NOT_DETERMINED }

/** What the app asks the OS for. The real implementation is `SystemPermissions`; tests use a fake. */
interface PermissionsGateway {
    /** Refusing any of these blocks Start. */
    val requiredKinds: List<PermissionKind>

    /** Refusing any of these only shows a warning. */
    val optionalKinds: List<PermissionKind>

    fun status(kind: PermissionKind): PermissionStatus

    /** Shows the system prompt when the status is NOT_DETERMINED; returns the resulting status. */
    suspend fun request(kind: PermissionKind): PermissionStatus
}

/**
 * Starts and stops GPS updates. The tracker only reads `LocationSource.latestFix()`, so something
 * has to start the updates: the model does, once location is allowed. `start` is false if it cannot.
 */
interface LocationControl {
    fun start(): Boolean
    fun stop()
}
