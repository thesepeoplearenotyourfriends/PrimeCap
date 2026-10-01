package com.openrecorder.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PrimeCapDaemonLifecycleTest {
    @Test
    public void stalePidIsValidatedAndTerminatedBeforePidFileRemoval() {
        String command = PrimeCapDaemonLifecycle.cleanupCommand("/tmp/daemon.pid");
        int validate = command.indexOf("is_primecap \"$old\"");
        int terminate = command.indexOf("kill \"$old\"");
        int removePid = command.lastIndexOf("rm -f");
        assertTrue(validate >= 0 && validate < terminate);
        assertTrue(terminate < removePid);
        assertFalse(command.startsWith("rm -f"));
    }

    @Test
    public void missingPidMigrationScansOnlyExactPrimeCapCommand() {
        String command = PrimeCapDaemonLifecycle.cleanupCommand("/tmp/daemon.pid");
        assertTrue(command.contains("for proc in /proc/[0-9]*"));
        assertTrue(command.contains(
                "app_process / com.genymobile.scrcpy.Server primecap-daemon "));
        assertFalse(command.contains("killall"));
        assertTrue(command.contains("exit " + PrimeCapDaemonLifecycle.SOCKET_CLEAR_FAILURE));
    }

    @Test
    public void readinessRequiresPublishedPidToOwnNamedSocket() {
        String command = PrimeCapDaemonLifecycle.readinessCommand("/tmp/daemon.pid", 12_000L);
        assertTrue(command.contains("while [ \"$i\" -lt 240 ]"));
        assertTrue(command.contains("kill -0 \"$pid\" 2>/dev/null || exit 2"));
        assertTrue(command.contains("if is_primecap \"$pid\""));
        assertTrue(command.contains("inode=$(socket_inode)"));
        assertTrue(command.contains("owns_socket \"$pid\" \"$inode\""));
        assertTrue(command.endsWith("exit " + PrimeCapDaemonLifecycle.READINESS_TIMED_OUT));
        assertFalse(command.endsWith("grep -q ' @primecap_video_daemon$' /proc/net/unix"));
    }
}
