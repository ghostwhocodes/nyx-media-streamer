package com.nyx.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class QloudCompatibilityConfigTest {
    @Test
    void defaultsForPreservesEphemeralMainPort() {
        QloudCompatibilityConfig config = QloudCompatibilityConfig.defaultsFor("127.0.0.1", 0);

        assertFalse(config.enabled());
        assertEquals("127.0.0.1", config.host());
        assertEquals(0, config.port());
    }

    @Test
    void resolveDefaultsPreservesEphemeralMainPortWhenPortIsUnset() {
        QloudCompatibilityConfig config = new QloudCompatibilityConfig(
            true,
            null,
            QloudCompatibilityConfig.DEFAULT_PORT_SENTINEL
        ).resolveDefaults("127.0.0.1", 0);

        assertEquals("127.0.0.1", config.host());
        assertEquals(0, config.port());
    }
}
