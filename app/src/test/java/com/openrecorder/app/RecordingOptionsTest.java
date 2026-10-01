package com.openrecorder.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RecordingOptionsTest {
    @Test
    public void fixedVideoBitratesArePreservedExactly() {
        int[] bitrates = {
                750_000, 1_000_000, 1_500_000, 2_000_000, 3_000_000,
                4_000_000, 8_000_000, 16_000_000, 24_000_000,
        };
        for (int bitrate : bitrates) {
            assertEquals(bitrate, RecordingOptions.normalizeVideoBitrate(bitrate));
        }
    }

    @Test
    public void allCodecSelectionsUseH264() {
        assertEquals(
                RecordingOptions.VIDEO_CODEC_H264,
                RecordingOptions.normalizeVideoCodec(1));
    }

    @Test
    public void recordingTimeoutAcceptsOnlyAvailableWholeMinuteValues() {
        int[] timeouts = {1, 5, 10, 30, 60};
        for (int timeout : timeouts) {
            assertEquals(timeout, RecordingOptions.normalizeRecordingTimeoutMinutes(timeout));
        }
        assertEquals(0, RecordingOptions.normalizeRecordingTimeoutMinutes(-1));
        assertEquals(0, RecordingOptions.normalizeRecordingTimeoutMinutes(2));
    }

    @Test
    public void factoryProfileIsTheDefault() {
        assertEquals(
                RecordingOptions.SAMPLE_RATE_44_1_KHZ,
                RecordingOptions.DEFAULT_SAMPLE_RATE);
        assertEquals(
                RecordingOptions.VIDEO_RESOLUTION_720P,
                RecordingOptions.DEFAULT_VIDEO_RESOLUTION);
        assertEquals(
                RecordingOptions.VIDEO_FRAME_RATE_30_FPS,
                RecordingOptions.DEFAULT_VIDEO_FRAME_RATE);
        assertEquals(
                RecordingOptions.VIDEO_CODEC_H264,
                RecordingOptions.DEFAULT_VIDEO_CODEC);
        assertEquals(
                RecordingOptions.VIDEO_BITRATE_2_MBPS,
                RecordingOptions.DEFAULT_VIDEO_BITRATE);
        assertEquals(
                RecordingOptions.ORIENTATION_LANDSCAPE,
                RecordingOptions.DEFAULT_ORIENTATION);
    }
}
