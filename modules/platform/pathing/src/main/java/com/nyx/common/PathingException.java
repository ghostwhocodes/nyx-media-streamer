package com.nyx.common;

public class PathingException extends RuntimeException {
    public PathingException(String message) {
        super(message);
    }

    public PathingException(String message, Throwable cause) {
        super(message, cause);
    }
}
