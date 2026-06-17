package com.nyx.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigServiceSerializationTest {
    private final ObjectMapper json = NyxJson.newMapper();

    private static FfmpegConfig ffmpegConfig(int maxConcurrentJobs) {
        return new FfmpegConfig(
            "ffmpeg",
            "ffprobe",
            "6.0",
            maxConcurrentJobs,
            4,
            8,
            Map.of(
                "low", "h264_fast",
                "medium", "h264_balanced",
                "high", "h265_quality"
            ),
            "polling",
            500L
        );
    }

    private static TranscodeConfig transcodeConfig() {
        return new TranscodeConfig("hls", 10, 6, 10_000, 524_288_000L, 3, 2_000L, 5);
    }

    private static DatabaseConfig databaseConfig(Path dir) {
        return new DatabaseConfig(dir, 4, 600_000L, 1_800_000L);
    }

    private static QuotaConfig quotaConfig(boolean enabled, int maxConcurrentJobs, int maxRequestsPerMinute) {
        return new QuotaConfig(enabled, maxConcurrentJobs, maxRequestsPerMinute, 10_737_418_240L, Map.of());
    }

    @Test
    void configServiceGetSanitizedConfigCoversAllFields() {
        ServerConfig serverConfig = new ServerConfig(
            "localhost",
            9090,
            List.of("http://localhost:3000"),
            List.of(new MediaRootConfig(Path.of("/tmp/media"))),
            ffmpegConfig(2),
            transcodeConfig(),
            databaseConfig(Path.of("/tmp/db")),
            new ThumbnailConfig(),
            new AudioConfig(),
            new AuthConfig(true, "test-token", Map.of("admin", "hash"), Map.of("tok1", "user1")),
            new RateLimitConfig(),
            new CsrfConfig(),
            new TlsConfig(),
            new WebhookConfig(),
            quotaConfig(true, 8, 120),
            new BackupConfig(),
            new StorageConfig()
        );
        ConfigService service = new ConfigService(serverConfig);
        SanitizedConfig sanitized = service.getSanitizedConfig();

        assertThat(sanitized.host()).isEqualTo("localhost");
        assertThat(sanitized.port()).isEqualTo(9090);
        assertThat(sanitized.corsOrigins()).containsExactly("http://localhost:3000");
        assertThat(sanitized.mediaRoots()).hasSize(1);
        assertThat(sanitized.mediaRoots().get(0).filesystem()).isEqualTo("local");
        assertThat(sanitized.auth().enabled()).isTrue();
        assertThat(sanitized.auth().hasToken()).isTrue();
        assertThat(sanitized.auth().hasMultiToken()).isTrue();
        assertThat(sanitized.auth().tokenUsers()).containsExactly("user1");
        assertThat(sanitized.auth().users()).containsExactly("admin");
        assertThat(sanitized.transcode().defaultFormat()).isEqualTo("hls");
        assertThat(sanitized.transcode().maxConcurrentJobs()).isEqualTo(2);
        assertThat(sanitized.transcode().segmentCacheGracePeriodMinutes()).isEqualTo(10);
        assertThat(sanitized.thumbnails().sizes()).containsExactly(150, 300, 600);
        assertThat(sanitized.thumbnails().videoOffsetPercent()).isEqualTo(10);
        assertThat(sanitized.thumbnails().maxCacheSizeMB()).isEqualTo(1024);
        assertThat(sanitized.quota().enabled()).isTrue();
        assertThat(sanitized.quota().defaultMaxConcurrentJobs()).isEqualTo(8);
        assertThat(sanitized.quota().defaultMaxRequestsPerMinute()).isEqualTo(120);
    }

    @Test
    void configServiceListUsersReturnsUserKeys() {
        ServerConfig serverConfig = new ServerConfig(
            "localhost",
            8080,
            List.of(),
            List.of(new MediaRootConfig(Path.of("/tmp"))),
            ffmpegConfig(2),
            transcodeConfig(),
            databaseConfig(Path.of("/tmp/db")),
            new ThumbnailConfig(),
            new AudioConfig(),
            new AuthConfig(false, "", Map.of("alice", "h1", "bob", "h2"), Map.of()),
            new RateLimitConfig(),
            new CsrfConfig(),
            new TlsConfig(),
            new WebhookConfig(),
            new QuotaConfig(),
            new BackupConfig(),
            new StorageConfig()
        );
        ConfigService service = new ConfigService(serverConfig);

        assertThat(service.listUsers()).containsExactlyInAnyOrder("alice", "bob");
    }

    @Test
    void sanitizedConfigSerializationRoundtrip() throws Exception {
        SanitizedConfig config = new SanitizedConfig(
            "h",
            80,
            List.of(),
            List.of(new SanitizedMediaRoot("/tmp", "local")),
            new SanitizedAuth(false, false, false, List.of(), List.of()),
            new SanitizedTranscode("hls", 2, 10),
            new SanitizedThumbnails(List.of(150), 10, 512),
            new SanitizedQuota(false, 4, 60)
        );

        String encoded = json.writeValueAsString(config);
        SanitizedConfig decoded = json.readValue(encoded, SanitizedConfig.class);

        assertThat(decoded.host()).isEqualTo("h");
    }

    @Test
    void configUpdateResponseSerialization() throws Exception {
        ConfigUpdateResponse response = new ConfigUpdateResponse(
            new SanitizedConfig(
                "h",
                80,
                List.of(),
                List.of(),
                new SanitizedAuth(false, false, false, List.of(), List.of()),
                new SanitizedTranscode("hls", 2, 10),
                new SanitizedThumbnails(List.of(150), 10, 512),
                new SanitizedQuota(false, 4, 60)
            ),
            true
        );

        String encoded = json.writeValueAsString(response);
        ConfigUpdateResponse decoded = json.readValue(encoded, ConfigUpdateResponse.class);

        assertThat(decoded.restartRequired()).isTrue();
    }

    @Test
    void createUserResponseSerialization() throws Exception {
        CreateUserResponse response = new CreateUserResponse("alice");

        String encoded = json.writeValueAsString(response);
        CreateUserResponse decoded = json.readValue(encoded, CreateUserResponse.class);

        assertThat(decoded.username()).isEqualTo("alice");
    }
}
