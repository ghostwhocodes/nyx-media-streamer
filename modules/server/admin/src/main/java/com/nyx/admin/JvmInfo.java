package com.nyx.admin;

public record JvmInfo(
    String version,
    long maxMemoryBytes,
    int availableProcessors
) {
}
