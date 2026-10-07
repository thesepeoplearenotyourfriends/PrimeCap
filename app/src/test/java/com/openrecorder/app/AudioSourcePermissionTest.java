// SPDX-License-Identifier: GPL-3.0-only
package com.openrecorder.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class AudioSourcePermissionTest {
    @Test public void ordinaryAudioRequiresPermissionForEverySelectedAudioSource() {
        assertFalse(AudioSource.NONE.requiresRecordAudioPermission(false));
        assertTrue(AudioSource.INTERNAL.requiresRecordAudioPermission(false));
        assertTrue(AudioSource.MICROPHONE.requiresRecordAudioPermission(false));
        assertTrue(AudioSource.INTERNAL_AND_MICROPHONE.requiresRecordAudioPermission(false));
    }
    @Test public void rootPlaybackAloneDoesNotRequireRecordAudio() {
        assertFalse(AudioSource.NONE.requiresRecordAudioPermission(true));
        assertFalse(AudioSource.INTERNAL.requiresRecordAudioPermission(true));
    }
    @Test public void microphoneStillRequiresPermissionInBothModes() {
        for (boolean root : new boolean[]{false, true}) {
            assertTrue(AudioSource.MICROPHONE.requiresRecordAudioPermission(root));
            assertTrue(AudioSource.INTERNAL_AND_MICROPHONE.requiresRecordAudioPermission(root));
        }
    }
}
