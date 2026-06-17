package com.nyx.common;

public final class PathNotDirectoryException extends PathingException {
    public PathNotDirectoryException(String path) {
        super("Not a directory: " + path);
    }
}
