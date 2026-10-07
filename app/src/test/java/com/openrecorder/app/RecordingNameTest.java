package com.openrecorder.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordingNameTest {
    @Test public void editsBasenameAndKeepsMp4ExactlyOnce() {
        assertEquals("Clip", RecordingName.basename("Clip.MP4"));
        assertEquals("New clip.mp4", RecordingName.displayName(" New clip "));
        assertEquals("New clip.mp4", RecordingName.displayName("New clip.mp4"));
        assertEquals("Clip.v2.mp4", RecordingName.displayName("Clip.v2"));
        assertEquals("旅行.mp4", RecordingName.displayName("旅行"));
    }
    @Test public void rejectsEmptyPathsAndInvalidNames() {
        for (String name : new String[]{"", "   ", ".mp4", ".", "..", "../clip",
                "folder\\clip", "a\u0000b", "a\nb", "a:b", "a*b", "a?b", "a\"b",
                "a<b", "a>b", "a|b", "a".repeat(252), "旅".repeat(84)}) {
            assertNull(name, RecordingName.displayName(name));
        }
        assertNotNull(RecordingName.displayName("a".repeat(251)));
    }
}
