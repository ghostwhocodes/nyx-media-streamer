package com.nyx.common.storage;

import com.nyx.config.S3Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class S3StorageBackendTest {
    private InMemoryS3Client fakeS3;
    private S3StorageBackend backend;

    @BeforeEach
    void setUp() {
        fakeS3 = new InMemoryS3Client();
        fakeS3.createBucket("test-bucket");
        backend = new S3StorageBackend(
            new S3Config("test-bucket", "http://localhost:9000", "us-east-1", "key", "secret", "pfx", true),
            fakeS3
        );
    }

    @AfterEach
    void tearDown() {
        backend.close();
    }

    @Test
    void readReturnsBytesForExistingKey() {
        fakeS3.putObject("test-bucket", "pfx/hello.txt", "world".getBytes());

        assertThat(backend.read("hello.txt")).containsExactly("world".getBytes());
    }

    @Test
    void readReturnsNullForMissingKey() {
        assertThat(backend.read("nonexistent")).isNull();
    }

    @Test
    void writeStoresDataWithContentType() {
        backend.write("doc.json", "{\"a\":1}".getBytes(), "application/json");

        assertThat(fakeS3.getBytes("test-bucket", "pfx/doc.json")).containsExactly("{\"a\":1}".getBytes());
    }

    @Test
    void writeStoresDataWithoutContentType() {
        backend.write("file.bin", new byte[10]);

        assertThat(fakeS3.exists("test-bucket", "pfx/file.bin")).isTrue();
    }

    @Test
    void writeStoresMetadata() {
        backend.write("meta.bin", new byte[5], Map.of("k", "v"));

        assertThat(fakeS3.objects.get("test-bucket/pfx/meta.bin").metadata()).containsEntry("k", "v");
    }

    @Test
    void existsReturnsTrueForExistingKey() {
        backend.write("exists.txt", "x".getBytes());

        assertThat(backend.exists("exists.txt")).isTrue();
    }

    @Test
    void existsReturnsFalseForMissingKey() {
        assertThat(backend.exists("nope")).isFalse();
    }

    @Test
    void deleteRemovesObjectAndReturnsTrue() {
        backend.write("del.txt", "x".getBytes());

        assertThat(backend.delete("del.txt")).isTrue();
        assertThat(backend.exists("del.txt")).isFalse();
    }

    @Test
    void deleteReturnsTrueEvenForNonExistentKey() {
        assertThat(backend.delete("no-such")).isTrue();
    }

    @Test
    void metadataReturnsSizeAndContentType() {
        backend.write("info.bin", new byte[42], "application/octet-stream", Map.of("k", "v"));

        StorageMetadata meta = backend.metadata("info.bin");

        assertThat(meta).isNotNull();
        assertThat(meta.sizeBytes()).isEqualTo(42L);
        assertThat(meta.contentType()).isEqualTo("application/octet-stream");
        assertThat(meta.userMetadata()).containsEntry("k", "v");
    }

    @Test
    void metadataReturnsNullForMissingKey() {
        assertThat(backend.metadata("nope")).isNull();
    }

    @Test
    void listReturnsKeysUnderPrefix() {
        backend.write("dir/a.txt", "a".getBytes());
        backend.write("dir/b.txt", "b".getBytes());
        backend.write("other/c.txt", "c".getBytes());

        List<String> listed = backend.list("dir/");

        assertThat(listed).hasSize(2);
        assertThat(listed).anyMatch(item -> item.contains("a.txt"));
        assertThat(listed).anyMatch(item -> item.contains("b.txt"));
    }

    @Test
    void listReturnsEmptyForNoMatches() {
        assertThat(backend.list("empty/")).isEmpty();
    }

    @Test
    void listHandlesPaginatedResponses() {
        for (int index = 1; index <= 5; index++) {
            backend.write("many/file" + index + ".txt", "data".getBytes());
        }
        fakeS3.maxKeysPerPage = 2;

        assertThat(backend.list("many/")).hasSize(5);
    }

    @Test
    void listWithMetadataReturnsEntriesWithSizes() {
        backend.write("lm/a.bin", new byte[10]);
        backend.write("lm/b.bin", new byte[20]);

        List<StorageEntryInfo> entries = backend.listWithMetadata("lm/");

        assertThat(entries).hasSize(2);
        assertThat(entries).anyMatch(entry -> entry.sizeBytes() == 10L);
        assertThat(entries).anyMatch(entry -> entry.sizeBytes() == 20L);
        assertThat(entries).allMatch(entry -> entry.lastModifiedEpochMillis() > 0);
    }

    @Test
    void listWithMetadataHandlesPagination() {
        for (int index = 1; index <= 4; index++) {
            backend.write("pg/f" + index + ".bin", new byte[index * 10]);
        }
        fakeS3.maxKeysPerPage = 2;

        assertThat(backend.listWithMetadata("pg/")).hasSize(4);
    }

    @Test
    void listWithMetadataReturnsEmptyForNoMatches() {
        assertThat(backend.listWithMetadata("empty/")).isEmpty();
    }

    @Test
    void deletePrefixRemovesAllKeysUnderPrefix() {
        backend.write("dp/x.txt", "x".getBytes());
        backend.write("dp/y.txt", "y".getBytes());
        backend.write("keep/z.txt", "z".getBytes());

        assertThat(backend.deletePrefix("dp/")).isEqualTo(2);
        assertThat(backend.exists("dp/x.txt")).isFalse();
        assertThat(backend.exists("keep/z.txt")).isTrue();
    }

    @Test
    void deletePrefixReturnsZeroForEmptyPrefix() {
        assertThat(backend.deletePrefix("empty/")).isZero();
    }

    @Test
    void totalSizeSumsObjectSizes() {
        backend.write("ts/a.bin", new byte[100]);
        backend.write("ts/b.bin", new byte[200]);

        assertThat(backend.totalSize("ts/")).isEqualTo(300L);
    }

    @Test
    void totalSizeHandlesPagination() {
        for (int index = 1; index <= 4; index++) {
            backend.write("tsp/f" + index + ".bin", new byte[100]);
        }
        fakeS3.maxKeysPerPage = 2;

        assertThat(backend.totalSize("tsp/")).isEqualTo(400L);
    }

    @Test
    void totalSizeReturnsZeroForNoMatches() {
        assertThat(backend.totalSize("empty/")).isZero();
    }

    @Test
    void backendWithBlankPrefixUsesKeysDirectly() {
        InMemoryS3Client blankPrefixFake = new InMemoryS3Client();
        blankPrefixFake.createBucket("bucket2");
        S3StorageBackend blankPrefixBackend = new S3StorageBackend(
            new S3Config("bucket2", "http://localhost:9000", "us-east-1", "key", "secret", "", true),
            blankPrefixFake
        );

        blankPrefixBackend.write("direct.txt", "data".getBytes());
        assertThat(blankPrefixFake.exists("bucket2", "direct.txt")).isTrue();
        assertThat(blankPrefixBackend.list("")).contains("direct.txt");

        blankPrefixBackend.close();
    }

    @Test
    void constructorWithInaccessibleBucketLogsWarningButDoesNotThrow() {
        InMemoryS3Client fake = new InMemoryS3Client();

        assertThatCode(() -> {
            S3StorageBackend backend = new S3StorageBackend(
                new S3Config("nonexistent", "", "us-east-1", "k", "s", "", true),
                fake
            );
            backend.close();
        }).doesNotThrowAnyException();
    }

    @Test
    void deletePrefixHandlesBatchDeleteWithErrors() {
        backend.write("err/a.txt", "a".getBytes());
        backend.write("err/b.txt", "b".getBytes());
        fakeS3.deleteErrorKeys.add("pfx/err/a.txt");

        assertThat(backend.deletePrefix("err/")).isEqualTo(1);
    }

    record FakeS3Object(byte[] data, String contentType, Map<String, String> metadata, Instant lastModified) {
        FakeS3Object(byte[] data) {
            this(data, null, Map.of(), Instant.now());
        }

        FakeS3Object(byte[] data, String contentType, Map<String, String> metadata) {
            this(data, contentType, metadata, Instant.now());
        }
    }

    static final class InMemoryS3Client implements S3Client {
        final Map<String, FakeS3Object> objects = new HashMap<>();
        final Set<String> buckets = new HashSet<>();
        int maxKeysPerPage = 1000;
        final Set<String> deleteErrorKeys = new HashSet<>();

        void createBucket(String bucket) {
            buckets.add(bucket);
        }

        void putObject(String bucket, String key, byte[] data) {
            putObject(bucket, key, data, null, Map.of());
        }

        void putObject(String bucket, String key, byte[] data, String contentType, Map<String, String> metadata) {
            objects.put(bucket + "/" + key, new FakeS3Object(data, contentType, metadata));
        }

        byte[] getBytes(String bucket, String key) {
            FakeS3Object object = objects.get(bucket + "/" + key);
            return object == null ? null : object.data();
        }

        boolean exists(String bucket, String key) {
            return objects.containsKey(bucket + "/" + key);
        }

        @Override
        public String serviceName() {
            return "s3";
        }

        @Override
        public void close() {
        }

        @Override
        public HeadBucketResponse headBucket(HeadBucketRequest request) {
            if (!buckets.contains(request.bucket())) {
                throw NoSuchBucketException.builder().message("Bucket not found").build();
            }
            return HeadBucketResponse.builder().build();
        }

        @Override
        public ResponseInputStream<GetObjectResponse> getObject(GetObjectRequest request) {
            String key = request.bucket() + "/" + request.key();
            FakeS3Object object = objects.get(key);
            if (object == null) {
                throw NoSuchKeyException.builder().message("No such key").build();
            }
            GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) object.data().length)
                .contentType(object.contentType())
                .lastModified(object.lastModified())
                .metadata(object.metadata())
                .build();
            return new ResponseInputStream<>(response, new ByteArrayInputStream(object.data()));
        }

        @Override
        public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
            String key = request.bucket() + "/" + request.key();
            FakeS3Object object = objects.get(key);
            if (object == null) {
                throw NoSuchKeyException.builder().message("No such key").build();
            }
            GetObjectResponse response = GetObjectResponse.builder()
                .contentLength((long) object.data().length)
                .contentType(object.contentType())
                .lastModified(object.lastModified())
                .metadata(object.metadata())
                .build();
            return ResponseBytes.fromByteArray(response, object.data());
        }

        @Override
        public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
            try {
                byte[] data = body.contentStreamProvider().newStream().readAllBytes();
                objects.put(
                    request.bucket() + "/" + request.key(),
                    new FakeS3Object(
                        data,
                        request.contentType(),
                        request.metadata() == null ? Map.of() : request.metadata()
                    )
                );
                return PutObjectResponse.builder().build();
            } catch (IOException exception) {
                throw new RuntimeException(exception);
            }
        }

        @Override
        public HeadObjectResponse headObject(HeadObjectRequest request) {
            String key = request.bucket() + "/" + request.key();
            FakeS3Object object = objects.get(key);
            if (object == null) {
                throw NoSuchKeyException.builder().message("No such key").build();
            }
            return HeadObjectResponse.builder()
                .contentLength((long) object.data().length)
                .contentType(object.contentType())
                .lastModified(object.lastModified())
                .metadata(object.metadata())
                .build();
        }

        @Override
        public DeleteObjectResponse deleteObject(DeleteObjectRequest request) {
            objects.remove(request.bucket() + "/" + request.key());
            return DeleteObjectResponse.builder().build();
        }

        @Override
        public DeleteObjectsResponse deleteObjects(DeleteObjectsRequest request) {
            List<S3Error> errors = new ArrayList<>();
            List<DeletedObject> deleted = new ArrayList<>();
            String bucket = request.bucket();
            for (ObjectIdentifier identifier : request.delete().objects()) {
                String fullKey = identifier.key();
                if (deleteErrorKeys.contains(fullKey)) {
                    errors.add(S3Error.builder().key(fullKey).code("InternalError").message("Simulated error").build());
                } else {
                    objects.remove(bucket + "/" + fullKey);
                    deleted.add(DeletedObject.builder().key(fullKey).build());
                }
            }
            return DeleteObjectsResponse.builder().deleted(deleted).errors(errors).build();
        }

        @Override
        public ListObjectsV2Response listObjectsV2(ListObjectsV2Request request) {
            String bucket = request.bucket();
            String prefix = request.prefix() == null ? "" : request.prefix();
            String token = request.continuationToken();

            List<S3Object> allMatching = objects.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(bucket + "/" + prefix))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    String key = entry.getKey().replaceFirst("^" + java.util.regex.Pattern.quote(bucket + "/"), "");
                    FakeS3Object object = entry.getValue();
                    return S3Object.builder()
                        .key(key)
                        .size((long) object.data().length)
                        .lastModified(object.lastModified())
                        .build();
                })
                .toList();

            int startIndex = token == null ? 0 : Integer.parseInt(token);
            int endIndex = Math.min(startIndex + maxKeysPerPage, allMatching.size());
            List<S3Object> page = allMatching.subList(startIndex, endIndex);
            boolean truncated = endIndex < allMatching.size();

            ListObjectsV2Response.Builder builder = ListObjectsV2Response.builder()
                .contents(page)
                .isTruncated(truncated);
            if (truncated) {
                builder.nextContinuationToken(Integer.toString(endIndex));
            }
            return builder.build();
        }
    }
}
