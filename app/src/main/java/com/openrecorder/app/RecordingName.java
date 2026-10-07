package com.openrecorder.app;

import java.nio.charset.StandardCharsets;

/** Validates a MediaStore display name without accepting directory components. */
final class RecordingName {
    static String basename(String displayName) {
        return displayName.regionMatches(true, Math.max(0, displayName.length() - 4),
                ".mp4", 0, 4) ? displayName.substring(0, displayName.length() - 4) : displayName;
    }

    static String displayName(String input) {
        String name = basename(input.trim()).trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || "/\\:*?\"<>|".indexOf(c) >= 0) return null;
        }
        String result = name + ".mp4";
        return result.getBytes(StandardCharsets.UTF_8).length <= 255 ? result : null;
    }

    private RecordingName() {}
}
