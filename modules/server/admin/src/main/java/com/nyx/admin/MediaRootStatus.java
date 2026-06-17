package com.nyx.admin;

public record MediaRootStatus(
    String path,
    boolean exists,
    boolean readable,
    long freeSpaceBytes
) {
}
