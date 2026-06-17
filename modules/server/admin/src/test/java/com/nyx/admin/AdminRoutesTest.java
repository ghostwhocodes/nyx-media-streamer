package com.nyx.admin;

import static com.nyx.admin.AdminApiTestSupport.bodyAsText;
import static com.nyx.admin.AdminApiTestSupport.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.QuotaService;
import com.nyx.common.VirtualPathResolver;
import com.nyx.common.storage.InMemoryStorageBackend;
import com.nyx.config.MediaRootConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.nyx.media.AudioMetadataService;
import com.nyx.media.LibraryAdminService;
import com.nyx.media.LibraryCatalogService;
import com.nyx.media.LibraryExtensionCoordinator;
import com.nyx.media.LibraryInterpretationService;
import com.nyx.media.LibraryScanMode;
import com.nyx.media.LibraryScanRunStatus;
import com.nyx.media.LibraryScanService;
import com.nyx.media.LibraryService;
import com.nyx.media.LibraryTrackedObjectStatus;
import com.nyx.media.MediaObjectResolver;
import com.nyx.media.MediaObjectService;
import com.nyx.media.ThumbnailService;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibrarySourceRootWriteRequest;
import com.nyx.media.contracts.LibraryType;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.transcode.JobRepository;
import com.nyx.transcode.SegmentCache;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeJob;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminRoutesTest {
    private Path tempDir;
    private Path mediaDir;
    private Path libraryRoot;
    private int dbCounter;
    private ExecutorService backgroundExecutor;
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-admin-routes");
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
        libraryRoot = Files.createDirectories(tempDir.resolve("library-root"));
        dbCounter = 0;
        backgroundExecutor = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void teardown() throws Exception {
        backgroundExecutor.shutdownNow();
        for (HikariDataSource dataSource : dataSources) {
            dataSource.close();
        }
        dataSources.clear();
        deleteRecursively(tempDir);
    }

    private DataSource createDb() {
        dbCounter += 1;
        Path dbDir = createDirectories(tempDir.resolve("db" + dbCounter));
        Path dbPath = dbDir.resolve("test.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbPath + "?journal_mode=wal");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        HikariDataSource dataSource = new HikariDataSource(config);
        dataSources.add(dataSource);
        return dataSource;
    }

    private TestServices createServices() {
        InMemoryStorageBackend backend = new InMemoryStorageBackend();
        ThumbnailService thumbnailService = new ThumbnailService(Set.of(150, 300, 600), backend);
        SegmentCache segmentCache = new SegmentCache();
        MetricsService metricsService = new MetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        return new TestServices(
            thumbnailService,
            segmentCache,
            Map.of("test", createDb()),
            metricsService,
            backend,
            null,
            null
        );
    }

    private LibraryServices createLibraryServices() {
        return createLibraryServices(true);
    }

    private LibraryServices createLibraryServices(boolean wireDerivedStateIntoScan) {
        var libraryResources = LibraryService.createDatabase(tempDir.resolve("libraries-db"));
        var mediaResources = MediaObjectService.createDatabase(tempDir.resolve("media-objects-db"));
        dataSources.add(libraryResources.getDataSource());
        dataSources.add(mediaResources.getDataSource());

        MediaObjectService mediaObjectService = new MediaObjectService(mediaResources.getJdbi());
        MediaObjectResolver resolver = new MediaObjectResolver(
            mediaObjectService,
            new ProbeService(),
            new AudioMetadataService(new ProbeService())
        );
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryInterpretationService interpretationService = new LibraryInterpretationService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService
        );
        LibraryCatalogService catalogService = new LibraryCatalogService(libraryResources.getJdbi(), libraryService);
        LibraryExtensionCoordinator extensionCoordinator = new LibraryExtensionCoordinator(libraryService, catalogService);
        LibraryScanService scanService = new LibraryScanService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService,
            resolver,
            backgroundExecutor,
            wireDerivedStateIntoScan ? interpretationService : null,
            wireDerivedStateIntoScan ? catalogService : null,
            wireDerivedStateIntoScan ? extensionCoordinator : null
        );
        return new LibraryServices(
            libraryService,
            interpretationService,
            catalogService,
            extensionCoordinator,
            scanService,
            new LibraryAdminService(
                libraryService,
                scanService,
                interpretationService,
                catalogService,
                extensionCoordinator
            )
        );
    }

    private void installAdminRoutes(AdminApiTestSupport.ApplicationHarness app, TestServices services) {
        installAdminRoutes(app, services, List.of(mediaDir), null, null, null, List.of(), "local");
    }

    private void installAdminRoutes(
        AdminApiTestSupport.ApplicationHarness app,
        TestServices services,
        List<Path> mediaRoots,
        VirtualPathResolver virtualPathResolver,
        QuotaService quotaService,
        BackupService backupService,
        List<String> authProviders,
        String storageBackendType
    ) {
        app.installContentNegotiation(json);
        app.installStatusPages();
        if (authProviders.contains("api-token")) {
            app.installAuthentication(authentication ->
                authentication.bearer("api-token", bearer ->
                    bearer.authenticate(credential -> new UserIdPrincipal(credential.token()))
                )
            );
        }
        app.routing(route -> AdminFixtures.adminRoutes(
            route,
            services.thumbnailService(),
            services.segmentCache(),
            services.databases(),
            mediaRoots,
            services.metricsService(),
            virtualPathResolver,
            quotaService,
            backupService,
            services.libraryScanService(),
            services.libraryAdminService(),
            authProviders,
            storageBackendType
        ));
    }

    private BackupService createBackupService() throws Exception {
        Path backupDbDir = createDirectories(tempDir.resolve("backup-db-" + dbCounter++));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + backupDbDir.resolve("t.db"));
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        HikariDataSource dataSource = new HikariDataSource(config);
        dataSources.add(dataSource);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS x (id INTEGER PRIMARY KEY)");
        }
        return AdminFixtures.newBackupService(
            Map.of("t", dataSource),
            AdminFixtures.testBackupConfig(true, "", 0, 5),
            backupDbDir
        );
    }

    @Test
    void cacheRoutesReportStatsAndPurgeThumbnailAndSegmentCaches() throws Exception {
        TestServices services = createServices();
        Path segment = tempDir.resolve("seg1.ts");
        Files.createFile(segment);
        services.segmentCache().register(segment, "job-1");
        services.storageBackend().write("thumbnails/abc/300.jpg", "data".getBytes(), null, Map.of());

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services);

            try (var stats = app.client().get("/api/v1/admin/cache/stats")) {
                assertEquals(HttpStatusCode.OK, status(stats));
                JsonNode body = json.readTree(bodyAsText(stats));
                assertEquals(1, body.get("segmentCacheEntries").asInt());
                assertFalse(body.get("storageBackend").asText().isBlank());
            }

            try (var purgeThumbs = app.client().delete("/api/v1/admin/cache/thumbnails")) {
                assertEquals(HttpStatusCode.NoContent, status(purgeThumbs));
            }
            assertFalse(services.storageBackend().exists("thumbnails/abc/300.jpg"));

            try (var purgeSegments = app.client().delete("/api/v1/admin/cache/segments")) {
                assertEquals(HttpStatusCode.NoContent, status(purgeSegments));
            }
            assertEquals(0, services.segmentCache().entryCount());
        });
    }

    @Test
    void dbVacuumReturnsAllDatabaseNames() throws Exception {
        ThumbnailService thumbnailService = new ThumbnailService(Set.of(150, 300, 600), new InMemoryStorageBackend());
        SegmentCache segmentCache = new SegmentCache();
        MetricsService metricsService = new MetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        TestServices services = new TestServices(
            thumbnailService,
            segmentCache,
            Map.of("db1", createDb(), "db2", createDb()),
            metricsService,
            new InMemoryStorageBackend(),
            null,
            null
        );

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services);
            try (var response = app.client().post("/api/v1/admin/db/vacuum")) {
                assertEquals(HttpStatusCode.OK, status(response));
                JsonNode databases = json.readTree(bodyAsText(response)).get("databases");
                assertEquals(Set.of("db1", "db2"), Set.of(databases.get(0).asText(), databases.get(1).asText()));
            }
        });
    }

    @Test
    void storageRoutesReportExistingRootsAndSkipMissingOnes() throws Exception {
        TestServices services = createServices();
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaDir)));

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services, List.of(mediaDir), resolver, null, null, List.of(), "local");
            try (var response = app.client().get("/api/v1/admin/storage")) {
                assertEquals(HttpStatusCode.OK, status(response));
                JsonNode body = json.readTree(bodyAsText(response));
                assertEquals(1, body.size());
                JsonNode storage = body.get(0);
                assertEquals("media", storage.get("path").asText());
                assertTrue(storage.get("totalBytes").asLong() > 0);
            }
        });

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services, List.of(tempDir.resolve("does-not-exist")), null, null, null, List.of(), "local");
            try (var response = app.client().get("/api/v1/admin/storage")) {
                assertEquals(HttpStatusCode.OK, status(response));
                assertEquals(0, json.readTree(bodyAsText(response)).size());
            }
        });
    }

    @Test
    void metricsEndpointPresenceDependsOnMetricsService() throws Exception {
        TestServices withMetrics = createServices();
        TestServices withoutMetrics = new TestServices(
            new ThumbnailService(Set.of(150, 300, 600), new InMemoryStorageBackend()),
            new SegmentCache(),
            Map.of("test", createDb()),
            null,
            new InMemoryStorageBackend(),
            null,
            null
        );
        withMetrics.metricsService().getTranscodeSubmitted().increment();

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, withMetrics);
            try (var response = app.client().get("/api/v1/admin/metrics")) {
                assertEquals(HttpStatusCode.OK, status(response));
                assertTrue(bodyAsText(response).contains("nyx_transcode_jobs_submitted_total"));
            }
        });

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, withoutMetrics);
            try (var stats = app.client().get("/api/v1/admin/cache/stats")) {
                assertEquals(HttpStatusCode.OK, status(stats));
            }
            try (var response = app.client().get("/api/v1/admin/metrics")) {
                assertEquals(HttpStatusCode.NotFound, status(response));
            }
        });
    }

    @Test
    void quotaRoutesReportUsageAndStorageFieldsAndHandleUnknownUsers() throws Exception {
        Path quotaDbDir = createDirectories(tempDir.resolve("quota-db"));
        var resources = JobRepository.createDatabase(quotaDbDir);
        dataSources.add(resources.getDataSource());
        JobRepository jobRepository = new JobRepository(resources.getJdbi());
        QuotaService quotaService = new QuotaService(
            AdminFixtures.testQuotaConfig(true, 4, 60, 10_000_000L, Map.of()),
            jobRepository::countActiveByOwner,
            jobRepository::sumStorageByOwner,
            Set.of("alice", "bob")
        );
        jobRepository.create(new TranscodeJob("q1", JobStatus.QUEUED, "/test.mkv", "h264_fast", StreamRepresentation.DASH_FMP4, List.of(), null, null, 0, 0, null, null, null, null, null, null, null, null, null, "alice", 0L));
        jobRepository.updateStatus("q1", JobStatus.PROBING);
        jobRepository.updateStatus("q1", JobStatus.TRANSCODING);
        jobRepository.updateStatus("q1", JobStatus.COMPLETED);
        jobRepository.updateOutputSize("q1", 500_000L);

        TestServices services = createServices();
        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services, List.of(mediaDir), null, quotaService, null, List.of(), "local");

            try (var response = app.client().get("/api/v1/admin/users/alice/quota")) {
                assertEquals(HttpStatusCode.OK, status(response));
                JsonNode body = json.readTree(bodyAsText(response));
                assertEquals("alice", body.get("userId").asText());
                assertEquals(0, body.get("activeJobs").asInt());
                assertEquals(4, body.get("maxConcurrentJobs").asInt());
                assertEquals(60, body.get("maxRequestsPerMinute").asInt());
                assertEquals(500_000L, body.get("storageUsedBytes").asLong());
                assertEquals(10_000_000L, body.get("maxStorageBytes").asLong());
            }

            try (var response = app.client().get("/api/v1/admin/users/nobody/quota")) {
                assertEquals(HttpStatusCode.NotFound, status(response));
                assertTrue(bodyAsText(response).contains("USER_NOT_FOUND"));
            }
        });

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services);
            try (var response = app.client().get("/api/v1/admin/users/alice/quota")) {
                assertEquals(HttpStatusCode.NotFound, status(response));
            }
        });
    }

    @Test
    void adminRoutesRequireBearerAuthWhenConfigured() throws Exception {
        TestServices services = createServices();

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services, List.of(mediaDir), null, null, null, List.of("api-token"), "local");

            try (var response = app.client().get("/api/v1/admin/cache/stats")) {
                assertEquals(HttpStatusCode.Unauthorized, status(response));
            }

            try (var response = app.client().get("/api/v1/admin/cache/stats", builder ->
                builder.header(HttpHeaders.Authorization, "Bearer test-token")
            )) {
                assertEquals(HttpStatusCode.OK, status(response));
            }
        });
    }

    @Test
    void backupRoutesSupportHappyPathConflictAndStatus() throws Exception {
        TestServices services = createServices();
        BackupService backupService = createBackupService();

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services, List.of(mediaDir), null, null, backupService, List.of(), "local");

            try (var response = app.client().get("/api/v1/admin/backup/status")) {
                assertEquals(HttpStatusCode.OK, status(response));
                JsonNode body = json.readTree(bodyAsText(response));
                assertEquals(0L, body.get("lastBackupBytes").asLong());
                assertEquals(0L, body.get("successCount").asLong());
            }

            try (var response = app.client().post("/api/v1/admin/backup")) {
                assertEquals(HttpStatusCode.OK, status(response));
                JsonNode body = json.readTree(bodyAsText(response));
                assertTrue(body.get("totalBytes").asLong() > 0);
            }

            try (var response = app.client().get("/api/v1/admin/backup/status")) {
                assertEquals(HttpStatusCode.OK, status(response));
                JsonNode body = json.readTree(bodyAsText(response));
                assertTrue(body.get("lastBackupBytes").asLong() > 0);
                assertEquals(1L, body.get("successCount").asLong());
                assertNotNull(body.get("lastBackupTimestamp"));
            }

            Field field = BackupService.class.getDeclaredField("lock");
            field.setAccessible(true);
            ReentrantLock lock = (ReentrantLock) field.get(backupService);
            lock.lock();
            try (var response = app.client().post("/api/v1/admin/backup")) {
                assertEquals(HttpStatusCode.Conflict, status(response));
                JsonNode error = json.readTree(bodyAsText(response)).get("error");
                assertEquals("BACKUP_IN_PROGRESS", error.get("code").asText());
            } finally {
                lock.unlock();
            }
        });

        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services);
            try (var response = app.client().post("/api/v1/admin/backup")) {
                assertEquals(HttpStatusCode.NotFound, status(response));
            }
        });
    }

    @Test
    void libraryScanImportFlowExposesCompletedRun() throws Exception {
        TestServices baseServices = createServices();
        LibraryServices libraryServices = createLibraryServices();
        Path video = createDirectories(libraryRoot).resolve("movie.mp4");
        Files.write(video, new byte[2_048]);
        Library library = libraryServices.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Movies",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(libraryRoot.toString()))
            )
        );

        TestServices services = baseServices.withLibraryServices(libraryServices.scanService(), libraryServices.adminService());
        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services);
            try (var queue = app.client().post("/api/v1/admin/libraries/" + library.libraryId() + "/scans", builder -> {
                builder.contentType(ContentType.Application.getJson());
                builder.setBody("{\"mode\":\"IMPORT\"}");
            })) {
                assertEquals(HttpStatusCode.Accepted, status(queue));
            }

            JsonNode latestRun = awaitLatestRun(app, library.libraryId(), LibraryScanRunStatus.COMPLETED.name());
            assertEquals(LibraryScanRunStatus.COMPLETED.name(), latestRun.get("status").asText());
            assertEquals(1, latestRun.get("importedCount").asInt());
            assertEquals(1, latestRun.get("filesScanned").asInt());
            long activeCount = libraryServices.scanService().listTrackedObjects(library.libraryId()).stream()
                .filter(object -> object.getStatus() == LibraryTrackedObjectStatus.ACTIVE)
                .count();
            assertEquals(1L, activeCount);
        });
    }

    @Test
    void libraryScanFailureAndRefreshFlowsRemainVisible() throws Exception {
        TestServices baseServices = createServices();
        LibraryServices libraryServices = createLibraryServices();

        Path missingRoot = tempDir.resolve("missing-library-root");
        Library failedLibrary = libraryServices.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Photos",
                LibraryType.PHOTO,
                List.of(new LibrarySourceRootWriteRequest(missingRoot.toString()))
            )
        );

        Path keepFile = libraryRoot.resolve("keep.mp4");
        Path staleFile = libraryRoot.resolve("stale.mp4");
        Files.write(keepFile, new byte[2_048]);
        Files.write(staleFile, new byte[2_048]);
        Library refreshLibrary = libraryServices.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Refresh",
                LibraryType.MOVIE,
                List.of(new LibrarySourceRootWriteRequest(libraryRoot.toString()))
            )
        );
        libraryServices.scanService().runScanNow(refreshLibrary.libraryId(), LibraryScanMode.IMPORT);
        Files.delete(staleFile);
        Path addedFile = libraryRoot.resolve("added.mp4");
        Files.write(addedFile, new byte[2_048]);

        TestServices services = baseServices.withLibraryServices(libraryServices.scanService(), libraryServices.adminService());
        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services);

            try (var queue = app.client().post("/api/v1/admin/libraries/" + failedLibrary.libraryId() + "/scans", builder -> {
                builder.contentType(ContentType.Application.getJson());
                builder.setBody("{\"mode\":\"IMPORT\"}");
            })) {
                assertEquals(HttpStatusCode.Accepted, status(queue));
            }
            JsonNode failedRun = awaitLatestRun(app, failedLibrary.libraryId(), LibraryScanRunStatus.FAILED.name());
            assertTrue(failedRun.get("errorMessage").asText().contains("not found"));

            try (var queue = app.client().post("/api/v1/admin/libraries/" + refreshLibrary.libraryId() + "/scans", builder -> {
                builder.contentType(ContentType.Application.getJson());
                builder.setBody("{\"mode\":\"REFRESH\"}");
            })) {
                assertEquals(HttpStatusCode.Accepted, status(queue));
            }
            JsonNode refreshRun = awaitLatestRun(app, refreshLibrary.libraryId(), LibraryScanRunStatus.COMPLETED.name());
            assertEquals("REFRESH", refreshRun.get("mode").asText());
            assertEquals(0, refreshRun.get("importedCount").asInt());
            assertEquals(1, refreshRun.get("refreshedCount").asInt());
            assertEquals(1, refreshRun.get("missingCount").asInt());

            long activeCount = libraryServices.scanService().listTrackedObjects(refreshLibrary.libraryId()).stream()
                .filter(object -> object.getStatus() == LibraryTrackedObjectStatus.ACTIVE)
                .count();
            long missingCount = libraryServices.scanService().listTrackedObjects(refreshLibrary.libraryId()).stream()
                .filter(object -> object.getStatus() == LibraryTrackedObjectStatus.MISSING)
                .count();
            long addedCount = libraryServices.scanService().listTrackedObjects(refreshLibrary.libraryId()).stream()
                .filter(object -> object.getPrimaryPath().equals(addedFile.toString()))
                .count();
            assertEquals(2L, activeCount);
            assertEquals(0L, missingCount);
            assertEquals(0L, addedCount);
        });
    }

    @Test
    void libraryDiagnosticsExposeOrphansAndRepairRouteRebuildsDerivedState() throws Exception {
        TestServices baseServices = createServices();
        LibraryServices libraryServices = createLibraryServices(false);
        Path unmatchedFile = libraryRoot.resolve("bonus-featurette.mkv");
        Files.write(unmatchedFile, new byte[2_048]);
        Library library = libraryServices.libraryService().createLibrary(
            new CreateLibraryRequest(
                "Shows",
                LibraryType.SHOW,
                List.of(new LibrarySourceRootWriteRequest(libraryRoot.toString()))
            )
        );
        libraryServices.scanService().runScanNow(library.libraryId(), LibraryScanMode.IMPORT);

        TestServices services = baseServices.withLibraryServices(libraryServices.scanService(), libraryServices.adminService());
        AdminApiTestSupport.testApplication(app -> {
            installAdminRoutes(app, services);

            try (var before = app.client().get("/api/v1/admin/libraries/" + library.libraryId() + "/diagnostics")) {
                assertEquals(HttpStatusCode.OK, status(before));
                JsonNode body = json.readTree(bodyAsText(before));
                assertEquals(1, body.get("trackedObjects").get("orphaned").asInt());
                assertEquals(0, body.get("items").get("total").asInt());
                assertEquals(0, body.get("unmatchedItems").size());
            }

            try (var repair = app.client().post("/api/v1/admin/libraries/" + library.libraryId() + "/repairs/rebuild-derived-state")) {
                assertEquals(HttpStatusCode.OK, status(repair));
                assertEquals(1, json.readTree(bodyAsText(repair)).get("itemCount").asInt());
            }

            try (var after = app.client().get("/api/v1/admin/libraries/" + library.libraryId() + "/diagnostics")) {
                assertEquals(HttpStatusCode.OK, status(after));
                JsonNode body = json.readTree(bodyAsText(after));
                assertEquals(0, body.get("trackedObjects").get("orphaned").asInt());
                assertEquals(1, body.get("items").get("unmatched").asInt());
                assertTrue(body.get("unmatchedItems").get(0).get("unmatchedReason").asText().contains("season or episode pattern missing"));
            }
        });
    }

    private JsonNode awaitLatestRun(
        AdminApiTestSupport.ApplicationHarness app,
        String libraryId,
        String expectedStatus
    ) throws Exception {
        JsonNode latestRun = null;
        for (int attempt = 0; attempt < 40; attempt += 1) {
            try (var response = app.client().get("/api/v1/admin/libraries/" + libraryId + "/scans")) {
                assertEquals(HttpStatusCode.OK, status(response));
                JsonNode runs = json.readTree(bodyAsText(response));
                latestRun = runs.size() > 0 ? runs.get(0) : null;
                if (latestRun != null && expectedStatus.equals(latestRun.get("status").asText())) {
                    return latestRun;
                }
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("Timed out waiting for scan status " + expectedStatus + " for library " + libraryId + ": " + latestRun);
    }

    private static Path createDirectories(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create directories for " + path, exception);
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

    private record TestServices(
        ThumbnailService thumbnailService,
        SegmentCache segmentCache,
        Map<String, DataSource> databases,
        MetricsService metricsService,
        InMemoryStorageBackend storageBackend,
        LibraryScanService libraryScanService,
        LibraryAdminService libraryAdminService
    ) {
        private TestServices withLibraryServices(LibraryScanService scanService, LibraryAdminService adminService) {
            return new TestServices(
                thumbnailService,
                segmentCache,
                databases,
                metricsService,
                storageBackend,
                scanService,
                adminService
            );
        }
    }

    private record LibraryServices(
        LibraryService libraryService,
        LibraryInterpretationService interpretationService,
        LibraryCatalogService catalogService,
        LibraryExtensionCoordinator extensionCoordinator,
        LibraryScanService scanService,
        LibraryAdminService adminService
    ) {
    }
}
