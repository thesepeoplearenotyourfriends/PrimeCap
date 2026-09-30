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
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;

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
    private static final int VERSION = 2;
    private static final int TYPE_FORMAT = 1;
    private static final int TYPE_SAMPLE = 2;
    private static final int TYPE_END = 3;
    private static final int TYPE_ERROR = 4;
    private static final int MAX_PACKET_SIZE = 16 * 1024 * 1024;
    private static final int ORIENTATION_AUTOMATIC = 0;
    private static final int ORIENTATION_PORTRAIT = 1;
    private static final int ORIENTATION_LANDSCAPE = 2;

    private PrimeCapServer() {}

    static void dropRootPrivileges() throws Exception {
        if (android.os.Process.myUid() == 0) {
            Os.setuid(2000);
        }
    }

    static void run(String... args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "primecap requires socket, max-size, bitrate, max-fps and recording-orientation");
        }
        String socketName = args[1];
        int maxSize = positiveInt("max-size", args[2]);
        int bitRate = positiveInt("bitrate", args[3]);
        float maxFps = Float.parseFloat(args[4]);
        if (!(maxFps > 0) || maxFps > 240) {
            throw new IllegalArgumentException("Invalid max-fps");
        }
        int recordingOrientation = Integer.parseInt(args[5]);
        if (recordingOrientation < ORIENTATION_AUTOMATIC
                || recordingOrientation > ORIENTATION_LANDSCAPE) {
            throw new IllegalArgumentException("Invalid recording-orientation");
        }

        Workarounds.apply();
        String captureOrientation = resolveCaptureOrientation(recordingOrientation);

        // Parse only fixed video options. This branch never creates scrcpy audio,
        // control, DesktopConnection, recorder or transport objects.
        Options options = Options.parse(BuildConfig.VERSION_NAME,
                "video=true", "audio=false", "control=false",
                "video_codec=h264", "video_source=display", "display_id=0",
                "max_size=" + maxSize, "video_bit_rate=" + bitRate,
                "max_fps=" + maxFps, "send_device_meta=false",
                "send_codec_meta=false", "send_frame_meta=false", "cleanup=false",
                "capture_orientation=" + captureOrientation);

        LocalSocket socket = new LocalSocket();
        socket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
        socket.setSoTimeout(0);
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());
        DataInputStream input = new DataInputStream(socket.getInputStream());
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(android.os.Process.myPid());
        output.writeInt(android.os.Process.myUid());
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
            case 0:
                return "0";
            case 1:
                return "90";
            case 2:
                return "180";
            case 3:
                return "270";
            default:
                throw new AssertionError("Invalid rotation: " + rotation);
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
