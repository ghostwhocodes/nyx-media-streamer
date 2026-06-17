package com.nyx.common;

public final class PathNotAllowedException extends PathingException {
    public PathNotAllowedException(String path) {
        super("Path not allowed: " + path);
    }
}
