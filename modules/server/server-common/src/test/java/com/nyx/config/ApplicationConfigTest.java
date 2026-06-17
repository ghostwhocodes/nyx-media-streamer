package com.nyx.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.typesafe.config.ConfigFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApplicationConfigTest {
    @Test
    void applicationConfigExposesPropertiesListsNestedConfigAndKeys() {
        ApplicationConfig config = new ApplicationConfig(
            ConfigFactory.parseString(
                """
                plain = "value"
                names = ["alice", "bob"]
                tokens = { alpha = "one", beta = "two" }
                nested { port = 8080 }
                entries = [
                  { id = 1, label = "first" },
                  { id = 2, label = "second" }
                ]
                """
            )
        );

        assertEquals("value", config.property("plain").getString());
        assertEquals(List.of("alice", "bob"), config.property("names").getList());
        assertEquals("8080", config.config("nested").property("port").getString());

        List<String> configObjectList = new ArrayList<>(
            new ApplicationProperty(
                ConfigFactory.parseString("tokens { alpha = one, beta = two }").getObject("tokens")
            ).getList()
        );
        Collections.sort(configObjectList);
        assertEquals(List.of("one", "two"), configObjectList);

        List<ApplicationConfig> entries = config.configList("entries");
        assertEquals(2, entries.size());
        assertEquals("1", entries.getFirst().property("id").getString());
        assertEquals("second", entries.getLast().property("label").getString());

        Set<String> keys = config.keys();
        assertTrue(keys.contains("plain"));
        assertTrue(keys.contains("nested.port"));
        assertNotNull(config.propertyOrNull("plain"));
        assertNull(config.propertyOrNull("missing"));
    }

    @Test
    void applicationConfigReportsInvalidPropertyAccessClearly() {
        ApplicationProperty nullProperty = new ApplicationProperty(null);
        IllegalStateException nullError = assertThrows(IllegalStateException.class, nullProperty::getString);
        assertTrue(nullError.getMessage().contains("Config value is null"));

        ApplicationConfig scalarConfig = new ApplicationConfig(ConfigFactory.parseString("value = 1"));
        IllegalStateException listError = assertThrows(
            IllegalStateException.class,
            () -> scalarConfig.property("value").getList()
        );
        assertTrue(listError.getMessage().contains("Config value is not a list"));

        IllegalArgumentException missingResource = assertThrows(
            IllegalArgumentException.class,
            () -> new ApplicationConfig("missing-test-config.conf")
        );
        assertTrue(missingResource.getMessage().contains("Config resource not found"));
    }
}
