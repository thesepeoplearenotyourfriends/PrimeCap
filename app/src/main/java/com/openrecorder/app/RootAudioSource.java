// SPDX-License-Identifier: GPL-3.0-only
package com.openrecorder.app;

import android.content.Context;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Owns a single root helper and continuously drains it, including during preparation. */
final class RootAudioSource implements AutoCloseable {
    private static final String TAG = "RootAudioSource";
    private final ArrayBlockingQueue<RootAudioProtocol.Block> blocks = new ArrayBlockingQueue<>(64);
    private final String directory = "/data/local/tmp/primecap-audio-" + UUID.randomUUID();
    private final String helperPath = directory + "/primecap-root-audio";
    private final StringBuilder diagnostics = new StringBuilder();
    private volatile boolean closed;
    private volatile boolean active;
    private volatile IOException failure;
    private volatile RootAudioProtocol.Header header;
    private java.lang.Process process;
    private File stage;

    RootAudioSource(Context context, int requestedRate) throws IOException {
        CompletableFuture<RootAudioProtocol.Header> ready = new CompletableFuture<>();
        try {
            stage = File.createTempFile("primecap-root-audio", ".stage", context.getCodeCacheDir());
            try (InputStream asset = context.getAssets().open("primecap-root-audio");
                    FileOutputStream output = new FileOutputStream(stage)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = asset.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            String command = "mkdir " + quote(directory) + " && chmod 0700 " + quote(directory)
                    + " && cp " + quote(stage.getAbsolutePath()) + " " + quote(helperPath)
                    + " && chmod 0700 " + quote(helperPath)
                    + " && exec " + quote(helperPath) + " " + requestedRate;
            process = new ProcessBuilder("su", "-c", command).start();
            Thread errors = new Thread(() -> drainErrors(), "RootAudioDiagnostics");
            errors.setDaemon(true);
            errors.start();
            Thread reader = new Thread(() -> pump(ready), "RootAudioPcm");
            reader.setDaemon(true);
            reader.start();
            header = ready.get(10, TimeUnit.SECONDS);
            if (header.sampleRate != requestedRate) {
                Log.i(TAG, "Root playback selected actual capture rate " + header.sampleRate
                        + " Hz instead of " + requestedRate + " Hz");
            }
        } catch (Exception error) {
            close();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Unable to start root playback capture: " + diagnosticText(), error);
        } finally {
            if (stage != null) stage.delete();
        }
    }

    int sampleRate() { return header.sampleRate; }
    void start() { active = true; }

    private void pump(CompletableFuture<RootAudioProtocol.Header> ready) {
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(process.getInputStream()))) {
            header = new RootAudioProtocol.Header(input);
            ready.complete(header);
            boolean warned = false;
            while (!closed) {
                RootAudioProtocol.Block block = new RootAudioProtocol.Block(input);
                if (!block.hardwareTimestamp && !warned) {
                    Log.w(TAG, "Root audio uses explicit native monotonic frame-clock fallback");
                    warned = true;
                }
                if (active && !blocks.offer(block)) {
                    throw new IOException("Root audio PCM consumer overrun");
                }
            }
        } catch (IOException error) {
            if (!closed) failure = error;
            ready.completeExceptionally(error);
        }
    }

    RootAudioProtocol.Block read() throws IOException, InterruptedException {
        while (!closed) {
            IOException error = failure;
            if (error != null) throw new IOException("Root playback capture failed: " + diagnosticText(), error);
            RootAudioProtocol.Block block = blocks.poll(250, TimeUnit.MILLISECONDS);
            if (block != null) return block;
        }
        throw new IOException("Root playback capture stopped");
    }

    private void drainErrors() {
        try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = input.readLine()) != null) {
                Log.i(TAG, line);
                synchronized (diagnostics) {
                    diagnostics.append(line).append('\n');
                    if (diagnostics.length() > 4096) diagnostics.delete(0, diagnostics.length() - 4096);
                }
            }
        } catch (IOException ignored) { }
    }
    private String diagnosticText() {
        synchronized (diagnostics) { return diagnostics.toString().trim(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (process == null) return;
        // EOF wakes the helper's independent control thread, even in blocked ALSA I/O.
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        // su may be a supervisor rather than the helper. Kill only our verified executable,
        // then remove this session's private deployment; never scan arbitrary root processes.
        String kill = header == null ? "" : "if [ \"$(readlink /proc/" + header.pid
                + "/exe)\" = " + quote(helperPath) + " ]; then kill -9 " + header.pid + "; fi; ";
        java.lang.Process cleanup = null;
        try {
            cleanup = new ProcessBuilder("su", "-c", kill
                    + "rm -f " + quote(helperPath) + "; rmdir " + quote(directory)).start();
            cleanup.waitFor(500, TimeUnit.MILLISECONDS);
        } catch (IOException error) {
            Log.w(TAG, "Unable to clean root audio helper", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally {
            if (cleanup != null && cleanup.isAlive()) cleanup.destroyForcibly();
        }
    }
    private static String quote(String text) { return "'" + text.replace("'", "'\\''") + "'"; }
}
