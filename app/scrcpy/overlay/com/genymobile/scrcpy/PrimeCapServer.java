package com.genymobile.scrcpy;

import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.util.Codec;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoCodec;
import com.genymobile.scrcpy.video.VideoPacketSink;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-session shell-context display capture daemon over one local socket. */
final class PrimeCapServer {
    private static final int MAGIC = 0x50434150; // PCAP
    private static final int VERSION = 6;
    private static final String SOCKET_NAME = "primecap_video_daemon";
    private static final int COMMAND_START = 1;
    private static final int COMMAND_STOP = 2;
    private static final int COMMAND_REQUEST_SYNC_FRAME = 3;
    private static final int TYPE_FORMAT = 1;
    private static final int TYPE_SAMPLE = 2;
    private static final int TYPE_END = 3;
    private static final int TYPE_ERROR = 4;
    private static final int MAX_PACKET_SIZE = 16 * 1024 * 1024;
    private static final int CODEC_H264 = 0;
    private static final int CODEC_H265 = 1;

    private PrimeCapServer() {}

    static void run(String... args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("primecap-daemon accepts no arguments");
        }

        // Apply framework workarounds while this process still has the exact
        // context inherited from adb shell.
        Workarounds.apply();
        try (LocalServerSocket server = new LocalServerSocket(SOCKET_NAME)) {
            System.out.println("PrimeCap video daemon ready on @" + SOCKET_NAME
                    + " (pid=" + android.os.Process.myPid()
                    + ", uid=" + android.os.Process.myUid() + ")");
            try (LocalSocket client = server.accept()) {
                runSession(client);
            } catch (Exception error) {
                System.err.println("PrimeCap video session failed: " + error.getMessage());
                error.printStackTrace(System.err);
            }
        }
    }

    private static void runSession(LocalSocket socket) throws Exception {
        socket.setSoTimeout(0);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        DataInputStream input = new DataInputStream(socket.getInputStream());
        if (input.readInt() != MAGIC || input.readInt() != VERSION) {
            throw new IOException("Unsupported PrimeCap client protocol");
        }
        if (input.readUnsignedByte() != COMMAND_START) {
            throw new IOException("Expected START command");
        }
        int maxSize = positiveInt("max-size", input.readInt());
        int bitRate = positiveInt("bitrate", input.readInt());
        VideoCodec videoCodec = readVideoCodec(input.readInt());
        float maxFps = input.readFloat();
        if (!(maxFps > 0) || maxFps > 240) {
            throw new IllegalArgumentException("Invalid fps");
        }
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(android.os.Process.myPid());
        output.writeInt(android.os.Process.myUid());
        output.flush();

        PrimeCapSink sink = new PrimeCapSink(output, videoCodec);
        try {
            Options options = createOptions(maxSize, bitRate, videoCodec, maxFps);
            SurfaceEncoder encoder = new SurfaceEncoder(new ScreenCapture(null, options), sink, options);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicBoolean stopRequested = new AtomicBoolean();
            AtomicBoolean encoderFailed = new AtomicBoolean();
            encoder.start(fatalError -> {
                encoderFailed.set(fatalError);
                finished.countDown();
            });
            Thread stopReader = new Thread(() -> {
                try {
                    while (true) {
                        int command = input.readUnsignedByte();
                        if (command == COMMAND_STOP) {
                            stopRequested.set(true);
                            encoder.stop();
                            return;
                        }
                        if (command == COMMAND_REQUEST_SYNC_FRAME) {
                            if (!encoder.requestSyncFrame()) {
                                sink.writeError("Unable to request a "
                                        + videoCodec.getName() + " sync frame");
                                encoder.stop();
                                return;
                            }
                            continue;
                        }
                        sink.writeError("Unknown daemon command: " + command);
                        encoder.stop();
                        return;
                    }
                } catch (IOException ignored) {
                    // Closing the session socket is also a STOP request.
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
                } else if (encoderFailed.get()) {
                    sink.writeError(videoCodec.getName()
                            + " encoder failed or is unavailable on this device");
                } else {
                    sink.writeError("Display encoder stopped unexpectedly");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                encoder.stop();
                sink.writeError("Video daemon session interrupted");
            } finally {
                encoder.stop();
                encoder.join();
            }
        } catch (Exception error) {
            sink.writeError(error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage());
            throw error;
        }
    }

    private static Options createOptions(int maxSize, int bitRate, VideoCodec videoCodec,
            float maxFps) throws Exception {
        return Options.parse(BuildConfig.VERSION_NAME,
                "video=true", "audio=false", "control=false",
                "video_codec=" + videoCodec.getName(), "video_source=display", "display_id=0",
                "max_size=" + maxSize, "video_bit_rate=" + bitRate,
                "max_fps=" + maxFps, "send_device_meta=false",
                "send_codec_meta=false", "send_frame_meta=false", "cleanup=false");
    }

    private static VideoCodec readVideoCodec(int codec) {
        if (codec == CODEC_H264) {
            return VideoCodec.H264;
        }
        if (codec == CODEC_H265) {
            return VideoCodec.H265;
        }
        throw new IllegalArgumentException("Unsupported video codec: " + codec);
    }

    private static int positiveInt(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static final class PrimeCapSink implements VideoPacketSink {
        private final DataOutputStream output;
        private final VideoCodec videoCodec;
        private int width;
        private int height;
        private boolean formatSent;
        private byte[] pendingCsd0 = new byte[0];
        private byte[] pendingCsd1 = new byte[0];

        PrimeCapSink(DataOutputStream output, VideoCodec videoCodec) {
            this.output = output;
            this.videoCodec = videoCodec;
        }

        @Override
        public Codec getCodec() {
            return videoCodec;
        }

        @Override
        public synchronized void writeVideoHeader(Size size) {
            if (!formatSent) {
                width = size.getWidth();
                height = size.getHeight();
            }
        }

        @Override
        public synchronized void writePacket(ByteBuffer buffer, MediaCodec.BufferInfo info) throws IOException {
            ByteBuffer packet = buffer.duplicate();
            packet.position(info.offset);
            packet.limit(info.offset + info.size);
            byte[] bytes = new byte[packet.remaining()];
            packet.get(bytes);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                if (!formatSent && bytes.length > 0) {
                    pendingCsd0 = concatenate(pendingCsd0, bytes);
                    maybeWriteFormat();
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
            if (formatSent) {
                return;
            }
            if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                width = format.getInteger(MediaFormat.KEY_WIDTH);
            }
            if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                height = format.getInteger(MediaFormat.KEY_HEIGHT);
            }
            byte[] csd0 = copyBuffer(format.getByteBuffer("csd-0"));
            byte[] csd1 = copyBuffer(format.getByteBuffer("csd-1"));
            if (csd0.length > 0) {
                pendingCsd0 = csd0;
                pendingCsd1 = csd1;
            }
            maybeWriteFormat();
        }

        private void maybeWriteFormat() throws IOException {
            byte[] csd0 = pendingCsd0;
            byte[] csd1 = pendingCsd1;
            int csdLength = csd0.length + csd1.length;
            if (width <= 0 || height <= 0 || !hasRequiredParameterSets(csd0, csd1)) {
                return;
            }
            if (csdLength > MAX_PACKET_SIZE - 16) {
                throw new IOException(videoCodec.getName() + " codec configuration is too large");
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

        private boolean hasRequiredParameterSets(byte[] first, byte[] second) {
            int types = parameterSetTypes(first) | parameterSetTypes(second);
            int required = videoCodec == VideoCodec.H265 ? 1 | 2 | 4 : 1 | 2;
            return (types & required) == required;
        }

        private int parameterSetTypes(byte[] data) {
            int types = 0;
            for (int i = 0; i + 3 < data.length; ++i) {
                int startCodeLength = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1
                        ? 3
                        : i + 4 < data.length && data[i] == 0 && data[i + 1] == 0
                                && data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0;
                if (startCodeLength > 0 && i + startCodeLength < data.length) {
                    int header = data[i + startCodeLength] & 0xff;
                    if (videoCodec == VideoCodec.H265) {
                        int nalType = (header >> 1) & 0x3f;
                        if (nalType == 32) {
                            types |= 1; // VPS
                        } else if (nalType == 33) {
                            types |= 2; // SPS
                        } else if (nalType == 34) {
                            types |= 4; // PPS
                        }
                    } else {
                        int nalType = header & 0x1f;
                        if (nalType == 7) {
                            types |= 1; // SPS
                        } else if (nalType == 8) {
                            types |= 2; // PPS
                        }
                    }
                }
            }
            return types;
        }

        private static byte[] concatenate(byte[] first, byte[] second) {
            byte[] combined = Arrays.copyOf(first, first.length + second.length);
            System.arraycopy(second, 0, combined, first.length, second.length);
            return combined;
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
