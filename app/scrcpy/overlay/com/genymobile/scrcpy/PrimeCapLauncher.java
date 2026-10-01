package com.genymobile.scrcpy;

import java.io.IOException;

/** Root entry point which becomes the daemon after adopting the proven shell identity. */
public final class PrimeCapLauncher {
    private PrimeCapLauncher() {}

    public static void main(String... args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected native-library, daemon, and PID paths");
        }
        System.load(args[0]);
        launch(args[1], args[2]);
        throw new IOException("PrimeCap native launcher returned without exec");
    }

    private static native void launch(String daemonPath, String pidPath) throws IOException;
}
