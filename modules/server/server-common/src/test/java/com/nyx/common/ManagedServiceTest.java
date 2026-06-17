package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ManagedServiceTest {
    @Test
    void defaultStartIsANoOpAndShutdownStaysOverridable() {
        final boolean[] started = {false};
        final boolean[] shutDown = {false};

        ManagedService service = new ManagedService() {
            @Override
            public void start() {
                started[0] = true;
                ManagedService.super.start();
            }

            @Override
            public void shutdown() {
                shutDown[0] = true;
            }
        };

        assertFalse(shutDown[0]);
        service.start();
        service.shutdown();

        assertTrue(started[0]);
        assertTrue(shutDown[0]);
    }
}
