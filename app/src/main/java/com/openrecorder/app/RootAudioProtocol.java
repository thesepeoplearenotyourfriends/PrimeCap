// SPDX-License-Identifier: GPL-3.0-only
package com.openrecorder.app;

import java.io.DataInputStream;
import java.io.IOException;

/** Version 1 wire protocol: all integers and PCM16 samples are little endian. */
final class RootAudioProtocol {
    static final int MAGIC = 0x41524350;
    static final class Header {
        final int sampleRate;
        final int pid;
        Header(DataInputStream input) throws IOException {
            if (integer(input) != MAGIC || integer(input) != 1) {
                throw new IOException("Unsupported root audio protocol");
            }
            sampleRate = integer(input);
            int channels = integer(input);
            int bits = integer(input);
            pid = integer(input);
            if ((sampleRate != 44100 && sampleRate != 48000)
                    || channels != 2 || bits != 16 || pid <= 1) {
                throw new IOException("Invalid root audio capture format");
            }
        }
    }
    static final class Block {
        final short[] mono;
        final long sourceStartNanos;
        final boolean hardwareTimestamp;
        Block(DataInputStream input) throws IOException {
            int frames = integer(input);
            int quality = integer(input);
            sourceStartNanos = Integer.toUnsignedLong(integer(input))
                    | (Integer.toUnsignedLong(integer(input)) << 32);
            if (frames < 1 || frames > 1024 || quality < 0 || quality > 1
                    || sourceStartNanos <= 0) {
                throw new IOException("Invalid root audio PCM block");
            }
            hardwareTimestamp = quality == 1;
            mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                short left = Short.reverseBytes(input.readShort());
                short right = Short.reverseBytes(input.readShort());
                mono[i] = (short) (((int) left + right) / 2);
            }
        }
    }
    private static int integer(DataInputStream input) throws IOException {
        return Integer.reverseBytes(input.readInt());
    }
    private RootAudioProtocol() {}
}
