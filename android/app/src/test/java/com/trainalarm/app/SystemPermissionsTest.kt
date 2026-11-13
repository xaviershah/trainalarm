package com.trainalarm.app

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.runBlocking

class SystemPermissionsTest {
    private class FakePlatform : PermissionPlatform {
        val granted = mutableSetOf<PermissionKind>()
        val grantOnPrompt = mutableSetOf<PermissionKind>()
        val prompts = mutableListOf<PermissionKind>()
        override fun isGranted(kind: PermissionKind) = kind in granted
        override suspend fun prompt(kind: PermissionKind) {
            prompts += kind
            if (kind in grantOnPrompt) granted += kind
        }
    }

    private class MemoryAsked : AskedStore {
        val asked = mutableSetOf<PermissionKind>()
        override fun wasAsked(kind: PermissionKind) = kind in asked
        override fun markAsked(kind: PermissionKind) { asked += kind }
    }

    private val platform = FakePlatform()
    private val asked = MemoryAsked()
    private val permissions = SystemPermissions(platform, asked)

    @Test fun androidRequiresAlarmsAndNotifications() {
        assertEquals(listOf(PermissionKind.ALARM, PermissionKind.NOTIFICATIONS), permissions.requiredKinds)
        assertEquals(listOf(PermissionKind.LOCATION, PermissionKind.FULL_SCREEN_INTENT), permissions.optionalKinds)
    }

    @Test fun statusIsGrantedNotDeterminedThenDeniedOnceAsked() {
        assertEquals(PermissionStatus.NOT_DETERMINED, permissions.status(PermissionKind.ALARM))
        asked.markAsked(PermissionKind.ALARM)
        assertEquals(PermissionStatus.DENIED, permissions.status(PermissionKind.ALARM))
        platform.granted += PermissionKind.ALARM
        assertEquals(PermissionStatus.GRANTED, permissions.status(PermissionKind.ALARM))
    }

    @Test fun aRequestPromptsOnceAndReturnsTheOutcome() = runBlocking<Unit> {
        platform.grantOnPrompt += PermissionKind.LOCATION
        assertEquals(PermissionStatus.GRANTED, permissions.request(PermissionKind.LOCATION))
        assertEquals(listOf(PermissionKind.LOCATION), platform.prompts)
    }

    @Test fun aRefusalIsNeverRepromptedAutomatically() = runBlocking<Unit> {
        assertEquals(PermissionStatus.DENIED, permissions.request(PermissionKind.NOTIFICATIONS))
        assertEquals(PermissionStatus.DENIED, permissions.request(PermissionKind.NOTIFICATIONS))
        assertEquals(listOf(PermissionKind.NOTIFICATIONS), platform.prompts)
    }

    @Test fun anAlreadyGrantedPermissionIsNotPrompted() = runBlocking<Unit> {
        platform.granted += PermissionKind.ALARM
        assertEquals(PermissionStatus.GRANTED, permissions.request(PermissionKind.ALARM))
        assertEquals(emptyList<PermissionKind>(), platform.prompts)
    }

    @Test fun theAskedStoreRemembersAcrossInstances() {
        val kv = object : KeyValues {
            val map = mutableMapOf<String, Any>()
            override fun getString(key: String) = map[key] as? String
            override fun putString(key: String, value: String) { map[key] = value }
            override fun getInt(key: String) = map[key] as? Int
            override fun putInt(key: String, value: Int) { map[key] = value }
        }
        KeyValuesAskedStore(kv).markAsked(PermissionKind.LOCATION)
        assertEquals(true, KeyValuesAskedStore(kv).wasAsked(PermissionKind.LOCATION))
        assertEquals(false, KeyValuesAskedStore(kv).wasAsked(PermissionKind.ALARM))
    }
}
