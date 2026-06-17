package com.nyx.config;

public record CsrfConfig(boolean enabled) {
    public CsrfConfig() {
        this(false);
    }

    public boolean getEnabled() {
        return enabled;
    }
}
