package com.nyx.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StorageConfigTest {
    @Test
    void storageConfigDefaultsRemainLocalFirst() {
        StorageConfig config = new StorageConfig();

        assertThat(config.backend()).isEqualTo("local");
        assertThat(config.localCacheDir()).isEqualTo(Path.of("data/cache"));
        assertThat(config.s3().bucket()).isEmpty();
        assertThat(config.s3().region()).isEqualTo("us-east-1");
        assertThat(config.s3().pathStyleAccess()).isTrue();
    }

    @Test
    void storageConfigSupportsExplicitS3Overrides() {
        S3Config s3 = new S3Config(
            "nyx-cache",
            "https://s3.example.test",
            "eu-west-2",
            "access",
            "secret",
            "thumbs/",
            false
        );
        StorageConfig config = new StorageConfig("s3", Path.of("/tmp/unused-cache"), s3);

        assertThat(config.backend()).isEqualTo("s3");
        assertThat(config.localCacheDir()).isEqualTo(Path.of("/tmp/unused-cache"));
        assertThat(config.s3()).isEqualTo(s3);
    }
}
