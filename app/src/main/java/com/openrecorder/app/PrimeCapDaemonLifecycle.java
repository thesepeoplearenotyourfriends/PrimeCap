package com.openrecorder.app;

/** Exceptional migration cleanup for PrimeCap daemons left by older releases. */
final class PrimeCapDaemonLifecycle {
    private static final String PROCESS_COMMAND =
            "app_process / com.genymobile.scrcpy.Server primecap-daemon ";

    private PrimeCapDaemonLifecycle() {}

    static String staleCleanupCommand(String pidPath) {
        String quotedPidPath = shellQuote(pidPath);
        StringBuilder command = new StringBuilder(isPrimeCapFunction());
        command.append("targets=''; old=$(cat ").append(quotedPidPath)
                .append(" 2>/dev/null) || old=''; ")
                .append("case \"$old\" in ''|*[!0-9]*) ;; *) ")
                .append("is_primecap \"$old\" && targets=\"$targets $old\"; esac; ")
                // This migration scan is only run after bind reports an occupied socket.
                .append("for proc in /proc/[0-9]*; do pid=${proc#/proc/}; ")
                .append("is_primecap \"$pid\" && targets=\"$targets $pid\"; done; ")
                .append("for pid in $targets; do kill \"$pid\" 2>/dev/null; done; ")
                .append("for pid in $targets; do i=0; while kill -0 \"$pid\" 2>/dev/null; do ")
                .append("[ \"$i\" -ge 40 ] && break; sleep 0.05; i=$((i+1)); done; done; ")
                .append("rm -f ").append(quotedPidPath);
        return command.toString();
    }

    private static String isPrimeCapFunction() {
        return "is_primecap() { [ -r /proc/\"$1\"/cmdline ] || return 1; "
                + "[ \"$(tr '\\000' ' ' </proc/\"$1\"/cmdline 2>/dev/null)\" = "
                + shellQuote(PROCESS_COMMAND) + " ]; }; ";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
