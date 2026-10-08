package com.openrecorder.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RootAudioPcmTest {
    @Test public void framesSelectWholeStereoPairsAndFourBytesEach() {
        short[] samples = {1, 2, 3, 4, 5, 6, 7, 8};
        int offsetFrames = 1;
        int copiedFrames = 2;
        ByteBuffer pcm = ByteBuffer.allocate(RootAudioPcm.byteCount(copiedFrames))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(8, RootAudioPcm.copyFrames(pcm, samples, offsetFrames, copiedFrames));
        assertEquals(4, RootAudioPcm.sampleCount(copiedFrames));
        assertEquals(8, pcm.capacity());
        assertArrayEquals(new byte[]{3, 0, 4, 0, 5, 0, 6, 0}, pcm.array());
        assertEquals(1_000_000_000L, AudioFrameClock.framesToNanos(48000, 48000));
    }
    @Test public void monoMicrophoneIsGainedAndAddedEquallyWithIndependentClipping() {
        short[] stereo = {1000, -500, 32000, -32000, -32000, 32000};
        RootAudioPcm.mixMicrophone(stereo, new short[]{100, 1000, -1000}, 3, 1.4f);
        assertArrayEquals(new short[]{1140, -360, 32767, -30600, -32768, 30600}, stereo);
    }
    @Test public void clippingAndPauseRemovalRetainStereoPairsAtFrameOffsets() {
        long start = 1_000_000_000L;
        int rate = 48000;
        RecordingTimeline timeline = new RecordingTimeline(start + AudioFrameClock.framesToNanos(2, rate));
        timeline.pause(start + AudioFrameClock.framesToNanos(4, rate));
        timeline.resume(start + AudioFrameClock.framesToNanos(6, rate));
        short[] samples = {10, -10, 20, -20, 30, -30, 40, -40, 50, -50, 60, -60, 70, -70, 80, -80};
        long end = start + AudioFrameClock.framesToNanos(8, rate);
        RecordingTimeline.IncludedRange first = timeline.findNextIncludedRange(start, end);
        int firstFrame = (int) AudioFrameClock.nanosToFramesCeil(first.startNanos - start, rate);
        int firstEnd = (int) AudioFrameClock.nanosToFramesCeil(first.endNanos - start, rate);
        ByteBuffer firstPcm = ByteBuffer.allocate(8);
        assertEquals(8, RootAudioPcm.copyFrames(firstPcm, samples, firstFrame, firstEnd - firstFrame));
        short[] firstSamples = new short[4];
        firstPcm.asShortBuffer().get(firstSamples);
        assertArrayEquals(new short[]{30, -30, 40, -40}, firstSamples);
        RecordingTimeline.IncludedRange second = timeline.findNextIncludedRange(first.endNanos, end);
        int secondFrame = (int) AudioFrameClock.nanosToFramesCeil(second.startNanos - start, rate);
        int secondEnd = (int) AudioFrameClock.nanosToFramesCeil(second.endNanos - start, rate);
        ByteBuffer secondPcm = ByteBuffer.allocate(8);
        assertEquals(8, RootAudioPcm.copyFrames(secondPcm, samples, secondFrame, secondEnd - secondFrame));
        short[] secondSamples = new short[4];
        secondPcm.asShortBuffer().get(secondSamples);
        assertArrayEquals(new short[]{70, -70, 80, -80}, secondSamples);
    }
}
