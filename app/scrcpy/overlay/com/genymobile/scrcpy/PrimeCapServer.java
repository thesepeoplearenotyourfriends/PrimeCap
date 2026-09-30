package com.genymobile.scrcpy;

import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Codec;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoCodec;
import com.genymobile.scrcpy.video.VideoPacketSink;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** PrimeCap-only entry point: privileged H.264 display capture over one local socket. */
final class PrimeCapServer {
    private static final int MAGIC = 0x50434150; // PCAP
    private static final int VERSION = 1;
    private static final int TYPE_FORMAT = 1;
    private static final int TYPE_SAMPLE = 2;
    private static final int TYPE_END = 3;
    private static final int TYPE_ERROR = 4;
    private static final int MAX_PACKET_SIZE = 16 * 1024 * 1024;

    private PrimeCapServer() {}

    static void run(String... args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("primecap requires socket, max-size, bitrate and max-fps");
        }
        String socketName = args[1];
        int maxSize = positiveInt("max-size", args[2]);
        int bitRate = positiveInt("bitrate", args[3]);
        float maxFps = Float.parseFloat(args[4]);
        if (!(maxFps > 0) || maxFps > 240) {
            throw new IllegalArgumentException("Invalid max-fps");
        }

        // Parse only fixed video options. This branch never creates scrcpy audio,
        // control, DesktopConnection, recorder or transport objects.
        Options options = Options.parse(BuildConfig.VERSION_NAME,
                "video=true", "audio=false", "control=false",
                "video_codec=h264", "video_source=display", "display_id=0",
                "max_size=" + maxSize, "video_bit_rate=" + bitRate,
                "max_fps=" + maxFps, "send_device_meta=false",
                "send_codec_meta=false", "send_frame_meta=false", "cleanup=false");

        Workarounds.apply();
        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
        socket.setSoTimeout(0);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        DataInputStream input = new DataInputStream(socket.getInputStream());
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.flush();

        PrimeCapSink sink = new PrimeCapSink(output);
        SurfaceEncoder encoder = new SurfaceEncoder(new ScreenCapture(null, options), sink, options);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean stopRequested = new AtomicBoolean();
        encoder.start(fatalError -> finished.countDown());
        Thread stopReader = new Thread(() -> {
            try {
                input.readByte();
            } catch (IOException ignored) {
                // Closing the owner socket is also a stop request.
            }
            stopRequested.set(true);
            encoder.stop();
        }, "primecap-stop");
        stopReader.setDaemon(true);
        stopReader.start();

        try {
            finished.await();
            if (stopRequested.get()) {
                sink.writeEnd();
            } else {
                sink.writeError("Privileged display encoder stopped unexpectedly");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            encoder.stop();
            sink.writeError("Helper interrupted");
        } finally {
            encoder.stop();
            encoder.join();
            socket.close();
        }
    }

    private static int positiveInt(String name, String value) {
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return parsed;
    }

    private static final class PrimeCapSink implements VideoPacketSink {
        private final DataOutputStream output;
        private int width;
        private int height;
        private boolean formatSent;

        PrimeCapSink(DataOutputStream output) {
            this.output = output;
        }

        @Override
        public Codec getCodec() {
            return VideoCodec.H264;
        }

        @Override
        public synchronized void writeVideoHeader(Size size) {
            width = size.getWidth();
            height = size.getHeight();
        }

        @Override
        public synchronized void writePacket(ByteBuffer buffer, MediaCodec.BufferInfo info) throws IOException {
            ByteBuffer packet = buffer.duplicate();
            packet.position(info.offset);
            packet.limit(info.offset + info.size);
            byte[] bytes = new byte[packet.remaining()];
            packet.get(bytes);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                if (!formatSent) {
                    writeFormat(bytes, new byte[0]);
                }
                return;
            }
            if (!formatSent) {
                throw new IOException("Encoded sample arrived before codec configuration");
            }
            int payloadLength = 8 + 4 + 4 + bytes.length;
            if (payloadLength > MAX_PACKET_SIZE) {
                throw new IOException("Encoded sample is too large");
            }
            output.writeByte(TYPE_SAMPLE);
            output.writeInt(payloadLength);
            output.writeLong(info.presentationTimeUs);
            output.writeInt(info.flags);
            output.writeInt(bytes.length);
            output.write(bytes);
            output.flush();
        }

        @Override
        public synchronized void writeVideoFormat(MediaFormat format) throws IOException {
            byte[] csd0 = copyBuffer(format.getByteBuffer("csd-0"));
            byte[] csd1 = copyBuffer(format.getByteBuffer("csd-1"));
            writeFormat(csd0, csd1);
        }

        private void writeFormat(byte[] csd0, byte[] csd1) throws IOException {
            if (formatSent) {
                throw new IOException("Video format changed during recording");
            }
            int csdLength = csd0.length + csd1.length;
            if (width <= 0 || height <= 0 || csd0.length == 0 || csdLength > MAX_PACKET_SIZE - 16) {
                throw new IOException("Invalid H.264 output format");
            }
            output.writeByte(TYPE_FORMAT);
            output.writeInt(16 + csdLength);
            output.writeInt(width);
            output.writeInt(height);
            output.writeInt(csd0.length);
            output.write(csd0);
            output.writeInt(csd1.length);
            output.write(csd1);
            output.flush();
            formatSent = true;
        }

        private static byte[] copyBuffer(ByteBuffer source) {
            if (source == null) {
                return new byte[0];
            }
            ByteBuffer copy = source.duplicate();
            byte[] bytes = new byte[copy.remaining()];
            copy.get(bytes);
            return bytes;
        }

        synchronized void writeEnd() throws IOException {
            output.writeByte(TYPE_END);
            output.writeInt(0);
            output.flush();
        }

        synchronized void writeError(String message) {
            try {
                byte[] bytes = message.getBytes("UTF-8");
                bytes = Arrays.copyOf(bytes, Math.min(bytes.length, 4096));
                output.writeByte(TYPE_ERROR);
                output.writeInt(bytes.length);
                output.write(bytes);
                output.flush();
            } catch (IOException ignored) {
                // The owner may already have closed the socket.
            }
        }
    }
}
