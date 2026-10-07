// SPDX-License-Identifier: GPL-3.0-only
#ifndef ROOT_AUDIO_BACKEND_H
#define ROOT_AUDIO_BACKEND_H
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <ctype.h>

// Registry of exact PCM ID tokens; never probe unrelated capture devices.
static const char *const writeback_names[] = { "DL1_AWB_Record" };
static int parse_writeback(const char *line, unsigned *card, unsigned *device) {
    unsigned c, d, captures;
    int consumed = 0;
    if (sscanf(line, "%u-%u: %n", &c, &d, &consumed) != 2 || !consumed) return 0;
    const char *end = strchr(line + consumed, ':');
    if (!end) return 0;
    const char *start = line + consumed;
    while (start < end && isspace((unsigned char)*start)) start++;
    // The PCM ID is the first token. MediaTek appends the DAI name in this
    // field; upstream ALSA prints the human-readable PCM name in a later field.
    // Match only the ID, never a substring or a token in the descriptive name.
    const char *id_end = start;
    while (id_end < end && !isspace((unsigned char)*id_end)) id_end++;
    int named = 0;
    for (unsigned i = 0; i < sizeof(writeback_names)/sizeof(writeback_names[0]); i++)
        if ((size_t)(id_end-start) == strlen(writeback_names[i]) &&
                !strncmp(start, writeback_names[i], id_end-start)) named = 1;
    if (!named) return 0;
    int has_capture = 0;
    for (const char *section = end; section; section = strchr(section + 1, ':')) {
        const char *text = section + 1;
        while (isspace((unsigned char)*text)) text++;
        int count_end = 0;
        if (sscanf(text, "capture %u %n", &captures, &count_end) == 1
                && captures && count_end && (!text[count_end] || text[count_end] == ':')) {
            has_capture = 1;
        }
    }
    if (!has_capture) return 0;
    *card = c; *device = d;
    return 1;
}
static int64_t source_start_ns(int64_t timestamp, uint64_t available,
        unsigned frames, unsigned rate) {
    uint64_t behind = available + frames;
    return timestamp - (int64_t)(behind / rate * 1000000000ULL
            + behind % rate * 1000000000ULL / rate);
}
#endif
