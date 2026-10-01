// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

import android.content.ContentResolver
import android.provider.Settings

internal enum class DeviceRotationState(val userRotation: Int?) {
    UNLOCKED(null),
    LOCKED_90(1),
    LOCKED_270(3),
}

internal object DeviceRotationController {
    fun read(contentResolver: ContentResolver): DeviceRotationState? {
        val sensorRotation = Settings.System.getInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            1,
        )
        if (sensorRotation != 0) return DeviceRotationState.UNLOCKED

        return when (
            Settings.System.getInt(contentResolver, Settings.System.USER_ROTATION, 0)
        ) {
            1 -> DeviceRotationState.LOCKED_90
            3 -> DeviceRotationState.LOCKED_270
            else -> null
        }
    }

    fun apply(state: DeviceRotationState): Boolean {
        val command = if (state == DeviceRotationState.UNLOCKED) {
            "settings put system accelerometer_rotation 1"
        } else {
            "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation ${state.userRotation}"
        }
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}
