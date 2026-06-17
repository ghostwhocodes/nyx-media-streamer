package com.nyx.config;

public record CompatibilityConfig(QloudCompatibilityConfig qloud) {
    public CompatibilityConfig {
        qloud = qloud == null ? new QloudCompatibilityConfig() : qloud;
    }

    public CompatibilityConfig() {
        this(new QloudCompatibilityConfig());
    }

    public static CompatibilityConfig defaultsFor(String defaultHost, int mainPort) {
        return new CompatibilityConfig(QloudCompatibilityConfig.defaultsFor(defaultHost, mainPort));
    }

    public CompatibilityConfig resolveDefaults(String defaultHost, int mainPort) {
        return new CompatibilityConfig(qloud.resolveDefaults(defaultHost, mainPort));
    }

    public QloudCompatibilityConfig getQloud() {
        return qloud;
    }
}
