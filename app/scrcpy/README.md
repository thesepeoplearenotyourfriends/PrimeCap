# PrimeCap scrcpy helper

This directory vendors the unmodified scrcpy 3.3.4 release archive plus a small,
reviewable helper-mode overlay. `build.sh` extracts the archive, applies
`primecap-helper.patch`, copies the new helper sources, and invokes upstream's
`server/build_without_gradle.sh` with Android platform/build-tools 35.

PrimeCap uses root only to stage the helper in `/data/local/tmp`. The helper then
drops to Android's shell UID before initializing the Android framework or encoder:

```
CLASSPATH=/data/local/tmp/primecap-server app_process / com.genymobile.scrcpy.Server \
  primecap <abstract-socket-name> <max-size> <bitrate> <max-fps>
```

This branch opens no desktop connection and constructs no scrcpy audio,
control, recorder, or muxer objects. It sends H.264 codec configuration and
encoded samples (including their original MediaCodec PTS and flags) over the
private framed local-socket protocol. OpenRecorder remains the sole MP4 muxer.
