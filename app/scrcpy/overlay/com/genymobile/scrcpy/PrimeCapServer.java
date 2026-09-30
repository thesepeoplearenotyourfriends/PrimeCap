package com.genymobile.scrcpy;

import com.genymobile.scrcpy.device.Size;
import com.genymobile.scrcpy.device.DisplayInfo;
import com.genymobile.scrcpy.device.Orientation;
import com.genymobile.scrcpy.util.Codec;
import com.genymobile.scrcpy.video.ScreenCapture;
import com.genymobile.scrcpy.video.SurfaceEncoder;
import com.genymobile.scrcpy.video.VideoCodec;
import com.genymobile.scrcpy.video.VideoPacketSink;
import com.genymobile.scrcpy.wrappers.ServiceManager;

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

/** Persistent shell-context H.264 display capture daemon over one local socket. */
final class PrimeCapServer {
    private static final int MAGIC = 0x50434150; // PCAP
    private static final int VERSION = 3;
    private static final String SOCKET_NAME = "primecap_video_daemon";
    private static final int COMMAND_START = 1;
    private static final int COMMAND_STOP = 2;
    private static final int TYPE_FORMAT = 1;
    private static final int TYPE_SAMPLE = 2;
    private static final int TYPE_END = 3;
    private static final int TYPE_ERROR = 4;
    private static final int MAX_PACKET_SIZE = 16 * 1024 * 1024;
    private static final int ORIENTATION_AUTOMATIC = 0;
    private static final int ORIENTATION_PORTRAIT = 1;
    private static final int ORIENTATION_LANDSCAPE = 2;

    private PrimeCapServer() {}

    static void run(String... args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("primecap-daemon accepts no arguments");
        }

        // Apply framework workarounds once while this process still has the exact
        // context inherited from adb shell. Every recording session reuses it.
        Workarounds.apply();
        LocalServerSocket server = new LocalServerSocket(SOCKET_NAME);
        System.out.println("PrimeCap video daemon ready on @" + SOCKET_NAME
                + " (pid=" + android.os.Process.myPid()
                + ", uid=" + android.os.Process.myUid() + ")");
        while (true) {
            LocalSocket client = server.accept();
            try {
                runSession(client);
            } catch (Exception error) {
                System.err.println("PrimeCap video session failed: " + error.getMessage());
                error.printStackTrace(System.err);
            } finally {
                try {
                    client.close();
                } catch (IOException ignored) {
                }
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
        float maxFps = input.readFloat();
        if (!(maxFps > 0) || maxFps > 240) {
            throw new IllegalArgumentException("Invalid fps");
        }
        int recordingOrientation = input.readInt();
        if (recordingOrientation < ORIENTATION_AUTOMATIC
                || recordingOrientation > ORIENTATION_LANDSCAPE) {
            throw new IllegalArgumentException("Invalid orientation");
        }

        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(android.os.Process.myPid());
        output.writeInt(android.os.Process.myUid());
        output.flush();

        PrimeCapSink sink = new PrimeCapSink(output);
        try {
            Options options = createOptions(maxSize, bitRate, maxFps, recordingOrientation);
            SurfaceEncoder encoder = new SurfaceEncoder(new ScreenCapture(null, options), sink, options);
            CountDownLatch finished = new CountDownLatch(1);
            AtomicBoolean stopRequested = new AtomicBoolean();
            encoder.start(fatalError -> finished.countDown());
            Thread stopReader = new Thread(() -> {
                try {
                    int command = input.readUnsignedByte();
                    if (command != COMMAND_STOP) {
                        sink.writeError("Unknown daemon command: " + command);
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

    private static Options createOptions(int maxSize, int bitRate, float maxFps,
            int recordingOrientation) throws Exception {
        String captureOrientation = resolveCaptureOrientation(recordingOrientation);
        return Options.parse(BuildConfig.VERSION_NAME,
                "video=true", "audio=false", "control=false",
                "video_codec=h264", "video_source=display", "display_id=0",
                "max_size=" + maxSize, "video_bit_rate=" + bitRate,
                "max_fps=" + maxFps, "send_device_meta=false",
                "send_codec_meta=false", "send_frame_meta=false", "cleanup=false",
                "capture_orientation=" + captureOrientation);
    }

    private static int positiveInt(String name, int value) {
        if (value <= 0) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return value;
    }

    private static String resolveCaptureOrientation(int requestedOrientation) {
        if (requestedOrientation == ORIENTATION_AUTOMATIC) {
            return "@";
        }

        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(0);
        if (displayInfo == null) {
            throw new IllegalStateException("Main display is unavailable");
        }
        Size size = displayInfo.getSize();
        boolean currentPortrait = size.getHeight() >= size.getWidth();
        boolean wantPortrait = requestedOrientation == ORIENTATION_PORTRAIT;
        Orientation current = Orientation.fromRotation(displayInfo.getRotation());
        int captureRotation = current.getRotation();
        if (currentPortrait != wantPortrait) {
            captureRotation = (captureRotation + 1) % 4;
        }
        return "@" + orientationName(captureRotation);
    }

    private static String orientationName(int rotation) {
        switch (rotation) {
            case 0: return "0";
            case 1: return "90";
            case 2: return "180";
            case 3: return "270";
            default: throw new AssertionError("Invalid rotation: " + rotation);
        }
    }

    private static final class PrimeCapSink implements VideoPacketSink {
        private final DataOutputStream output;
        private int width;
        private int height;
        private boolean formatSent;
        private byte[] pendingCsd0 = new byte[0];
        private byte[] pendingCsd1 = new byte[0];

        PrimeCapSink(DataOutputStream output) {
            this.output = output;
        }

        @Override
        public Codec getCodec() {
            return VideoCodec.H264;
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
            if (width <= 0 || height <= 0 || !hasH264ParameterSets(csd0, csd1)) {
                return;
            }
            if (csdLength > MAX_PACKET_SIZE - 16) {
                throw new IOException("H.264 codec configuration is too large");
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

        private static boolean hasH264ParameterSets(byte[] first, byte[] second) {
            int types = parameterSetTypes(first) | parameterSetTypes(second);
            return (types & 1) != 0 && (types & 2) != 0;
        }

        private static int parameterSetTypes(byte[] data) {
            int types = 0;
            for (int i = 0; i + 3 < data.length; ++i) {
                int startCodeLength = data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1
                        ? 3
                        : i + 4 < data.length && data[i] == 0 && data[i + 1] == 0
                                && data[i + 2] == 0 && data[i + 3] == 1 ? 4 : 0;
                if (startCodeLength > 0 && i + startCodeLength < data.length) {
                    int nalType = data[i + startCodeLength] & 0x1f;
                    if (nalType == 7) {
                        types |= 1;
                    } else if (nalType == 8) {
                        types |= 2;
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
