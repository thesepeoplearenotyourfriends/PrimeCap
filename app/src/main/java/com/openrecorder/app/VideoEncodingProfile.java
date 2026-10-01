package com.openrecorder.app;

/** Resolves capture dimensions and the exact target bitrate sent to the video encoder. */
final class VideoEncodingProfile {
    private static final int LONG_EDGE_1080P = 1_920;
    private static final int LONG_EDGE_720P = 1_280;
    private static final int LONG_EDGE_480P = 854;
    private static final int MAX_AUTOMATIC_VIDEO_BIT_RATE = 24_000_000;
    private static final int BASE_FRAME_RATE = RecordingOptions.VIDEO_FRAME_RATE_30_FPS;
    private static final int AUTOMATIC_BITRATE_DIVISOR = 10;

    private VideoEncodingProfile() {
    }

    static Layout resolve(
            int requestedWidth,
            int requestedHeight,
            int requestedResolution,
            int requestedBitrate) {
        return resolve(
                requestedWidth,
                requestedHeight,
                requestedResolution,
                requestedBitrate,
                BASE_FRAME_RATE);
    }

    static Layout resolve(
            int requestedWidth,
            int requestedHeight,
            int requestedResolution,
            int requestedBitrate,
            int targetFrameRate) {
        if (requestedWidth <= 0 || requestedHeight <= 0) {
            throw new IllegalArgumentException("Capture dimensions must be positive");
        }

        int maximumLongEdge = maximumLongEdge(requestedResolution);
        int sourceLongEdge = Math.max(requestedWidth, requestedHeight);
        double scale = maximumLongEdge > 0 && sourceLongEdge > maximumLongEdge
                ? (double) maximumLongEdge / sourceLongEdge
                : 1.0;
        int width = toEvenDimension(requestedWidth * scale);
        int height = toEvenDimension(requestedHeight * scale);
        int videoBitrate = resolveBitrate(width, height, requestedBitrate, targetFrameRate);
        return new Layout(width, height, videoBitrate);
    }

    private static int resolveBitrate(
            int width,
            int height,
            int requestedBitrate,
            int targetFrameRate) {
        int normalizedBitrate = RecordingOptions.normalizeVideoBitrate(requestedBitrate);
        if (normalizedBitrate != RecordingOptions.VIDEO_BITRATE_AUTO) {
            return normalizedBitrate;
        }

        int normalizedFrameRate = RecordingOptions.normalizeVideoFrameRate(targetFrameRate);
        int bitrateFrameRate = normalizedFrameRate == RecordingOptions.VIDEO_FRAME_RATE_AUTO
                ? BASE_FRAME_RATE
                : normalizedFrameRate;
        long automaticBitrate = (long) width
                * height
                * bitrateFrameRate
                / AUTOMATIC_BITRATE_DIVISOR;
        return (int) Math.min(MAX_AUTOMATIC_VIDEO_BIT_RATE, automaticBitrate);
    }

    private static int maximumLongEdge(int requestedResolution) {
        switch (RecordingOptions.normalizeVideoResolution(requestedResolution)) {
            case RecordingOptions.VIDEO_RESOLUTION_NATIVE:
                return 0;
            case RecordingOptions.VIDEO_RESOLUTION_720P:
                return LONG_EDGE_720P;
            case RecordingOptions.VIDEO_RESOLUTION_480P:
                return LONG_EDGE_480P;
            case RecordingOptions.VIDEO_RESOLUTION_1080P:
            default:
                return LONG_EDGE_1080P;
        }
    }

    private static int toEvenDimension(double value) {
        int rounded = (int) Math.round(value);
        return Math.max(2, rounded & ~1);
    }

    static final class Layout {
        final int width;
        final int height;
        final int videoBitrate;

        Layout(int width, int height, int videoBitrate) {
            this.width = width;
            this.height = height;
            this.videoBitrate = videoBitrate;
        }
    }
}
