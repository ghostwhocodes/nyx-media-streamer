package com.nyx.config;

public record SanitizedTls(
    boolean enabled,
    int port,
    boolean hasKeystore
) {
    public SanitizedTls() {
        this(false, 8443, false);
    }
}
