package com.nyx.transcode;

import com.nyx.common.DatabaseResources;
import com.nyx.common.MetricsCollector;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.config.UserQuotaOverride;
import com.nyx.ffmpeg.ProbeService;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

abstract class AbstractTranscodeServiceTestSupport {
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<TranscodeService> managedServices = new ArrayList<>();

    protected Path tempDir;
    protected Path mediaDir;

    @BeforeEach
    void setUpTranscodeTestSupport() throws Exception {
        tempDir = Files.createTempDirectory("nyx-transcode-test");
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
    }

    @AfterEach
    void tearDownTranscodeTestSupport() throws Exception {
        for (int index = managedServices.size() - 1; index >= 0; index -= 1) {
            managedServices.get(index).shutdown();
        }
        managedServices.clear();

        for (HikariDataSource dataSource : dataSources) {
            dataSource.close();
        }
        dataSources.clear();

        deleteRecursively(tempDir);
    }

    protected TranscodeConfig testTranscodeConfig() {
        return testTranscodeConfig("both", 10, 6, 10_000, 524_288_000L, 3, 2_000L, 5);
    }

    protected TranscodeConfig testTranscodeConfig(int segmentCacheGracePeriodMinutes, long retryBackoffMs) {
        return testTranscodeConfig("both", segmentCacheGracePeriodMinutes, 6, 10_000, 524_288_000L, 3, retryBackoffMs, 5);
    }

    protected TranscodeConfig testTranscodeConfig(
        String defaultFormat,
        int segmentCacheGracePeriodMinutes,
        int segmentDurationSteadyStateSecs,
        int segmentCacheMaxEntries,
        long minFreeDiskBytes,
        int maxRetries,
        long retryBackoffMs,
        int circuitBreakerThreshold
    ) {
        return new TranscodeConfig(
            defaultFormat,
            segmentCacheGracePeriodMinutes,
            segmentDurationSteadyStateSecs,
            segmentCacheMaxEntries,
            minFreeDiskBytes,
            maxRetries,
            retryBackoffMs,
            circuitBreakerThreshold
        );
    }

    protected QuotaConfig testQuotaConfig() {
        return testQuotaConfig(false, 4, 60, 10_737_418_240L, Map.of());
    }

    protected QuotaConfig testQuotaConfig(
        boolean enabled,
        int defaultMaxConcurrentJobs,
        int defaultMaxRequestsPerMinute,
        long defaultMaxStorageBytes
    ) {
        return testQuotaConfig(
            enabled,
            defaultMaxConcurrentJobs,
            defaultMaxRequestsPerMinute,
            defaultMaxStorageBytes,
            Map.of()
        );
    }

    protected QuotaConfig testQuotaConfig(
        boolean enabled,
        int defaultMaxConcurrentJobs,
        int defaultMaxRequestsPerMinute,
        long defaultMaxStorageBytes,
        Map<String, UserQuotaOverride> userOverrides
    ) {
        return new QuotaConfig(
            enabled,
            defaultMaxConcurrentJobs,
            defaultMaxRequestsPerMinute,
            defaultMaxStorageBytes,
            userOverrides
        );
    }

    protected ServerConfig testServerConfig() {
        return testServerConfig(mediaDir, tempDir.resolve("db"), "ffmpeg", "ffprobe", 2, testTranscodeConfig());
    }

    protected ServerConfig testServerConfig(Path mediaDirectory, Path dbDir) {
        return testServerConfig(mediaDirectory, dbDir, "ffmpeg", "ffprobe", 2, testTranscodeConfig());
    }

    protected ServerConfig testServerConfig(
        Path mediaDirectory,
        Path dbDir,
        String ffmpegPath,
        String ffprobePath,
        int maxConcurrentJobs,
        TranscodeConfig transcodeConfig
    ) {
        return new ServerConfig(
            "0.0.0.0",
            8080,
            List.of("*"),
            List.of(new MediaRootConfig(mediaDirectory, "local")),
            new FfmpegConfig(ffmpegPath, ffprobePath, "6.0", maxConcurrentJobs),
            transcodeConfig,
            new DatabaseConfig(dbDir)
        );
    }

    protected TranscodeService createService(String dbName) throws Exception {
        return createServiceResources(dbName, "ffmpeg", "ffprobe", 2, 10, null, 2_000L, null, new InMemorySegmentRegistry(), null)
            .service();
    }

    protected ServiceResources createServiceWithRepo(String dbName) throws Exception {
        return createServiceWithRepo(dbName, new InMemorySegmentRegistry());
    }

    protected ServiceResources createServiceWithRepo(String dbName, SegmentRegistry segmentRegistry) throws Exception {
        return createServiceResources(dbName, "ffmpeg", "ffprobe", 2, 10, null, 2_000L, null, segmentRegistry, null);
    }

    protected ServiceResources createServiceAndRepo(String dbSubDir) throws Exception {
        Path dbDir = Files.createDirectories(tempDir.resolve("db").resolve(dbSubDir));

        DatabaseResources databaseResources = JobRepository.createDatabase(dbDir);
        dataSources.add(databaseResources.getDataSource());

        JobRepository jobRepository = new JobRepository(databaseResources.getJdbi());
        TranscodeService service = trackService(
            new TranscodeService(
                TranscodeEngineConfigMapper.toTranscodeEngineConfig(testServerConfig(mediaDir, dbDir)),
                new ProbeService(),
                new SegmentCache(),
                new ManifestGenerator(),
                jobRepository,
                new PathSecurity(List.of(mediaDir))
            )
        );

        return new ServiceResources(service, jobRepository, new InMemorySegmentRegistry());
    }

    protected ServiceResources createServiceResources(
        String dbName,
        String ffmpegPath,
        String ffprobePath,
        int maxConcurrentJobs,
        int gracePeriodMinutes,
        ScheduledExecutorService cleanupScheduler,
        long retryBackoffMs
    ) throws Exception {
        return createServiceResources(
            dbName,
            ffmpegPath,
            ffprobePath,
            maxConcurrentJobs,
            gracePeriodMinutes,
            cleanupScheduler,
            retryBackoffMs,
            null,
            new InMemorySegmentRegistry(),
            null
        );
    }

    protected ServiceResources createServiceResources(
        String dbName,
        String ffmpegPath,
        String ffprobePath,
        int maxConcurrentJobs,
        int gracePeriodMinutes,
        ScheduledExecutorService cleanupScheduler,
        long retryBackoffMs,
        MetricsCollector metricsService,
        SegmentRegistry segmentRegistry,
        QuotaService quotaService
    ) throws Exception {
        Path dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);

        DatabaseResources databaseResources = JobRepository.createDatabase(dbDir);
        dataSources.add(databaseResources.getDataSource());

        JobRepository jobRepository = new JobRepository(databaseResources.getJdbi());
        ServerConfig config = testServerConfig(
            mediaDir,
            dbDir,
            ffmpegPath,
            ffprobePath,
            maxConcurrentJobs,
            testTranscodeConfig(gracePeriodMinutes, retryBackoffMs)
        );

        TranscodeService service = trackService(
            new TranscodeService(
                TranscodeEngineConfigMapper.toTranscodeEngineConfig(config),
                new ProbeService(ffprobePath),
                new SegmentCache(),
                new ManifestGenerator(),
                jobRepository,
                new PathSecurity(List.of(mediaDir)),
                cleanupScheduler,
                metricsService,
                segmentRegistry,
                quotaService,
                new TranscodeCommandFactory()
            )
        );

        return new ServiceResources(service, jobRepository, segmentRegistry);
    }

    protected TranscodeService trackService(TranscodeService service) {
        managedServices.add(service);
        return service;
    }

    protected Path createScript(String name, String content) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(
            script,
            "#!/bin/bash\n" + content.stripIndent() + (content.endsWith("\n") ? "" : "\n")
        );
        Files.setPosixFilePermissions(
            script,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        );
        return script;
    }

    protected Path fakeProbeScript(String name) throws IOException {
        return createScript(
            name,
            """
            echo '{"format":{"format_name":"matroska","duration":"120.0","size":"1000000"},"streams":[{"index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080,"r_frame_rate":"24/1"},{"index":1,"codec_name":"aac","codec_type":"audio","channels":2}]}'
            exit 0
            """
        );
    }

    protected void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path)) {
            return;
        }

        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(entry -> {
                    try {
                        Files.deleteIfExists(entry);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    protected record ServiceResources(
        TranscodeService service,
        JobRepository jobRepository,
        SegmentRegistry segmentRegistry
    ) {
    }
}
