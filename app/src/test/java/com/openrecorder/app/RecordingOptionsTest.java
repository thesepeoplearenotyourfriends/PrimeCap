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
    public void h265CodecSelectionIsPreserved() {
        assertEquals(
                RecordingOptions.VIDEO_CODEC_H265,
                RecordingOptions.normalizeVideoCodec(RecordingOptions.VIDEO_CODEC_H265));
    }

    @Test
    public void factoryProfileIsTheDefault() {
        assertEquals(
                RecordingOptions.SAMPLE_RATE_44_1_KHZ,
                RecordingOptions.DEFAULT_SAMPLE_RATE);
        assertEquals(
                RecordingOptions.VIDEO_RESOLUTION_NATIVE,
                RecordingOptions.DEFAULT_VIDEO_RESOLUTION);
        assertEquals(
                RecordingOptions.VIDEO_FRAME_RATE_AUTO,
                RecordingOptions.DEFAULT_VIDEO_FRAME_RATE);
        assertEquals(
                RecordingOptions.VIDEO_CODEC_H264,
                RecordingOptions.DEFAULT_VIDEO_CODEC);
        assertEquals(
                RecordingOptions.VIDEO_BITRATE_AUTO,
                RecordingOptions.DEFAULT_VIDEO_BITRATE);
    }
}
