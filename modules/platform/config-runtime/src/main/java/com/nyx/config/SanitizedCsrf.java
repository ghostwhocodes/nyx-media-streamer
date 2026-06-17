package com.nyx.config;

public record SanitizedCsrf(
    boolean enabled
) {
    public SanitizedCsrf() {
        this(false);
    }
}
