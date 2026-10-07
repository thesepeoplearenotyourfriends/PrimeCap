// SPDX-License-Identifier: GPL-3.0-only
// Minimal ALSA kernel PCM capture client. No vendor libraries or executables.
#include "root_audio_backend.h"
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sound/asound.h>
#include <time.h>
#include <unistd.h>

static void die(const char *message) {
    fprintf(stderr, "Root audio: %s: %s\n", message, strerror(errno));
    exit(1);
}
// Independent of the device read and stdout write. Process exit closes the PCM,
// even if either operation is blocked. EOF also covers app death/pipe closure.
static void *control(void *unused) {
    (void)unused;
    char byte;
    while (read(STDIN_FILENO, &byte, 1) < 0 && errno == EINTR) {}
    _exit(0);
}
static void write_all(const void *data, size_t size) {
    const char *cursor = data;
    while (size) {
        ssize_t count = write(STDOUT_FILENO, cursor, size);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) die("protocol write");
        cursor += count; size -= count;
    }
}
// Explicit little-endian wire encoding; no struct padding on the wire.
static void u32(uint32_t v) {
    unsigned char b[4] = {v, v >> 8, v >> 16, v >> 24}; write_all(b, 4);
}
static void u64(uint64_t v) { u32(v); u32(v >> 32); }
static struct snd_interval *interval(struct snd_pcm_hw_params *p, int key) {
    return &p->intervals[key - SNDRV_PCM_HW_PARAM_FIRST_INTERVAL];
}
static void exact(struct snd_pcm_hw_params *p, int key, unsigned value) {
    struct snd_interval *i = interval(p, key);
    *i = (struct snd_interval){ .min = value, .max = value, .integer = 1 };
    p->rmask = ~0U;
}
static void mask(struct snd_pcm_hw_params *p, int key, unsigned value) {
    memset(&p->masks[key], 0, sizeof(p->masks[key]));
    p->masks[key].bits[value / 32] = 1U << (value % 32);
}
static int capabilities(int fd, unsigned rate, struct snd_pcm_hw_params *p) {
    memset(p, 0, sizeof(*p));
    for (unsigned i = 0; i < 3; i++) memset(&p->masks[i], 0xff, sizeof(p->masks[i]));
    for (unsigned i = 0; i < 12; i++) p->intervals[i].max = UINT_MAX;
    p->flags = SNDRV_PCM_HW_PARAMS_NORESAMPLE;
    mask(p, SNDRV_PCM_HW_PARAM_ACCESS, SNDRV_PCM_ACCESS_RW_INTERLEAVED);
    mask(p, SNDRV_PCM_HW_PARAM_FORMAT, SNDRV_PCM_FORMAT_S16_LE);
    mask(p, SNDRV_PCM_HW_PARAM_SUBFORMAT, SNDRV_PCM_SUBFORMAT_STD);
    exact(p, SNDRV_PCM_HW_PARAM_CHANNELS, 2);
    exact(p, SNDRV_PCM_HW_PARAM_RATE, rate);
    return ioctl(fd, SNDRV_PCM_IOCTL_HW_REFINE, p);
}
static unsigned choose(struct snd_interval *i, unsigned preferred) {
    unsigned low = i->min + i->openmin, high = i->max - i->openmax;
    if (i->empty || low > high) { errno = EINVAL; die("empty PCM capability"); }
    return preferred < low ? low : preferred > high ? high : preferred;
}
int main(int argc, char **argv) {
    pthread_t monitor;
    if (pthread_create(&monitor, NULL, control, NULL)) die("control thread");
    signal(SIGPIPE, SIG_DFL);
    unsigned rate = argc == 2 ? (unsigned)strtoul(argv[1], NULL, 10) : 48000;
    if (rate != 44100 && rate != 48000) { errno = EINVAL; die("requested rate"); }
    FILE *pcm_list = fopen("/proc/asound/pcm", "r");
    if (!pcm_list) die("endpoint discovery");
    char line[1024]; unsigned card = 0, device = 0; int found = 0;
    while (fgets(line, sizeof(line), pcm_list)) {
        unsigned c, d;
        if (parse_writeback(line, &c, &d)) {
            if (found) { errno = EINVAL; die("ambiguous writeback endpoint"); }
            card = c; device = d; found = 1;
        }
    }
    fclose(pcm_list);
    if (!found) { errno = ENODEV; die("DL1_AWB_Record capture endpoint absent"); }
    char path[128]; snprintf(path, sizeof(path), "/dev/snd/pcmC%uD%uc", card, device);
    int fd = open(path, O_RDWR | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) die("open named writeback PCM");
    struct snd_pcm_hw_params hw;
    if (capabilities(fd, rate, &hw) < 0) {
        rate = rate == 44100 ? 48000 : 44100;
        if (capabilities(fd, rate, &hw) < 0) die("no supported stereo PCM16 capture rate");
    }
    exact(&hw, SNDRV_PCM_HW_PARAM_PERIOD_SIZE,
            choose(interval(&hw, SNDRV_PCM_HW_PARAM_PERIOD_SIZE), 1024));
    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_REFINE, &hw) < 0) die("period capability");
    exact(&hw, SNDRV_PCM_HW_PARAM_PERIODS,
            choose(interval(&hw, SNDRV_PCM_HW_PARAM_PERIODS), 4));
    if (ioctl(fd, SNDRV_PCM_IOCTL_HW_PARAMS, &hw) < 0) die("configure PCM");
    if (interval(&hw, SNDRV_PCM_HW_PARAM_RATE)->min != rate ||
            interval(&hw, SNDRV_PCM_HW_PARAM_RATE)->max != rate ||
            interval(&hw, SNDRV_PCM_HW_PARAM_CHANNELS)->min != 2)
        { errno = EINVAL; die("unexpected negotiated PCM format"); }
    unsigned buffer = interval(&hw, SNDRV_PCM_HW_PARAM_BUFFER_SIZE)->min;
    if (!buffer) { errno = EINVAL; die("empty PCM buffer"); }
    struct snd_pcm_sw_params sw = {0};
    sw.tstamp_mode = SNDRV_PCM_TSTAMP_ENABLE;
    sw.period_step = 1; sw.avail_min = 1; sw.xfer_align = 1;
    sw.start_threshold = 1; sw.stop_threshold = buffer;
    sw.boundary = buffer;
    while (sw.boundary <= ((unsigned long)LONG_MAX - buffer) / 2) sw.boundary *= 2;
    int monotonic_type = SNDRV_PCM_TSTAMP_TYPE_MONOTONIC;
    int monotonic = ioctl(fd, SNDRV_PCM_IOCTL_TTSTAMP, &monotonic_type) == 0;
    sw.tstamp_type = monotonic ? SNDRV_PCM_TSTAMP_TYPE_MONOTONIC : SNDRV_PCM_TSTAMP_TYPE_GETTIMEOFDAY;
    if (ioctl(fd, SNDRV_PCM_IOCTL_SW_PARAMS, &sw) < 0 ||
            ioctl(fd, SNDRV_PCM_IOCTL_PREPARE) < 0 ||
            ioctl(fd, SNDRV_PCM_IOCTL_START) < 0) die("start PCM");
    fprintf(stderr, "Root audio: %s, %u Hz stereo PCM16; monotonic timestamp %s\n",
            path, rate, monotonic ? "enabled" : "unavailable (explicit frame-clock fallback)");
    // Header: magic, version, rate, channels, bits, owned helper PID.
    u32(0x41524350); u32(1); u32(rate); u32(2); u32(16); u32(getpid());
    int16_t samples[1024 * 2]; int64_t next_ns = 0; int warned = 0;
    for (;;) {
        struct snd_xferi transfer = { .buf = samples, .frames = 1024 };
        int result = ioctl(fd, SNDRV_PCM_IOCTL_READI_FRAMES, &transfer);
        int error = result < 0 ? errno : transfer.result < 0 ? (int)-transfer.result : 0;
        if (error == EAGAIN || error == EINTR || (!error && !transfer.result)) {
            struct pollfd ready = { .fd = fd, .events = POLLIN };
            if (poll(&ready, 1, 250) < 0 && errno != EINTR) die("PCM poll");
            continue;
        }
        // An overrun means frames were lost: fail visibly instead of reusing an invalid epoch.
        if (error) { errno = error; die("PCM capture read"); }
        unsigned frames = transfer.result;
        if (frames > 1024) { errno = EPROTO; die("invalid PCM frame count"); }
        struct snd_pcm_status status = {0};
        int valid_status = ioctl(fd, SNDRV_PCM_IOCTL_STATUS, &status) == 0
                && status.state == SNDRV_PCM_STATE_RUNNING && status.avail <= buffer;
        int hardware = monotonic && valid_status && status.tstamp.tv_sec > 0;
        int64_t start;
        if (hardware) {
            start = source_start_ns((int64_t)status.tstamp.tv_sec * 1000000000LL
                    + status.tstamp.tv_nsec, status.avail, frames, rate);
        } else {
            if (!warned++) fprintf(stderr, "Root audio: ALSA timestamp unavailable; using native monotonic frame-clock fallback\n");
            if (!next_ns) {
                struct timespec now; clock_gettime(CLOCK_MONOTONIC, &now);
                next_ns = source_start_ns((int64_t)now.tv_sec * 1000000000LL + now.tv_nsec, valid_status ? status.avail : 0, frames, rate);
            }
            start = next_ns;
        }
        next_ns = start + (int64_t)frames * 1000000000LL / rate;
        // Block: frames, timestamp quality (1=ALSA, 0=fallback), first-frame CLOCK_MONOTONIC ns.
        u32(frames); u32(hardware ? 1 : 0); u64(start);
        write_all(samples, frames * 4);
    }
}
