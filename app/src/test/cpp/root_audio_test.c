// SPDX-License-Identifier: GPL-3.0-only
#define main root_helper_main
#include "../../main/cpp/primecap_root_audio.c"
#undef main
#include <assert.h>
#include <sys/wait.h>

static void fixture(const char *path, unsigned expected_card, unsigned expected_device) {
    FILE *file = fopen(path, "r"); assert(file);
    char line[1024]; unsigned card, device; int found = 0;
    while (fgets(line, sizeof(line), file)) {
        if (parse_writeback(line, &card, &device)) {
            assert(card == expected_card); assert(device == expected_device); found++;
        }
    }
    fclose(file); assert(found == 1);
}
static void stop_blocked_reader(int eof) {
    int input[2]; assert(pipe(input) == 0);
    pid_t child = fork(); assert(child >= 0);
    if (!child) {
        close(input[1]); assert(dup2(input[0], STDIN_FILENO) >= 0); close(input[0]);
        pthread_t monitor; assert(!pthread_create(&monitor, NULL, control, NULL));
        // Simulate an indefinitely blocked PCM read, independently of the control thread.
        for (;;) pause();
    }
    close(input[0]);
    if (!eof) assert(write(input[1], "S", 1) == 1);
    close(input[1]);
    int status;
    for (unsigned i = 0; i < 100; i++) {
        if (waitpid(child, &status, WNOHANG) == child) {
            assert(WIFEXITED(status) && WEXITSTATUS(status) == 0); return;
        }
        usleep(10000);
    }
    kill(child, SIGKILL); waitpid(child, &status, 0);
    assert(!"control did not terminate blocked reader within one second");
}
int main(void) {
    fixture("app/src/test/cpp/fixtures/armor-pcm.txt", 0, 9);
    fixture("app/src/test/cpp/fixtures/shifted-pcm.txt", 2, 17);
    unsigned c, d;
    assert(!parse_writeback("00-09: DL1_AWB_Record : playback 1", &c, &d));
    assert(!parse_writeback("00-09: DL1_AWB_Record : capture 0", &c, &d));
    assert(!parse_writeback("00-09: DL1_AWB_Record_extra : capture 1", &c, &d));
    assert(!parse_writeback("00-09: UL1_Record : capture 1", &c, &d));
    assert(!parse_writeback("invalid", &c, &d));
    assert(!parse_writeback("00-09: DL1_AWB_Record : nocapture 1", &c, &d));
    assert(!parse_writeback("00-09: DL1_AWB_Record : capture 1garbage", &c, &d));
    assert(source_start_ns(1000000000LL, 480, 480, 48000) == 980000000LL);
    assert(source_start_ns(1000000000LL, 0, 441, 44100) == 990000000LL);
    assert(source_start_ns(1000000000LL, 0, 1024, 48000) == 978666667LL);
    stop_blocked_reader(0); stop_blocked_reader(1);
    puts("Native discovery, timestamp and blocked-reader shutdown tests passed");
}
