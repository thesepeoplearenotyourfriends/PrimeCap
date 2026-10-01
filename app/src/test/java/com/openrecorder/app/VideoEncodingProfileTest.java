package com.openrecorder.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VideoEncodingProfileTest {
    @Test
    public void nativeResolutionKeepsSourceDimensions() {
        assertLayout(resolve(RecordingOptions.VIDEO_RESOLUTION_NATIVE,
                RecordingOptions.VIDEO_BITRATE_AUTO), 1080, 2340);
    }

    @Test
    public void resolutionPresetsPreserveAspectRatioAndCapLongEdge() {
        assertLayout(resolve(RecordingOptions.VIDEO_RESOLUTION_1080P, 0), 886, 1920);
        assertLayout(resolve(RecordingOptions.VIDEO_RESOLUTION_720P, 0), 590, 1280);
        assertLayout(resolve(RecordingOptions.VIDEO_RESOLUTION_480P, 0), 394, 854);
    }

    @Test
    public void presetDoesNotUpscaleSmallerContent() {
        VideoEncodingProfile.Layout layout = VideoEncodingProfile.resolve(
                720, 1280, RecordingOptions.VIDEO_RESOLUTION_1080P,
                RecordingOptions.VIDEO_BITRATE_AUTO);
        assertLayout(layout, 720, 1280);
    }

    @Test
    public void everyFixedBitrateIsPassedThroughExactly() {
        int[] fixedBitrates = {
                RecordingOptions.VIDEO_BITRATE_750_KBPS,
                RecordingOptions.VIDEO_BITRATE_1_MBPS,
                RecordingOptions.VIDEO_BITRATE_1_5_MBPS,
                RecordingOptions.VIDEO_BITRATE_2_MBPS,
                RecordingOptions.VIDEO_BITRATE_3_MBPS,
                RecordingOptions.VIDEO_BITRATE_4_MBPS,
                RecordingOptions.VIDEO_BITRATE_8_MBPS,
                RecordingOptions.VIDEO_BITRATE_16_MBPS,
                RecordingOptions.VIDEO_BITRATE_24_MBPS,
        };
        for (int bitrate : fixedBitrates) {
            assertEquals(bitrate,
                    resolve(RecordingOptions.VIDEO_RESOLUTION_1080P, bitrate).videoBitrate);
        }
    }

    @Test
    public void automaticBitrateFallsMonotonicallyWithResolution() {
        int nativeBitrate = resolve(RecordingOptions.VIDEO_RESOLUTION_NATIVE, 0).videoBitrate;
        int fullHdBitrate = resolve(RecordingOptions.VIDEO_RESOLUTION_1080P, 0).videoBitrate;
        int hdBitrate = resolve(RecordingOptions.VIDEO_RESOLUTION_720P, 0).videoBitrate;
        int sdBitrate = resolve(RecordingOptions.VIDEO_RESOLUTION_480P, 0).videoBitrate;

        assertEquals(7_581_600, nativeBitrate);
        assertEquals(5_103_360, fullHdBitrate);
        assertEquals(2_265_600, hdBitrate);
        assertEquals(1_009_428, sdBitrate);
        assertTrue(nativeBitrate > fullHdBitrate);
        assertTrue(fullHdBitrate > hdBitrate);
        assertTrue(hdBitrate > sdBitrate);
    }

    @Test
    public void automaticBitrateScalesWithFrameRate() {
        VideoEncodingProfile.Layout at30Fps = VideoEncodingProfile.resolve(
                1080, 2340, RecordingOptions.VIDEO_RESOLUTION_720P, 0,
                RecordingOptions.VIDEO_FRAME_RATE_30_FPS);
        VideoEncodingProfile.Layout at60Fps = VideoEncodingProfile.resolve(
                1080, 2340, RecordingOptions.VIDEO_RESOLUTION_720P, 0,
                RecordingOptions.VIDEO_FRAME_RATE_60_FPS);
        VideoEncodingProfile.Layout at120Fps = VideoEncodingProfile.resolve(
                1080, 2340, RecordingOptions.VIDEO_RESOLUTION_720P, 0,
                RecordingOptions.VIDEO_FRAME_RATE_120_FPS);

        assertEquals(2_265_600, at30Fps.videoBitrate);
        assertEquals(4_531_200, at60Fps.videoBitrate);
        assertEquals(9_062_400, at120Fps.videoBitrate);
    }

    @Test
    public void automaticBitrateIsCappedAt24Mbps() {
        VideoEncodingProfile.Layout layout = VideoEncodingProfile.resolve(
                1080, 2340, RecordingOptions.VIDEO_RESOLUTION_NATIVE, 0,
                RecordingOptions.VIDEO_FRAME_RATE_120_FPS);
        assertEquals(24_000_000, layout.videoBitrate);
    }

    private static VideoEncodingProfile.Layout resolve(int resolution, int bitrate) {
        return VideoEncodingProfile.resolve(1080, 2340, resolution, bitrate);
    }

    private static void assertLayout(
            VideoEncodingProfile.Layout layout, int width, int height) {
        assertEquals(width, layout.width);
        assertEquals(height, layout.height);
    }
}
