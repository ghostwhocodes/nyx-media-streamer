package com.nyx.common;

public final class VirtualRootNotFoundException extends PathingException {
    public VirtualRootNotFoundException(String rootName) {
        super("Unknown media root: " + rootName);
    }
}
