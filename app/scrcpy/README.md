# PrimeCap shell-context video daemon

This directory vendors the unmodified scrcpy 3.3.4 release archive plus a small,
reviewable daemon-mode overlay. `build.sh` extracts the archive, applies the
patch, copies the daemon sources, and invokes upstream's
`server/build_without_gradle.sh` with Android platform/build-tools 35.

Build the daemon and APK:

```sh
./gradlew buildPrimeCapVideoDaemon
./gradlew assembleDevelopment
```

PrimeCap stages and starts the daemon through root when a recording begins. A
small native launcher performs the verified `setgroups`, `setgid`, `setuid`,
`setcon`, and `execve` sequence in that order. The daemon binds the
local abstract socket `primecap_video_daemon`, accepts one recording session at
a time, and returns to its accept loop after `STOP`. It must be launched directly
with the identity and SELinux context of a real adb shell. The app retains the
root-launched process and stops only the
exact daemon PID reported by the protocol when the recording ends.

The build also packages a disposable `primecap-relay` in the APK. For each
recording, PrimeCap stages and launches it with PHH-su, exchanges the protocol
only over the child process's stdin/stdout, and then terminates it. The relay's
stdout contains only bytes copied from the daemon socket; all relay diagnostics
are written to stderr.

For each session the APK sends `START` with max size, bitrate, and fps. The
daemon leaves scrcpy capture orientation unlocked and responds with the framed `FORMAT`, `SAMPLE`,
`END`, and `ERROR` stream. Samples retain their original MediaCodec PTS and
flags. Audio and MP4 muxing remain entirely inside PrimeCap.
