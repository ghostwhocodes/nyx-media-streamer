package com.nyx.admin;

public record StorageInfo(
    String path,
    long totalBytes,
    long freeBytes,
    long usableBytes
) {
}
