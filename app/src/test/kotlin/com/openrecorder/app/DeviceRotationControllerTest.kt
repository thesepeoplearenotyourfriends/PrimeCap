// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceRotationControllerTest {
    @Test
    fun parsesUnlockedWindowManagerState() {
        assertEquals(
            DeviceRotationState.UNLOCKED,
            DeviceRotationController.parse(windowState("USER_ROTATION_FREE", "ROTATION_0", false)),
        )
    }

    @Test
    fun parsesSupportedLockedRotations() {
        assertEquals(
            DeviceRotationState.LOCKED_90,
            DeviceRotationController.parse(windowState("USER_ROTATION_LOCKED", "ROTATION_90", true)),
        )
        assertEquals(
            DeviceRotationState.LOCKED_270,
            DeviceRotationController.parse(windowState("USER_ROTATION_LOCKED", "ROTATION_270", true)),
        )
    }

    @Test
    fun rejectsSettingsStyleLockWithoutFixedWindowManagerRotation() {
        assertNull(
            DeviceRotationController.parse(windowState("USER_ROTATION_LOCKED", "ROTATION_90", false)),
        )
    }

    @Test
    fun rejectsUnsupportedOrIncompleteWindowManagerState() {
        assertNull(
            DeviceRotationController.parse(windowState("USER_ROTATION_LOCKED", "ROTATION_0", true)),
        )
        assertNull(DeviceRotationController.parse("mUserRotationMode=USER_ROTATION_LOCKED"))
    }

    private fun windowState(mode: String, rotation: String, fixed: Boolean) = """
        DisplayRotation
          mUserRotationMode=$mode mUserRotation=$rotation
          mAllowAllRotations=false mFixedToUserRotation=$fixed
    """.trimIndent()
}
