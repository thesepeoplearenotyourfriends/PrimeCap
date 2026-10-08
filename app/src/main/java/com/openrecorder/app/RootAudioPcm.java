// SPDX-License-Identifier: GPL-3.0-only
package com.openrecorder.app;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Root writeback PCM layout; timeline offsets and durations always count frames. */
final class RootAudioPcm {
    static final int CHANNEL_COUNT = 2;

    static int sampleCount(int frames) { return Math.multiplyExact(frames, CHANNEL_COUNT); }
    static int byteCount(int frames) { return Math.multiplyExact(sampleCount(frames), Short.BYTES); }

    static int copyFrames(ByteBuffer destination, short[] stereo, int startFrame, int frames) {
        destination.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                .put(stereo, sampleCount(startFrame), sampleCount(frames));
        return byteCount(frames);
    }

    static void mixMicrophone(short[] stereo, short[] microphone, int frames, float gain) {
        for (int frame = 0; frame < frames; frame++) {
            int mic = Math.round(microphone[frame] * gain);
            for (int channel = 0; channel < CHANNEL_COUNT; channel++) {
                int index = frame * CHANNEL_COUNT + channel;
                int mixed = stereo[index] + mic;
                stereo[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
            }
        }
    }

    private RootAudioPcm() {}
}
