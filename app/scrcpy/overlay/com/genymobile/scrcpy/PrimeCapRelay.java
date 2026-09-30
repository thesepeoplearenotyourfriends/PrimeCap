package com.genymobile.scrcpy;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Disposable stdio relay between the APK's root child and the shell daemon. */
final class PrimeCapRelay {
    private static final String SOCKET_NAME = "primecap_video_daemon";
    private static final int BUFFER_SIZE = 32 * 1024;

    private PrimeCapRelay() {}

    static void run(String... args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("primecap-relay accepts no arguments");
        }

        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(
                    SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            Thread responseRelay = new Thread(() -> relayResponses(socket),
                    "primecap-relay-responses");
            responseRelay.start();
            try {
                copy(System.in, socket.getOutputStream());
                socket.shutdownOutput();
                responseRelay.join();
            } finally {
                socket.close();
                responseRelay.interrupt();
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void relayResponses(LocalSocket socket) {
        try {
            copy(socket.getInputStream(), System.out);
        } catch (IOException error) {
            System.err.println("PrimeCap relay response stream failed: " + error.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
            output.flush();
        }
    }
}
