package com.nyx.common.storage;

import com.nyx.config.S3Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class S3StorageBackend implements StorageBackend {
    private static final Logger LOG = LoggerFactory.getLogger(S3StorageBackend.class);

    private final String bucket;
    private final String prefix;
    private final S3Client s3;

    public S3StorageBackend(S3Config config) {
        this(config, null);
    }

    public S3StorageBackend(S3Config config, S3Client s3Client) {
        this.bucket = config.getBucket();
        this.prefix = config.getPrefix();
        this.s3 = s3Client == null
            ? S3Client.builder()
                .region(Region.of(config.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())
                ))
                .overrideConfiguration(cfg -> {
                    cfg.apiCallTimeout(Duration.ofSeconds(10));
                    cfg.apiCallAttemptTimeout(Duration.ofSeconds(5));
                })
                .applyMutation(builder -> {
                    if (!config.getEndpoint().isBlank()) {
                        builder.endpointOverride(URI.create(config.getEndpoint()));
                    }
                    if (config.getPathStyleAccess()) {
                        builder.forcePathStyle(true);
                    }
                })
                .build()
            : s3Client;

        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (Exception exception) {
            LOG.warn(
                "S3 bucket '{}' is not accessible: {}. Storage operations will fail at runtime.",
                bucket,
                exception.getMessage()
            );
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(fullKey(key)).build()).asByteArray();
        } catch (NoSuchKeyException ignored) {
            return null;
        }
    }

    @Override
    public void write(String key, byte[] data, String contentType, Map<String, String> metadata) {
        PutObjectRequest.Builder builder = PutObjectRequest.builder()
            .bucket(bucket)
            .key(fullKey(key))
            .metadata(metadata);
        if (contentType != null) {
            builder.contentType(contentType);
        }
        s3.putObject(builder.build(), RequestBody.fromBytes(data));
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(fullKey(key)).build());
            return true;
        } catch (NoSuchKeyException ignored) {
            return false;
        }
    }

    @Override
    public boolean delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(fullKey(key)).build());
        return true;
    }

    @Override
    public StorageMetadata metadata(String key) {
        try {
            var head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(fullKey(key)).build());
            return new StorageMetadata(
                head.contentLength(),
                head.lastModified().toEpochMilli(),
                head.contentType(),
                head.metadata() == null ? Map.of() : head.metadata()
            );
        } catch (NoSuchKeyException ignored) {
            return null;
        }
    }

    @Override
    public List<String> list(String prefix) {
        String fullPrefix = fullKey(prefix);
        List<String> result = new ArrayList<>();
        String nextToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(fullPrefix);
            if (nextToken != null) {
                request.continuationToken(nextToken);
            }
            var response = s3.listObjectsV2(request.build());
            if (response.contents() != null) {
                response.contents().forEach(obj -> result.add(relativeKey(obj.key())));
            }
            nextToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (nextToken != null);
        return result;
    }

    @Override
    public List<StorageEntryInfo> listWithMetadata(String prefix) {
        String fullPrefix = fullKey(prefix);
        List<StorageEntryInfo> result = new ArrayList<>();
        String nextToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(fullPrefix);
            if (nextToken != null) {
                request.continuationToken(nextToken);
            }
            var response = s3.listObjectsV2(request.build());
            if (response.contents() != null) {
                response.contents().forEach(obj ->
                    result.add(new StorageEntryInfo(relativeKey(obj.key()), obj.size(), obj.lastModified().toEpochMilli()))
                );
            }
            nextToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (nextToken != null);
        return result;
    }

    @Override
    public int deletePrefix(String prefix) {
        List<String> keys = list(prefix);
        if (keys.isEmpty()) {
            return 0;
        }

        int deleted = 0;
        for (int index = 0; index < keys.size(); index += 1000) {
            List<String> chunk = keys.subList(index, Math.min(index + 1000, keys.size()));
            List<ObjectIdentifier> objects = chunk.stream()
                .map(key -> ObjectIdentifier.builder().key(fullKey(key)).build())
                .toList();
            var response = s3.deleteObjects(
                DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(objects).build())
                    .build()
            );
            List<software.amazon.awssdk.services.s3.model.S3Error> errors =
                response.errors() == null ? List.of() : response.errors();
            if (!errors.isEmpty()) {
                errors.forEach(error ->
                    LOG.warn("S3 batch delete failed for key={}: {} {}", error.key(), error.code(), error.message())
                );
            }
            deleted += chunk.size() - errors.size();
        }
        return deleted;
    }

    @Override
    public long totalSize(String prefix) {
        String fullPrefix = fullKey(prefix);
        long total = 0L;
        String nextToken = null;
        do {
            ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(fullPrefix);
            if (nextToken != null) {
                request.continuationToken(nextToken);
            }
            var response = s3.listObjectsV2(request.build());
            if (response.contents() != null) {
                for (var object : response.contents()) {
                    total += object.size();
                }
            }
            nextToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
        } while (nextToken != null);
        return total;
    }

    @Override
    public void close() {
        s3.close();
    }

    private String fullKey(String key) {
        return prefix.isBlank() ? key : prefix + "/" + key;
    }

    private String relativeKey(String fullKey) {
        return prefix.isBlank() ? fullKey : fullKey.replaceFirst("^" + java.util.regex.Pattern.quote(prefix + "/"), "");
    }
}
