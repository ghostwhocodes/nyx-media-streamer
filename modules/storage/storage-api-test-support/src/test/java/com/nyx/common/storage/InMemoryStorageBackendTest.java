package com.nyx.common.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryStorageBackendTest {
    @Test
    void storesReadsAndDeletesObjects() {
        InMemoryStorageBackend backend = new InMemoryStorageBackend();
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);

        backend.write("covers/one.jpg", payload, "image/jpeg", Map.of("kind", "poster"));

        assertThat(backend.exists("covers/one.jpg")).isTrue();
        assertThat(backend.read("covers/one.jpg")).containsExactly(payload);
        assertThat(backend.metadata("covers/one.jpg"))
            .satisfies(metadata -> {
                assertThat(metadata.sizeBytes()).isEqualTo(payload.length);
                assertThat(metadata.contentType()).isEqualTo("image/jpeg");
                assertThat(metadata.userMetadata()).containsEntry("kind", "poster");
            });
        assertThat(backend.list("covers")).isEqualTo(List.of("covers/one.jpg"));
        assertThat(backend.totalSize("covers")).isEqualTo(payload.length);

        assertThat(backend.delete("covers/one.jpg")).isTrue();
        assertThat(backend.exists("covers/one.jpg")).isFalse();
        assertThat(backend.read("covers/one.jpg")).isNull();
    }

    @Test
    void deletesPrefixes() {
        InMemoryStorageBackend backend = new InMemoryStorageBackend();

        backend.write("art/one", new byte[] {1}, null, Map.of());
        backend.write("art/two", new byte[] {2}, null, Map.of());
        backend.write("other", new byte[] {3}, null, Map.of());

        assertThat(backend.deletePrefix("art/")).isEqualTo(2);
        assertThat(backend.exists("other")).isTrue();
        assertThat(backend.exists("art/one")).isFalse();
        assertThat(backend.exists("art/two")).isFalse();
    }

    @Test
    void exposesStoredObjectAccessorsAndGracefulMissingObjectBehavior() {
        InMemoryStorageBackend backend = new InMemoryStorageBackend();
        InMemoryStorageBackend.StoredObject storedObject = new InMemoryStorageBackend.StoredObject(
            new byte[] {4, 5, 6},
            "application/octet-stream",
            Map.of("source", "fixture")
        );

        assertThat(storedObject.getData()).containsExactly(4, 5, 6);
        assertThat(storedObject.getContentType()).isEqualTo("application/octet-stream");
        assertThat(storedObject.getMetadata()).containsEntry("source", "fixture");
        assertThat(storedObject.getCreatedAtMillis()).isPositive();

        assertThat(backend.getObjects()).isEmpty();
        assertThat(backend.metadata("missing")).isNull();
        assertThat(backend.delete("missing")).isFalse();

        backend.close();
    }
}
