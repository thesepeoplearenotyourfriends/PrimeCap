package com.openrecorder.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;

public class RecordingRenameTest {
    @Test public void successfulUpdateReceivesValidatedDisplayName() {
        AtomicInteger updates = new AtomicInteger();
        assertTrue(RecordingRename.apply(" New clip.MP4 ", name -> {
            assertEquals("New clip.mp4", name);
            updates.incrementAndGet();
            return 1;
        }));
        assertEquals(1, updates.get());
    }
    @Test public void zeroUpdateAndExceptionsAreFailures() {
        assertFalse(RecordingRename.apply("clip", name -> 0));
        assertFalse(RecordingRename.apply("clip", name -> {
            throw new SecurityException("denied");
        }));
        assertFalse(RecordingRename.apply("clip", name -> {
            throw new IllegalArgumentException("missing item");
        }));
    }
    @Test public void invalidNameNeverReachesMediaStoreUpdate() {
        assertFalse(RecordingRename.apply("../clip", name -> {
            fail("Invalid name must not update MediaStore");
            return 1;
        }));
    }
}
