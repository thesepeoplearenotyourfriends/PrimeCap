#include <jni.h>

#include <dlfcn.h>
#include <errno.h>
#include <grp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

typedef int (*setcon_fn)(const char *);

static void throw_io_exception(JNIEnv *env, const char *operation) {
    char message[256];
    snprintf(message, sizeof(message), "%s failed: %s", operation, strerror(errno));
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) {
        (*env)->ThrowNew(env, type, message);
    }
}

JNIEXPORT void JNICALL
Java_com_genymobile_scrcpy_PrimeCapLauncher_launch(JNIEnv *env, jclass type,
        jstring daemon_path, jstring pid_path) {
    (void) type;
    const char *daemon = (*env)->GetStringUTFChars(env, daemon_path, NULL);
    const char *pid_file = (*env)->GetStringUTFChars(env, pid_path, NULL);
    if (daemon == NULL || pid_file == NULL) {
        return;
    }

    /* Resolve setcon while still root in phhsu_daemon, as in the proven prototype. */
    void *selinux = dlopen("libselinux.so", RTLD_NOW | RTLD_LOCAL);
    if (selinux == NULL) {
        errno = ENOSYS;
        throw_io_exception(env, "loading libselinux");
        goto done;
    }
    setcon_fn setcon = (setcon_fn) dlsym(selinux, "setcon");
    if (setcon == NULL) {
        errno = ENOSYS;
        throw_io_exception(env, "resolving setcon");
        dlclose(selinux);
        goto done;
    }

    /* These are the supplementary groups of the physically verified adb shell. */
    const gid_t groups[] = {1004, 1007, 1011, 1015, 1028, 3001,
                            3002, 3003, 3006, 3009, 3011};
    if (setgroups(sizeof(groups) / sizeof(groups[0]), groups) != 0) {
        throw_io_exception(env, "setgroups");
        goto done;
    }
    if (setgid(2000) != 0) {
        throw_io_exception(env, "setgid");
        goto done;
    }
    if (setuid(2000) != 0) {
        throw_io_exception(env, "setuid");
        goto done;
    }

    if (setcon("u:r:shell:s0") != 0) {
        throw_io_exception(env, "setcon");
        dlclose(selinux);
        goto done;
    }

    FILE *pid_output = fopen(pid_file, "w");
    if (pid_output == NULL || fprintf(pid_output, "%d\n", getpid()) < 0
            || fclose(pid_output) != 0) {
        throw_io_exception(env, "writing daemon pid");
        goto done;
    }

    setenv("CLASSPATH", daemon, 1);
    char *const argv[] = {"app_process", "/", "com.genymobile.scrcpy.Server",
                          "primecap-daemon", NULL};
    execv("/system/bin/app_process", argv);
    throw_io_exception(env, "exec app_process");
    dlclose(selinux);

done:
    (*env)->ReleaseStringUTFChars(env, daemon_path, daemon);
    (*env)->ReleaseStringUTFChars(env, pid_path, pid_file);
}
