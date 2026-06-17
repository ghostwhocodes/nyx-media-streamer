package com.nyx.common.storage;

import com.nyx.config.S3Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class S3StorageBackendDeepTest {
    private static final String BUCKET = "nyx-test";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";

    private MinIOContainer minio;
    private S3StorageBackend backend;

    @BeforeEach
    void setUp() {
        MinIOContainer container;
        try {
            container = new MinIOContainer("minio/minio:latest")
                .withUserName(ACCESS_KEY)
                .withPassword(SECRET_KEY);
            container.start();
        } catch (Exception exception) {
            assumeTrue(false, "Docker/MinIO not available: " + exception.getMessage());
            return;
        }
        minio = container;

        S3Client s3Admin = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(container.getS3URL()))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)
            ))
            .forcePathStyle(true)
            .build();
        s3Admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        s3Admin.close();

        backend = new S3StorageBackend(new S3Config(
            BUCKET,
            container.getS3URL(),
            "us-east-1",
            ACCESS_KEY,
            SECRET_KEY,
            "test",
            true
        ));
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.close();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    @Test
    void writeAndReadReturnsSameBytes() {
        if (backend == null) {
            return;
        }
        byte[] data = "hello s3".getBytes();
        backend.write("test/file.txt", data, "text/plain");

        assertThat(backend.read("test/file.txt")).containsExactly(data);
    }

    @Test
    void readReturnsNullForNonExistentKey() {
        if (backend == null) {
            return;
        }
        assertThat(backend.read("nonexistent/key")).isNull();
    }

    @Test
    void existsReturnsTrueForExistingKey() {
        if (backend == null) {
            return;
        }
        backend.write("exists.txt", "data".getBytes());
        assertThat(backend.exists("exists.txt")).isTrue();
    }

    @Test
    void existsReturnsFalseForNonExistentKey() {
        if (backend == null) {
            return;
        }
        assertThat(backend.exists("nope.txt")).isFalse();
    }

    @Test
    void deleteRemovesObject() {
        if (backend == null) {
            return;
        }
        backend.write("del.txt", "data".getBytes());
        assertThat(backend.delete("del.txt")).isTrue();
        assertThat(backend.exists("del.txt")).isFalse();
    }

    @Test
    void deleteReturnsTrueEvenForNonExistentKey() {
        if (backend == null) {
            return;
        }
        assertThat(backend.delete("no-such-key.txt")).isTrue();
    }

    @Test
    void metadataReturnsSizeAndContentType() {
        if (backend == null) {
            return;
        }
        backend.write("meta.bin", new byte[42], "application/octet-stream", java.util.Map.of("key", "val"));

        StorageMetadata meta = backend.metadata("meta.bin");

        assertThat(meta).isNotNull();
        assertThat(meta.sizeBytes()).isEqualTo(42L);
        assertThat(meta.contentType()).isEqualTo("application/octet-stream");
        assertThat(meta.userMetadata()).containsEntry("key", "val");
    }

    @Test
    void metadataReturnsNullForNonExistentKey() {
        if (backend == null) {
            return;
        }
        assertThat(backend.metadata("no-meta")).isNull();
    }

    @Test
    void listReturnsKeysUnderPrefix() {
        if (backend == null) {
            return;
        }
        backend.write("dir/a.txt", "a".getBytes());
        backend.write("dir/b.txt", "b".getBytes());
        backend.write("other/c.txt", "c".getBytes());

        var listed = backend.list("dir/");

        assertThat(listed).hasSize(2);
        assertThat(listed).anyMatch(item -> item.contains("a.txt"));
        assertThat(listed).anyMatch(item -> item.contains("b.txt"));
    }

    @Test
    void listReturnsEmptyForNonExistentPrefix() {
        if (backend == null) {
            return;
        }
        assertThat(backend.list("nope/")).isEmpty();
    }

    @Test
    void deletePrefixRemovesAllKeysUnderPrefix() {
        if (backend == null) {
            return;
        }
        backend.write("pfx/x.txt", "x".getBytes());
        backend.write("pfx/y.txt", "y".getBytes());
        backend.write("keep/z.txt", "z".getBytes());

        assertThat(backend.deletePrefix("pfx/")).isEqualTo(2);
        assertThat(backend.exists("pfx/x.txt")).isFalse();
        assertThat(backend.exists("keep/z.txt")).isTrue();
    }

    @Test
    void totalSizeSumsObjectSizes() {
        if (backend == null) {
            return;
        }
        backend.write("sz/a.bin", new byte[100]);
        backend.write("sz/b.bin", new byte[200]);
        backend.write("other/c.bin", new byte[300]);

        assertThat(backend.totalSize("sz/")).isEqualTo(300L);
    }

    @Test
    void writeOverwritesExistingObject() {
        if (backend == null) {
            return;
        }
        backend.write("overwrite.txt", "v1".getBytes());
        backend.write("overwrite.txt", "v2".getBytes());

        assertThat(backend.read("overwrite.txt")).containsExactly("v2".getBytes());
    }
}
