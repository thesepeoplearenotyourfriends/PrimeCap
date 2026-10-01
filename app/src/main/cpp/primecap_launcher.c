#include <dlfcn.h>
#include <errno.h>
#include <grp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

typedef int (*setcon_fn)(const char *);

static int report_error(const char *operation) {
    fprintf(stderr, "primecap-launcher: %s failed: %s\n", operation, strerror(errno));
    return EXIT_FAILURE;
}

int main(int argc, char *argv[]) {
    if (argc != 3) {
        fprintf(stderr, "Usage: %s DAEMON_PATH PID_PATH\n", argv[0]);
        return EXIT_FAILURE;
    }
    const char *daemon = argv[1];
    const char *pid_file = argv[2];

    /* Resolve setcon while still root in phhsu_daemon, as in the proven prototype. */
    void *selinux = dlopen("libselinux.so", RTLD_NOW | RTLD_LOCAL);
    if (selinux == NULL) {
        errno = ENOSYS;
        return report_error("loading libselinux");
    }
    setcon_fn setcon = (setcon_fn) dlsym(selinux, "setcon");
    if (setcon == NULL) {
        errno = ENOSYS;
        report_error("resolving setcon");
        dlclose(selinux);
        return EXIT_FAILURE;
    }

    /* These are the supplementary groups of the physically verified adb shell. */
    const gid_t groups[] = {1004, 1007, 1011, 1015, 1028, 3001,
                            3002, 3003, 3006, 3009, 3011};
    if (setgroups(sizeof(groups) / sizeof(groups[0]), groups) != 0) {
        return report_error("setgroups");
    }
    if (setgid(2000) != 0) {
        return report_error("setgid");
    }
    if (setuid(2000) != 0) {
        return report_error("setuid");
    }

    if (setcon("u:r:shell:s0") != 0) {
        report_error("setcon");
        dlclose(selinux);
        return EXIT_FAILURE;
    }

    FILE *pid_output = fopen(pid_file, "w");
    if (pid_output == NULL || fprintf(pid_output, "%d\n", getpid()) < 0
            || fclose(pid_output) != 0) {
        return report_error("writing daemon pid");
    }

    setenv("CLASSPATH", daemon, 1);
    char *const app_process_argv[] = {"app_process", "/", "com.genymobile.scrcpy.Server",
                                      "primecap-daemon", NULL};
    printf("PrimeCap launcher exec (pid=%d)\n", getpid());
    fflush(stdout);
    execv("/system/bin/app_process", app_process_argv);
    report_error("exec app_process");
    dlclose(selinux);
    return EXIT_FAILURE;
}
