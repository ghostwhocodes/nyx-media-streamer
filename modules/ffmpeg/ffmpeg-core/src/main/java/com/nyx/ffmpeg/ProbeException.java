package com.nyx.ffmpeg;

public final class ProbeException extends Exception {
    public ProbeException(String message) {
        super(message);
    }

    public ProbeException(String message, Throwable cause) {
        super(message, cause);
    }
}
