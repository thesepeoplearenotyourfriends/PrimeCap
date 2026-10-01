package com.openrecorder.app;

/** Shell-side lifecycle checks for the privileged PrimeCap daemon. */
final class PrimeCapDaemonLifecycle {
    static final int SOCKET_CLEAR_FAILURE = 42;
    private static final String PROCESS_COMMAND =
            "app_process / com.genymobile.scrcpy.Server primecap-daemon ";

    private PrimeCapDaemonLifecycle() {}

    static String cleanupCommand(String pidPath) {
        String quotedPidPath = shellQuote(pidPath);
        StringBuilder command = new StringBuilder(commonFunctions());
        command.append("old=$(cat ").append(quotedPidPath)
                .append(" 2>/dev/null) || old=''; ")
                .append("case \"$old\" in ''|*[!0-9]*) ;; *) ")
                .append("is_primecap \"$old\" && kill \"$old\" 2>/dev/null; esac; ")
                // A persistent daemon from an older version may have no useful pid file.
                .append("for proc in /proc/[0-9]*; do pid=${proc#/proc/}; ")
                .append("is_primecap \"$pid\" && kill \"$pid\" 2>/dev/null; done; ")
                .append("i=0; while socket_inode >/dev/null; do ")
                .append("[ \"$i\" -ge 80 ] && exit ").append(SOCKET_CLEAR_FAILURE).append("; ")
                .append("sleep 0.05; i=$((i+1)); done; ")
                .append("rm -f ").append(quotedPidPath);
        return command.toString();
    }

    static String readinessCommand(String pidPath) {
        return commonFunctions()
                + "pid=$(cat " + shellQuote(pidPath)
                + " 2>/dev/null) || exit 1; case \"$pid\" in ''|*[!0-9]*) exit 1;; esac; "
                + "printf '%s\\n' \"$pid\"; "
                + "kill -0 \"$pid\" 2>/dev/null || exit 2; "
                // The launcher writes its PID immediately before exec, so a brief mismatch
                // is expected. It is never enough to declare the socket ready.
                + "is_primecap \"$pid\" || exit 1; "
                + "inode=$(socket_inode) || exit 1; "
                + "owns_socket \"$pid\" \"$inode\" || exit 1";
    }

    private static String commonFunctions() {
        return "is_primecap() { [ -r /proc/\"$1\"/cmdline ] || return 1; "
                + "[ \"$(tr '\\000' ' ' </proc/\"$1\"/cmdline 2>/dev/null)\" = "
                + shellQuote(PROCESS_COMMAND) + " ]; }; "
                + "socket_inode() { awk '$8 == \"@primecap_video_daemon\" { print $7; found=1; exit } "
                + "END { if (!found) exit 1 }' /proc/net/unix; }; "
                + "owns_socket() { for fd in /proc/\"$1\"/fd/*; do "
                + "[ \"$(readlink \"$fd\" 2>/dev/null)\" = \"socket:[$2]\" ] && return 0; "
                + "done; return 1; }; ";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
