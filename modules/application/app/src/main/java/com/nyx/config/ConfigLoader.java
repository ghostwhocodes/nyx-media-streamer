package com.nyx.config;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static ApplicationConfig loadProfileConfig() {
        String profile = System.getenv("NYX_CONFIG_PROFILE");
        String resolvedProfile = profile == null || profile.isBlank() ? "dev" : profile;
        try {
            return new ApplicationConfig("application-" + resolvedProfile + ".conf");
        } catch (Exception ignored) {
            return new ApplicationConfig("application.conf");
        }
    }
}
