# PrimeCap shell-context video daemon

This directory vendors the unmodified scrcpy 3.3.4 release archive plus a small,
reviewable daemon-mode overlay. `build.sh` extracts the archive, applies the
patch, copies the daemon sources, and invokes upstream's
`server/build_without_gradle.sh` with Android platform/build-tools 35.

Build and start the daemon from the host:

```sh
./gradlew buildPrimeCapVideoDaemon
adb push app/scrcpy/build/primecap-video-daemon /data/local/tmp/primecap-video-daemon
adb shell 'chmod 0644 /data/local/tmp/primecap-video-daemon && CLASSPATH=/data/local/tmp/primecap-video-daemon app_process / com.genymobile.scrcpy.Server primecap-daemon'
```

Keep that `adb shell` process running while using PrimeCap. The daemon binds the
local abstract socket `primecap_video_daemon`, accepts one recording session at
a time, and returns to its accept loop after `STOP`. It must be launched directly
by `adb shell`; the APK does not start or change the identity of the daemon.

The build also packages a disposable `primecap-relay` in the APK. For each
recording, PrimeCap stages and launches it with PHH-su, exchanges the protocol
only over the child process's stdin/stdout, and then terminates it. The relay's
stdout contains only bytes copied from the daemon socket; all relay diagnostics
are written to stderr.

For each session the APK sends `START` with max size, bitrate, fps, and
orientation. The daemon responds with the existing framed `FORMAT`, `SAMPLE`,
`END`, and `ERROR` stream. Samples retain their original MediaCodec PTS and
flags. Audio and MP4 muxing remain entirely inside PrimeCap.
