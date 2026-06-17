package com.nyx.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.DatabaseResources;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.ServerConfig;
import com.nyx.json.NyxJson;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.transcode.JobRepository;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobStore;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HealthServiceTest {
    private Path tempDir;
    private Path dbDir;
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-health-test");
        dbDir = Files.createDirectories(tempDir.resolve("db"));
    }

    @AfterEach
    void teardown() throws Exception {
        try {
            Path readOnlyDir = tempDir.resolve("readonly-db");
            if (Files.exists(readOnlyDir)) {
                Files.setPosixFilePermissions(
                    readOnlyDir,
                    Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                    )
                );
            }
        } catch (Exception ignored) {
        }

        for (HikariDataSource dataSource : dataSources) {
            try {
                dataSource.close();
            } catch (Exception ignored) {
            }
        }
        dataSources.clear();
        deleteRecursively(tempDir);
    }

    private ServerConfig testConfig() {
        return testConfig("ffmpeg", "ffprobe", List.of(), 524_288_000L, dbDir);
    }

    private ServerConfig testConfig(String ffmpegPath) {
        return testConfig(ffmpegPath, "ffprobe", List.of(), 524_288_000L, dbDir);
    }

    private ServerConfig testConfig(Path databaseDir) {
        return testConfig("ffmpeg", "ffprobe", List.of(), 524_288_000L, databaseDir);
    }

    private ServerConfig testConfig(List<MediaRootConfig> mediaRoots, long minFreeDiskBytes) {
        return testConfig("ffmpeg", "ffprobe", mediaRoots, minFreeDiskBytes, dbDir);
    }

    private ServerConfig testConfig(
        String ffmpegPath,
        String ffprobePath,
        List<MediaRootConfig> mediaRoots,
        long minFreeDiskBytes,
        Path databaseDir
    ) {
        return AdminFixtures.testServerConfig(
            "0.0.0.0",
            8080,
            List.of("*"),
            mediaRoots,
            new FfmpegConfig(ffmpegPath, ffprobePath, "6.0", 2),
            AdminFixtures.testTranscodeConfig("both", 10, 6, 10_000, minFreeDiskBytes, 3, 2_000L, 5),
            new DatabaseConfig(databaseDir),
            new com.nyx.config.ThumbnailConfig(),
            new com.nyx.config.AudioConfig(),
            new com.nyx.config.AuthConfig(),
            new com.nyx.config.RateLimitConfig(),
            new com.nyx.config.CsrfConfig(),
            new com.nyx.config.TlsConfig(),
            new com.nyx.config.WebhookConfig(),
            new com.nyx.config.QuotaConfig(),
            new com.nyx.config.BackupConfig(),
            new com.nyx.config.StorageConfig()
        );
    }

    private JobRepository createJobRepository() {
        DatabaseResources resources = JobRepository.createDatabase(dbDir);
        dataSources.add(resources.getDataSource());
        return new JobRepository(resources.getJdbi());
    }

    private TranscodeJobStore createBrokenJobRepository(String subDir) throws Exception {
        Files.createDirectories(tempDir.resolve(subDir));
        return new TranscodeJobStore() {
            @Override
            public TranscodeJob create(TranscodeJob job) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TranscodeJob createWithQuotaCheck(TranscodeJob job, int maxConcurrent, Long maxStorageBytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TranscodeJob getById(String id) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void updateStatus(String id, JobStatus newStatus) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void updateProgress(String id, int segmentsProduced) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void updateRetryCount(String id, int count) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void storeStderr(String id, String initial, String fallback) {
                throw new UnsupportedOperationException();
            }

            @Override
            public TranscodeJob findActiveBySpecKey(String specKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes, int limit, long offset, String owner) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countFiltered(JobStatus status, Integer sinceMinutes, String owner) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<TranscodeJob> listActive() {
                throw new IllegalStateException("simulated listActive failure");
            }

            @Override
            public List<TranscodeJob> listByBatchId(String batchId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countActiveByOwner(String ownerId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void updateOutputSize(String id, long sizeBytes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long sumStorageByOwner(String ownerId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countAll(String owner) {
                throw new IllegalStateException("simulated countAll failure");
            }

            @Override
            public List<TranscodeJob> listRecent(int limit, long offset, String owner) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Test
    void mediaRootStatusAndJvmInfoPreserveFields() {
        MediaRootStatus mediaRootStatus = new MediaRootStatus("/media/videos", true, true, 1_073_741_824L);
        JvmInfo jvmInfo = new JvmInfo("21.0.6", 2_147_483_648L, 8);

        assertEquals("/media/videos", mediaRootStatus.path());
        assertTrue(mediaRootStatus.exists());
        assertTrue(mediaRootStatus.readable());
        assertEquals(1_073_741_824L, mediaRootStatus.freeSpaceBytes());
        assertEquals("21.0.6", jvmInfo.version());
        assertEquals(8, jvmInfo.availableProcessors());
    }

    @Test
    void runtimeHealthReportsDegradedWhenFfmpegUnavailable() {
        HealthService service = new HealthService(testConfig("/nonexistent/ffmpeg"), createJobRepository());

        RuntimeHealthResponse health = service.runtimeHealth();

        assertEquals("degraded", health.status());
        assertFalse(health.ffmpegAvailable());
    }

    @Test
    void runtimeHealthReportsWritableStateForDatabaseDirectory() {
        HealthService writable = new HealthService(testConfig(), createJobRepository());
        HealthService missing = new HealthService(testConfig(Path.of("/nonexistent/db/path")), createJobRepository());

        assertTrue(writable.runtimeHealth().dbWritable());
        assertFalse(missing.runtimeHealth().dbWritable());
    }

    @Test
    void runtimeHealthReportsBaselineCountsAndWarnings() {
        HealthService service = new HealthService(testConfig(), createJobRepository());

        RuntimeHealthResponse health = service.runtimeHealth();

        assertEquals(0, health.activeJobs());
        assertTrue(Set.of("ok", "degraded").contains(health.status()));
        assertTrue(health.dbConnectivity());
        assertFalse(health.diskSpaceWarning());
        assertFalse(health.stuckJobsWarning());
    }

    @Test
    void runtimeHealthUsesFallbackValuesWhenRepositoryQueriesFail() throws Exception {
        HealthService service = new HealthService(testConfig("/nonexistent/ffmpeg"), createBrokenJobRepository("broken-db"));

        RuntimeHealthResponse health = service.runtimeHealth();

        assertEquals(-1, health.activeJobs());
        assertFalse(health.dbConnectivity());
        assertEquals("degraded", health.status());
    }

    @Test
    void runtimeHealthHandlesCircuitBreakerStates() {
        HealthService open = new HealthService(testConfig(), createJobRepository(), () -> true);
        HealthService closed = new HealthService(testConfig(), createJobRepository(), () -> false);
        HealthService absent = new HealthService(testConfig(), createJobRepository());

        assertTrue(open.runtimeHealth().circuitBreakerOpen());
        assertEquals("degraded", open.runtimeHealth().status());
        assertFalse(closed.runtimeHealth().circuitBreakerOpen());
        assertFalse(absent.runtimeHealth().circuitBreakerOpen());
    }

    @Test
    void runtimeHealthSetsStuckJobsWarningWhenActiveJobsExceedThreshold() {
        assumeFfmpegAndFfprobeAvailable();
        JobRepository repository = createJobRepository();
        for (int index = 0; index < 51; index++) {
            repository.create(new TranscodeJob(
                "stuck-" + index,
                JobStatus.QUEUED,
                "/test" + index + ".mkv",
                "h264_fast",
                StreamRepresentation.DASH_FMP4
            ));
        }

        RuntimeHealthResponse health = new HealthService(testConfig(), repository, () -> false).runtimeHealth();

        assertTrue(health.ffmpegAvailable());
        assertTrue(health.dbWritable());
        assertTrue(health.dbConnectivity());
        assertTrue(health.stuckJobsWarning());
        assertEquals("degraded", health.status());
    }

    @Test
    void livenessAlwaysReturnsAlive() {
        LivenessResponse response = new HealthService(testConfig(), createJobRepository()).liveness();

        assertEquals("alive", response.status());
        assertEquals("alive", new LivenessResponse().status());
    }

    @Test
    void readinessReflectsDatabaseAndDiskState() {
        HealthService ready = new HealthService(testConfig(List.of(new MediaRootConfig(tempDir, "local")), 1L), createJobRepository());
        HealthService missingDb = new HealthService(testConfig(Path.of("/nonexistent/path/db")), createJobRepository());
        HealthService highThreshold = new HealthService(
            testConfig(List.of(new MediaRootConfig(tempDir, "local")), Long.MAX_VALUE),
            createJobRepository()
        );

        ReadinessResponse readyResponse = ready.readiness();
        ReadinessResponse missingDbResponse = missingDb.readiness();
        ReadinessResponse highThresholdResponse = highThreshold.readiness();

        assertEquals("ready", readyResponse.status());
        assertTrue(readyResponse.dbWritable());
        assertTrue(readyResponse.dbConnectivity());
        assertTrue(readyResponse.diskSpaceOk());

        assertEquals("not_ready", missingDbResponse.status());
        assertFalse(missingDbResponse.dbWritable());

        assertEquals("not_ready", highThresholdResponse.status());
        assertFalse(highThresholdResponse.diskSpaceOk());
    }

    @Test
    void readinessHandlesCircuitBreakerAndBrokenConnectivity() throws Exception {
        HealthService breakerOpen = new HealthService(testConfig(), createJobRepository(), () -> true);
        HealthService brokenRepo = new HealthService(testConfig(), createBrokenJobRepository("broken-db-2"));

        ReadinessResponse breakerResponse = breakerOpen.readiness();
        ReadinessResponse brokenResponse = brokenRepo.readiness();

        assertEquals("not_ready", breakerResponse.status());
        assertTrue(breakerResponse.circuitBreakerOpen());

        assertEquals("not_ready", brokenResponse.status());
        assertFalse(brokenResponse.dbConnectivity());
    }

    @Test
    void readinessTreatsMissingMediaRootsAsDiskOk() {
        HealthService service = new HealthService(
            testConfig(List.of(new MediaRootConfig(Path.of("/nonexistent/media/root"), "local")), 1L),
            createJobRepository()
        );

        assertTrue(service.readiness().diskSpaceOk());
    }

    @Test
    void runStartupChecksMissingFfmpegSetsUnavailableAndNullVersion() {
        HealthService service = new HealthService(testConfig("/nonexistent/ffmpeg"), createJobRepository());

        StartupHealthReport report = service.runStartupChecks();

        assertFalse(service.isFfmpegAvailable());
        assertNull(report.ffmpegVersion());
    }

    @Test
    void runStartupChecksMissingFfprobeSetsUnavailable() {
        assumeBinaryAvailable("ffmpeg");
        HealthService service = new HealthService(
            testConfig("ffmpeg", "/nonexistent/ffprobe", List.of(), 524_288_000L, dbDir),
            createJobRepository()
        );

        service.runStartupChecks();

        assertFalse(service.isFfmpegAvailable());
    }

    @Test
    void runStartupChecksWithRealFfmpegReportsMediaRootStatus() throws Exception {
        assumeFfmpegAndFfprobeAvailable();
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        HealthService service = new HealthService(
            testConfig("ffmpeg", "ffprobe", List.of(new MediaRootConfig(mediaRoot, "local")), 524_288_000L, dbDir),
            createJobRepository()
        );

        StartupHealthReport report = service.runStartupChecks();

        assertNotNull(report.ffmpegVersion());
        assertNotNull(report.ffprobeVersion());
        assertEquals("ok", report.dbStatus());
        assertEquals(1, report.mediaRoots().size());
        assertTrue(report.mediaRoots().get(0).exists());
        assertTrue(report.mediaRoots().get(0).readable());
    }

    @Test
    void runStartupChecksReportsNonExistentMediaRoot() {
        assumeFfmpegAndFfprobeAvailable();
        HealthService service = new HealthService(
            testConfig(
                "ffmpeg",
                "ffprobe",
                List.of(new MediaRootConfig(Path.of("/nonexistent/media/root"), "local")),
                524_288_000L,
                dbDir
            ),
            createJobRepository()
        );

        StartupHealthReport report = service.runStartupChecks();
        MediaRootStatus root = report.mediaRoots().get(0);

        assertFalse(root.exists());
        assertFalse(root.readable());
        assertEquals(0L, root.freeSpaceBytes());
    }

    @Test
    void runStartupChecksFailsWithInsufficientVersion() {
        assumeFfmpegAndFfprobeAvailable();
        ServerConfig config = AdminFixtures.copyServerConfig(
            testConfig(),
            null,
            null,
            null,
            null,
            new FfmpegConfig("ffmpeg", "ffprobe", "99.0", 2),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );

        assertThrows(IllegalStateException.class, () -> new HealthService(config, createJobRepository()).runStartupChecks());
    }

    @Test
    void runStartupChecksCreatesNonexistentDatabaseDir() {
        Path newDbDir = tempDir.resolve("nonexistent-db-dir");
        HealthService service = new HealthService(
            testConfig("/nonexistent/ffmpeg", "ffprobe", List.of(), 524_288_000L, newDbDir),
            createJobRepository()
        );

        service.runStartupChecks();

        assertTrue(Files.exists(newDbDir));
    }

    @Test
    void runStartupChecksReportsReadOnlyDatabaseDirectoryError() throws Exception {
        Path readOnlyDir = Files.createDirectories(tempDir.resolve("readonly-db"));
        Files.setPosixFilePermissions(
            readOnlyDir,
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE)
        );
        HealthService service = new HealthService(
            testConfig("/nonexistent/ffmpeg", "ffprobe", List.of(), 524_288_000L, readOnlyDir),
            createJobRepository()
        );

        StartupHealthReport report = service.runStartupChecks();

        assertEquals("error: directory not writable", report.dbStatus());
    }

    @Test
    void runStartupChecksReportsProblematicDatabasePathError() {
        Path impossibleDir = Path.of("/proc/self/fd/999/impossible-db-dir");
        HealthService service = new HealthService(
            testConfig("/nonexistent/ffmpeg", "ffprobe", List.of(), 524_288_000L, impossibleDir),
            createJobRepository()
        );

        StartupHealthReport report = service.runStartupChecks();

        assertTrue(report.dbStatus().startsWith("error:"));
    }

    @Test
    void responseRecordsPreserveExtendedFields() {
        RuntimeHealthResponse runtime = new RuntimeHealthResponse("degraded", true, 60, true, true, false, true, true, null, null);
        ReadinessResponse readiness = new ReadinessResponse("ready", true, true);
        StartupHealthReport startup = new StartupHealthReport(
            "6.1.1",
            "6.1.1",
            List.of("vaapi", "cuda"),
            List.of("libx264", "libx265"),
            List.of(new MediaRootStatus("/media", true, true, 1_000_000L)),
            "ok",
            new JvmInfo("21.0.6", 2_147_483_648L, 8)
        );

        assertTrue(runtime.diskSpaceWarning());
        assertFalse(runtime.dbConnectivity());
        assertTrue(runtime.stuckJobsWarning());
        assertTrue(runtime.circuitBreakerOpen());
        assertEquals("ready", readiness.status());
        assertTrue(readiness.diskSpaceOk());
        assertFalse(readiness.circuitBreakerOpen());
        assertEquals(2, startup.hwAccels().size());
        assertEquals(1, startup.mediaRoots().size());
    }

    @Test
    void adminHealthRecordsSerializeWithJackson() throws Exception {
        StartupHealthReport startup = new StartupHealthReport(
            "6.1",
            "6.1",
            List.of("cuda"),
            List.of("h264_nvenc"),
            List.of(new MediaRootStatus("/media", true, true, 1024L)),
            "ok",
            new JvmInfo("21.0.2", 1024L, 8)
        );
        RuntimeHealthResponse runtime = new RuntimeHealthResponse(
            "healthy",
            true,
            2,
            true,
            false,
            true,
            false,
            true,
            null,
            null
        );

        String startupJson = json.writeValueAsString(startup);
        String runtimeJson = json.writeValueAsString(runtime);

        assertEquals(startup, json.readValue(startupJson, StartupHealthReport.class));
        assertEquals(runtime, json.readValue(runtimeJson, RuntimeHealthResponse.class));
        assertTrue(startupJson.contains("\"hwAccels\":[\"cuda\"]"));
        assertTrue(runtimeJson.contains("\"circuit_breaker_open\":true"));
    }

    private static void assumeFfmpegAndFfprobeAvailable() {
        Assumptions.assumeTrue(binaryAvailable("ffmpeg"), "FFmpeg not available");
        Assumptions.assumeTrue(binaryAvailable("ffprobe"), "FFprobe not available");
    }

    private static void assumeBinaryAvailable(String binary) {
        Assumptions.assumeTrue(binaryAvailable(binary), binary + " not available");
    }

    private static boolean binaryAvailable(String binary) {
        try {
            return new ProcessBuilder(binary, "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete " + path, exception);
                }
            });
        }
    }

    @SuppressWarnings("unused")
    private String loadFixture(String name) throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing fixture " + name);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder builder = new StringBuilder();
                String line;
                boolean first = true;
                while ((line = reader.readLine()) != null) {
                    if (!first) {
                        builder.append('\n');
                    }
                    builder.append(line);
                    first = false;
                }
                return builder.toString();
            }
        }
    }
}
