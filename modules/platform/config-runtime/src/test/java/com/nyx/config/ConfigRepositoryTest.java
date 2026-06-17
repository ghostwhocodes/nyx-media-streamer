package com.nyx.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.nyx.common.DatabaseResources;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigRepositoryTest {
    private Path tempDir;
    private DatabaseResources resources;
    private ConfigRepository repository;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-config-repository");
        resources = ConfigRepository.createDatabase(tempDir);
        repository = new ConfigRepository(resources.getJdbi());
    }

    @AfterEach
    void teardown() throws Exception {
        resources.close();
        Files.walk(tempDir)
            .sorted((left, right) -> right.compareTo(left))
            .forEach(path -> path.toFile().delete());
    }

    @Test
    void overrideCrudIsPersisted() {
        repository.setOverride("corsOrigins", "https://example.com");

        assertThat(repository.getOverrides())
            .containsEntry("corsOrigins", "https://example.com");

        repository.setOverride("corsOrigins", "https://example.org");

        assertThat(repository.getOverrides())
            .containsEntry("corsOrigins", "https://example.org");
    }

    @Test
    void userCrudIsPersisted() {
        repository.upsertUser("alice", "hash-1");
        repository.upsertUser("bob", "hash-2");

        assertThat(repository.getAllUsers())
            .containsEntry("alice", "hash-1")
            .containsEntry("bob", "hash-2");

        repository.upsertUser("alice", "hash-3");
        assertThat(repository.getAllUsers())
            .containsEntry("alice", "hash-3");

        assertThat(repository.deleteUser("bob")).isTrue();
        assertThat(repository.deleteUser("missing")).isFalse();
        assertThat(repository.getAllUsers()).containsOnlyKeys("alice");
    }
}
