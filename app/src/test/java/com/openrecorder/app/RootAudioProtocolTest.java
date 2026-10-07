// SPDX-License-Identifier: GPL-3.0-only
package com.openrecorder.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RootAudioProtocolTest {
    private DataInputStream input(ByteBuffer buffer) {
        return new DataInputStream(new ByteArrayInputStream(buffer.array()));
    }
    private ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
    @Test public void headerPropagatesActualRateAndOwnedPid() throws Exception {
        ByteBuffer b = buffer(24).putInt(RootAudioProtocol.MAGIC).putInt(1)
                .putInt(48000).putInt(2).putInt(16).putInt(4321);
        RootAudioProtocol.Header header = new RootAudioProtocol.Header(input(b));
        assertEquals(48000, header.sampleRate);
        assertEquals(4321, header.pid);
    }
    @Test public void stereoIsDownmixedWithoutOverflowAndSourceTimestampIsPreserved() throws Exception {
        long timestamp = 987654321012345L;
        ByteBuffer b = buffer(28).putInt(3).putInt(1).putLong(timestamp)
                .putShort((short)32767).putShort((short)32767)
                .putShort((short)-32768).putShort((short)-32768)
                .putShort((short)1000).putShort((short)-500);
        RootAudioProtocol.Block block = new RootAudioProtocol.Block(input(b));
        assertArrayEquals(new short[]{32767, -32768, 250}, block.mono);
        assertEquals(timestamp, block.sourceStartNanos);
        assertTrue(block.hardwareTimestamp);
    }
    @Test public void fallbackQualityIsExplicit() throws Exception {
        ByteBuffer b = buffer(20).putInt(1).putInt(0).putLong(1000000000L)
                .putShort((short)0).putShort((short)0);
        assertFalse(new RootAudioProtocol.Block(input(b)).hardwareTimestamp);
    }
    @Test(expected = IOException.class) public void rejectsUnknownProtocol() throws Exception {
        new RootAudioProtocol.Header(input(buffer(24).putInt(RootAudioProtocol.MAGIC).putInt(2)));
    }
    @Test(expected = IOException.class) public void rejectsStereoMislabelledAsMono() throws Exception {
        new RootAudioProtocol.Header(input(buffer(24).putInt(RootAudioProtocol.MAGIC).putInt(1)
                .putInt(48000).putInt(1).putInt(16).putInt(42)));
    }
    @Test(expected = IOException.class) public void rejectsOversizedPcmBlock() throws Exception {
        new RootAudioProtocol.Block(input(buffer(16).putInt(1025).putInt(1).putLong(1)));
    }
    @Test(expected = EOFException.class) public void rejectsTruncatedSamples() throws Exception {
        new RootAudioProtocol.Block(input(buffer(16).putInt(1).putInt(1).putLong(1)));
    }
}
