package com.nyx.common;

public final class PathNotFoundException extends PathingException {
    public PathNotFoundException(String path) {
        super("File not found: " + path);
    }
}
