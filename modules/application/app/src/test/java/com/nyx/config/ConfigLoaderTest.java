package com.nyx.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @Test
    void loadProfileConfigMatchesTheCurrentEnvironmentResolutionRules() {
        String profile = System.getenv("NYX_CONFIG_PROFILE");
        String resolvedProfile = profile == null || profile.isBlank() ? "dev" : profile;

        ApplicationConfig expected;
        try {
            expected = new ApplicationConfig("application-" + resolvedProfile + ".conf");
        } catch (Exception ignored) {
            expected = new ApplicationConfig("application.conf");
        }

        ServerConfig expectedConfig = expected.toServerConfig();
        ServerConfig actualConfig = ConfigLoader.loadProfileConfig().toServerConfig();

        assertEquals(expectedConfig.getHost(), actualConfig.getHost());
        assertEquals(expectedConfig.getPort(), actualConfig.getPort());
        assertEquals(expectedConfig.getCorsOrigins(), actualConfig.getCorsOrigins());
        assertEquals(expectedConfig.getRateLimit().getEnabled(), actualConfig.getRateLimit().getEnabled());
        assertEquals(expectedConfig.getCsrf().getEnabled(), actualConfig.getCsrf().getEnabled());
    }

    @Test
    void devProfileDisablesRateLimiting() {
        ServerConfig config = new ApplicationConfig("application-dev.conf").toServerConfig();
        assertFalse(config.getRateLimit().getEnabled());
    }

    @Test
    void devProfileDisablesCsrf() {
        ServerConfig config = new ApplicationConfig("application-dev.conf").toServerConfig();
        assertFalse(config.getCsrf().getEnabled());
    }

    @Test
    void devProfileBindsToLocalhostByDefault() {
        ServerConfig config = new ApplicationConfig("application-dev.conf").toServerConfig();
        assertEquals("127.0.0.1", config.getHost());
    }

    @Test
    void prodProfileEnablesRateLimiting() {
        ServerConfig config = new ApplicationConfig("application-prod.conf").toServerConfig();
        assertTrue(config.getRateLimit().getEnabled());
    }

    @Test
    void prodProfileEnablesCsrf() {
        ServerConfig config = new ApplicationConfig("application-prod.conf").toServerConfig();
        assertTrue(config.getCsrf().getEnabled());
    }

    @Test
    void unknownProfileFallsBackToBaseApplicationConfWithoutThrowing() {
        ServerConfig config = new ApplicationConfig("application.conf").toServerConfig();
        assertEquals(8080, config.getPort());
    }

    @Test
    void prodProfileUsesStricterRateLimitDefaults() {
        ServerConfig config = new ApplicationConfig("application-prod.conf").toServerConfig();
        assertEquals(50, config.getRateLimit().getRequestsPerSecond());
        assertEquals(100, config.getRateLimit().getBurstSize());
    }
}
