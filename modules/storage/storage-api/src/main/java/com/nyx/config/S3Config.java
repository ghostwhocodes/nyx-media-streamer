package com.nyx.config;

public record S3Config(
    String bucket,
    String endpoint,
    String region,
    String accessKey,
    String secretKey,
    String prefix,
    boolean pathStyleAccess
) {
    public S3Config {
        bucket = bucket == null ? "" : bucket;
        endpoint = endpoint == null ? "" : endpoint;
        region = region == null ? "us-east-1" : region;
        accessKey = accessKey == null ? "" : accessKey;
        secretKey = secretKey == null ? "" : secretKey;
        prefix = prefix == null ? "" : prefix;
    }

    public S3Config() {
        this("", "", "us-east-1", "", "", "", true);
    }

    public String getBucket() {
        return bucket;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRegion() {
        return region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getPrefix() {
        return prefix;
    }

    public boolean getPathStyleAccess() {
        return pathStyleAccess;
    }
}
