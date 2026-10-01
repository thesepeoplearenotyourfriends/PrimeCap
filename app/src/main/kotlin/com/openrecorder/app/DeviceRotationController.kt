// SPDX-License-Identifier: GPL-3.0-only

package com.openrecorder.app

internal enum class DeviceRotationState(val userRotation: Int?) {
    UNLOCKED(null),
    LOCKED_90(1),
    LOCKED_270(3),
}

internal object DeviceRotationController {
    private val userRotationModePattern = Regex("\\bmUserRotationMode=(\\S+)")
    private val userRotationPattern = Regex("\\bmUserRotation=(\\S+)")
    private val fixedToUserRotationPattern = Regex("\\bmFixedToUserRotation=(\\S+)")

    fun read(): DeviceRotationState? {
        val output = runRootCommand("dumpsys window displays") ?: return null
        return parse(output)
    }

    internal fun parse(output: String): DeviceRotationState? {
        val mode = userRotationModePattern.find(output)?.groupValues?.get(1)
        val rotation = userRotationPattern.find(output)?.groupValues?.get(1)
        val fixed = fixedToUserRotationPattern.find(output)?.groupValues?.get(1)

        if (mode == "USER_ROTATION_FREE" && fixed == "false") {
            return DeviceRotationState.UNLOCKED
        }
        if (mode != "USER_ROTATION_LOCKED" || fixed != "true") return null

        return when (rotation) {
            "ROTATION_90" -> DeviceRotationState.LOCKED_90
            "ROTATION_270" -> DeviceRotationState.LOCKED_270
            else -> null
        }
    }

    fun apply(state: DeviceRotationState): Boolean {
        val command = if (state == DeviceRotationState.UNLOCKED) {
            "wm set-fix-to-user-rotation disabled && wm set-user-rotation free"
        } else {
            "wm set-user-rotation lock ${state.userRotation} && " +
                "wm set-fix-to-user-rotation enabled"
        }
        return runRootCommand(command) != null
    }

    private fun runRootCommand(command: String): String? {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0) output else null
        } catch (_: Exception) {
            null
        }
    }
}
