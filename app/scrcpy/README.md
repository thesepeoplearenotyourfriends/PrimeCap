# Primecap scrcpy MP4 recorder

This directory contains the exact scrcpy 3.3.4 archive and a small server-only
patch for recording display video and internal playback audio directly to one
MP4 file on the Android device. The build
script extracts and patches the archive, then delegates compilation to
upstream's `server/build_without_gradle.sh`:

```sh
./build.sh
```

The result is `build/scrcpy-server`. After copying that file to the device as
`/data/local/tmp/scrcpy-server`, start an H.264/AAC MP4 recording with:

```sh
CLASSPATH=/data/local/tmp/scrcpy-server app_process / com.genymobile.scrcpy.Server 3.3.4 record=/sdcard/Movies/test.mp4
```

Stop the process (for example with `Ctrl+C`) to finish recording. The
`record` option requires an absolute path, selects display capture with H.264
video and AAC internal playback audio through Android playback capture (including
Android 10), and muxes both tracks in-process with Android `MediaMuxer`. It
does not open a scrcpy desktop socket or write
temporary elementary streams. Without `record`, upstream server behavior is
unchanged.
