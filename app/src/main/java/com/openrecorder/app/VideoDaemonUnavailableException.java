package com.openrecorder.app;

import java.io.IOException;

/** Indicates that the shell-context video daemon could not be started or reached. */
final class VideoDaemonUnavailableException extends IOException {
    VideoDaemonUnavailableException(String message) {
        super(message);
    }

    VideoDaemonUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
