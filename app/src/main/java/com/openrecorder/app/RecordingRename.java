package com.openrecorder.app;

/** Rename outcome policy around the repository's MediaStore update. */
final class RecordingRename {
    interface Update { int displayName(String name); }

    static boolean apply(String basename, Update update) {
        String displayName = RecordingName.displayName(basename);
        if (displayName == null) return false;
        try {
            return update.displayName(displayName) > 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private RecordingRename() {}
}
