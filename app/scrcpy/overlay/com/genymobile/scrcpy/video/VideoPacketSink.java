package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Codec;

import android.media.MediaCodec;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Destination for encoded video packets. */
public interface VideoPacketSink {
    Codec getCodec();
    void writeVideoHeader(Size size) throws IOException;
    default void writeVideoFormat(MediaFormat format) throws IOException {
        // The desktop Streamer protocol carries codec config as a packet instead.
    }
    void writePacket(ByteBuffer data, MediaCodec.BufferInfo info) throws IOException;
}
