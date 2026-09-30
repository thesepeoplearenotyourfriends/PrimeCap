package com.openrecorder.app;

import java.io.IOException;

/** Indicates that the manually started shell-context video daemon cannot be reached. */
final class VideoDaemonUnavailableException extends IOException {
    VideoDaemonUnavailableException(String message) {
        super(message);
    }

    VideoDaemonUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
