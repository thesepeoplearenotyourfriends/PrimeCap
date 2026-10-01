package com.openrecorder.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrimeCapDaemonLifecycleTest {
    @Test
    public void stalePidIsValidatedAndTerminatedBeforePidFileRemoval() {
        String command = PrimeCapDaemonLifecycle.staleCleanupCommand("/tmp/daemon.pid");
        int validate = command.indexOf("is_primecap \"$old\"");
        int terminate = command.indexOf("for pid in $targets; do kill");
        int removePid = command.lastIndexOf("rm -f");
        assertTrue(validate >= 0 && validate < terminate);
        assertTrue(terminate < removePid);
        assertFalse(command.startsWith("rm -f"));
    }

    @Test
    public void bindFailureMigrationScansOnlyExactPrimeCapCommand() {
        String command = PrimeCapDaemonLifecycle.staleCleanupCommand("/tmp/daemon.pid");
        assertTrue(command.contains("for proc in /proc/[0-9]*"));
        assertTrue(command.contains(
                "app_process / com.genymobile.scrcpy.Server primecap-daemon "));
        assertFalse(command.contains("killall"));
        assertFalse(command.contains("/proc/net/unix"));
        assertFalse(command.contains("/fd/"));
    }
}
