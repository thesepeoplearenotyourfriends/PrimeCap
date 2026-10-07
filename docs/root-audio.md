# Root playback capture

Settings > Options > **Use root audio capture** is persisted and defaults off.
It replaces only the playback portion of Internal audio / Microphone and internal
audio. Microphone-only recordings and the switch-off AudioPlaybackCapture path
retain their previous implementation. Both the main activity and Quick Settings
tile skip RECORD_AUDIO for root playback alone; microphone selection still requires
it. Video still uses the existing MediaProjection authorization flow.

## Discovery and PCM configuration

The bundled `primecap-root-audio` arm64 executable uses the public ALSA kernel
ioctl ABI directly (`sound/asound.h`). It links only against public Android system libraries (libc/libm/libdl), with no
TinyALSA, vendor-library, device executable, or AudioRecord playback dependency.
It parses `/proc/asound/pcm` for the exact first PCM ID token `DL1_AWB_Record`, then
constructs the capture-node path from that entry's card and device. Unrelated
capture nodes are never opened. MediaTek may append a DAI description after the
ID in the first field (the Armor line is `00-09: DL1_AWB_Record
mt-soc-codec-dl1awb-dai-9 :  : capture 1`); conventional Linux prints the PCM name
in a separate field. Descriptive fields are not used to identify the backend. Missing or ambiguous named endpoints fail clearly.
The name registry in `root_audio_backend.h` is the extension point for future
named writeback backends; adding a name requires confirming its format and timing.

The helper HW_REFINEs interleaved stereo S16_LE at the selected 44100/48000 Hz rate.
If unsupported, it tries the other rate. It refines period-size/count constraints
before HW_PARAMS and checks the resulting rate/channel configuration. Failure to
negotiate either rate is an explicit backend error. The acknowledged actual rate
configures both the mono AAC encoder and, when selected, the existing microphone
AudioRecord. Java averages each stereo pair in a 32-bit accumulator before passing
mono samples to the existing gain/clipping mixer and encoder.

## IPC and source timing

Each session stages the asset via `su` in a unique, root-owned 0700 directory
under `/data/local/tmp`, then execs it. Stdout carries only versioned binary data;
stderr carries diagnostics and is drained independently into Logcat. Stdin is a
lifetime/control pipe; no recording duration is passed to the helper.

All integers and samples are little endian. The 24-byte header contains six u32
fields: magic `0x41524350` (bytes `PCRA`), version `1`, actual rate, channels `2`,
bits `16`, and helper PID. Each block contains u32 frame count (1–1024), u32 timing
quality (1 = ALSA monotonic timestamp, 0 = explicit fallback), i64 first-frame
monotonic timestamp in nanoseconds, and `frames * 4` stereo PCM16 bytes. There is
no WAV header. EOF or malformed/truncated data fails the source. Java bounds and
validates block sizes before allocation; an active consumer queue overrun is an
error, not silently dropped recording data. The pump discards preparation samples
until the audio recorder starts and continues draining throughout pauses.

The helper requests `SNDRV_PCM_TSTAMP_TYPE_MONOTONIC` (the kernel mechanism behind
TinyALSA's PCM_MONOTONIC), enables timestamps in SW_PARAMS, and queries PCM STATUS
for its paired timestamp and capture availability after each read. This provides
the same timestamp/availability information used by `pcm_get_htimestamp` without
requiring that library. A block starts at:

```
status.timestamp - (status.available_frames + returned_frames) / actual_rate
```

The availability is measured **after** consuming the returned frames. STATUS
updates the hardware pointer; timestamp precision still depends on the kernel and
driver. These are ALSA timestamps associated with frame availability, not a claim
that this driver implements ALSA's optional high-precision link timestamps.
If monotonic timestamps are unavailable/invalid, both helper stderr and Java
Logcat explicitly identify the native CLOCK_MONOTONIC frame-clock fallback. Its
initial anchor accounts for availability when STATUS is usable, then advances
with the returned source frames. Java never substitutes IPC arrival time.

`InternalAudioRecorder` passes the block's source start through its existing
`AudioFrameClock` frame conversions, `RecordingTimeline` range clipping and pause
removal, monotonic AAC PTS, track registration, and RecordingMuxer. Root playback
and the microphone remain open and are drained during pause; the timeline excludes
paused samples, including buffers crossing pause/resume boundaries. The native
reader does not reopen/reconfigure on resume. ALSA overruns fail the source rather
than hiding lost frames under an invalid timestamp epoch.

## Ownership and stopping

A separate native control thread exits the entire helper on any stdin byte or EOF.
It does not need the capture loop or stdout writer to make progress. Process exit
closes the PCM descriptor even during a blocked read/write. Closing the pipe also
covers app death. Java closes stdin, waits at most 500 ms for the su process, then
forcibly destroys that process if necessary. A second, bounded (500 ms) root
cleanup verifies `/proc/<acknowledged PID>/exe` against this session's unique
helper path before SIGKILL; it removes only this session's staged executable and
directory. The existing audio worker join is bounded at five seconds, and codec
finalization retains its existing bounded polling. Stop never performs an
unbounded wait for PCM I/O or a root process. Native exit/reaping ultimately depends
on the kernel servicing process termination, but the app's waits remain bounded.

Startup has a ten-second deadline, including root authorization/staging/header
negotiation. Backend failures use the existing audio-failure notification and
video-without-audio behavior. Root playback never falls back to AudioPlaybackCapture.

## Validation and portability

Run JVM unit tests and build the development APK:

```
./gradlew :app:testDebugUnitTest :app:assembleDevelopment
```

Run native discovery/timing/control tests on a Linux host with a C compiler:

```
app/src/test/cpp/run.sh
```

Fixtures include the actual Armor line (card 0/device 9), a shifted MediaTek layout
(card 2/device 17), and conventional Linux ID/name fields (card 3/device 12), plus
ID-prefix, description-only matches, and capture-only rejection cases. The host test
checks timestamp/availability math and exits simulated blocked readers on both
control-byte and EOF shutdown. JVM tests check format/version validation, stereo
downmix overflow, source timestamp preservation, explicit fallback quality, and
truncated/oversized packets, and the permission matrix for every audio source. Host tests cannot verify this device's ALSA driver,
root policy, actual playback routing, or physical A/V synchronization.

Initial device support is MediaTek DL1_AWB_Record with stereo PCM16 and 44100 or
48000 Hz; the APK currently packages only arm64-v8a, as before. Merely having root
on another device is insufficient. SELinux/root policy can still deny access.
No mixer routing controls or HAL changes are made: the kernel endpoint must already
supply the playback mix. Hardware timestamp accuracy and support vary by kernel.

Before release, verify on the rooted Armor X5 Pro: switch-off playback regression;
root playback with the movie app; microphone-only and microphone + root playback;
selected 44100 vs actual negotiated rate; several pause/resume boundaries and A/V
sync; Stop during silence/blocked capture; root denied/absent endpoint; and app
termination leaving no helper. Check Logcat for actual rate and timing quality.
