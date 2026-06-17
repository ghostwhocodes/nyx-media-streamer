package com.nyx.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.nyx.common.ManagedService;
import com.nyx.common.QuotaService;
import com.nyx.common.VirtualPathResolver;
import com.nyx.common.storage.InMemoryStorageBackend;
import com.nyx.config.ConfigService;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.ServerConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.http.OpenApiRegistry;
import com.nyx.http.Route;
import com.nyx.media.AudioMetadataService;
import com.nyx.media.LibraryAdminService;
import com.nyx.media.LibraryCatalogService;
import com.nyx.media.LibraryExtensionCoordinator;
import com.nyx.media.LibraryInterpretationService;
import com.nyx.media.LibraryScanService;
import com.nyx.media.LibraryService;
import com.nyx.media.MediaObjectResolver;
import com.nyx.media.MediaObjectService;
import com.nyx.media.ThumbnailService;
import com.nyx.transcode.SegmentCache;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.javalin.Javalin;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteOpenApiCoverageTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<ManagedService> managedServices = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        managedServices.reversed().forEach(ManagedService::shutdown);
        executors.reversed().forEach(ExecutorService::shutdownNow);
        dataSources.reversed().forEach(HikariDataSource::close);
        managedServices.clear();
        executors.clear();
        dataSources.clear();
    }

    @Test
    void adminRoutesRegisterOpenApiDocsForAllPublicEndpoints() throws Exception {
        OpenApiRegistry docsRegistry = new OpenApiRegistry();
        Javalin app = Javalin.create(config -> config.startup.showJavalinBanner = false);

        Path mediaRoot = Files.createDirectories(tempDir.resolve("media-root"));
        VirtualPathResolver virtualPathResolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaRoot, "local", "media")));
        ThumbnailService thumbnailService = new ThumbnailService(new InMemoryStorageBackend());
        SegmentCache segmentCache = new SegmentCache();
        Map<String, DataSource> databases = Map.of("admin", createDataSource("admin"));
        MetricsService metricsService = new MetricsService(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));
        QuotaService quotaService = new QuotaService(
            AdminFixtures.testQuotaConfig(true, 2, 30, 1_024L, Map.of()),
            ownerId -> 0,
            java.util.Set.of("user-1")
        );
        BackupService backupService = AdminFixtures.newBackupService(
            databases,
            AdminFixtures.testBackupConfig(false, "", 0, 1),
            Files.createDirectories(tempDir.resolve("backup-db")),
            metricsService
        );
        managedServices.add(backupService);

        LibraryServices libraryServices = createLibraryServices();
        ConfigService configService = new ConfigService(serverConfig(mediaRoot));

        Route route = new Route(app, docsRegistry);
        AdminFixtures.adminRoutes(
            route,
            thumbnailService,
            segmentCache,
            databases,
            List.of(mediaRoot),
            metricsService,
            virtualPathResolver,
            quotaService,
            backupService,
            libraryServices.scanService(),
            libraryServices.adminService(),
            List.of("api-token"),
            "memory"
        );
        AdminFixtures.configRoutes(route, configService, List.of("api-basic"));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>) docsRegistry.buildSpec(
            "Nyx Admin API",
            "test",
            "Coverage registration"
        ).get("paths");

        assertThat(paths.keySet()).contains(
            "/api/v1/admin/cache/thumbnails",
            "/api/v1/admin/cache/segments",
            "/api/v1/admin/cache/stats",
            "/api/v1/admin/db/vacuum",
            "/api/v1/admin/storage",
            "/api/v1/admin/metrics",
            "/api/v1/admin/users/{userId}/quota",
            "/api/v1/admin/backup",
            "/api/v1/admin/backup/status",
            "/api/v1/admin/libraries/{libraryId}/scans",
            "/api/v1/admin/libraries/{libraryId}/diagnostics",
            "/api/v1/admin/libraries/{libraryId}/repairs/rebuild-derived-state",
            "/api/v1/config",
            "/api/v1/auth/users",
            "/api/v1/auth/users/{username}"
        );

        assertThat(paths.get("/api/v1/admin/cache/thumbnails")).containsKeys("delete");
        assertThat(paths.get("/api/v1/admin/cache/segments")).containsKeys("delete");
        assertThat(paths.get("/api/v1/admin/cache/stats")).containsKeys("get");
        assertThat(paths.get("/api/v1/admin/db/vacuum")).containsKeys("post");
        assertThat(paths.get("/api/v1/admin/storage")).containsKeys("get");
        assertThat(paths.get("/api/v1/admin/metrics")).containsKeys("get");
        assertThat(paths.get("/api/v1/admin/users/{userId}/quota")).containsKeys("get");
        assertThat(paths.get("/api/v1/admin/backup")).containsKeys("post");
        assertThat(paths.get("/api/v1/admin/backup/status")).containsKeys("get");
        assertThat(paths.get("/api/v1/admin/libraries/{libraryId}/scans")).containsKeys("get", "post");
        assertThat(paths.get("/api/v1/admin/libraries/{libraryId}/diagnostics")).containsKeys("get");
        assertThat(paths.get("/api/v1/admin/libraries/{libraryId}/repairs/rebuild-derived-state")).containsKeys("post");
        assertThat(paths.get("/api/v1/config")).containsKeys("get", "put");
        assertThat(paths.get("/api/v1/auth/users")).containsKeys("get", "post");
        assertThat(paths.get("/api/v1/auth/users/{username}")).containsKeys("delete");
    }

    private DataSource createDataSource(String name) {
        Path dbPath = tempDir.resolve(name + ".db");
        HikariDataSource dataSource = new HikariDataSource(new HikariConfig() {{
            setJdbcUrl("jdbc:sqlite:" + dbPath + "?journal_mode=wal");
            setDriverClassName("org.sqlite.JDBC");
            setMaximumPoolSize(1);
        }});
        dataSources.add(dataSource);
        return dataSource;
    }

    private LibraryServices createLibraryServices() {
        var libraryResources = LibraryService.createDatabase(tempDir.resolve("libraries-db"));
        var mediaResources = MediaObjectService.createDatabase(tempDir.resolve("media-objects-db"));
        dataSources.add(libraryResources.getDataSource());
        dataSources.add(mediaResources.getDataSource());

        MediaObjectService mediaObjectService = new MediaObjectService(mediaResources.getJdbi());
        ProbeService probeService = new ProbeService();
        MediaObjectResolver mediaObjectResolver = new MediaObjectResolver(
            mediaObjectService,
            probeService,
            new AudioMetadataService(probeService)
        );
        LibraryService libraryService = new LibraryService(libraryResources.getJdbi());
        LibraryInterpretationService interpretationService = new LibraryInterpretationService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService
        );
        LibraryCatalogService catalogService = new LibraryCatalogService(libraryResources.getJdbi(), libraryService);
        LibraryExtensionCoordinator extensionCoordinator = new LibraryExtensionCoordinator(libraryService, catalogService);
        ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
        executors.add(backgroundExecutor);
        LibraryScanService scanService = new LibraryScanService(
            libraryResources.getJdbi(),
            libraryService,
            mediaObjectService,
            mediaObjectResolver,
            backgroundExecutor,
            interpretationService,
            catalogService,
            extensionCoordinator
        );
        return new LibraryServices(
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

    private ServerConfig serverConfig(Path mediaRoot) {
        return AdminFixtures.testServerConfig(
            List.of(new MediaRootConfig(mediaRoot, "local", "media")),
            new DatabaseConfig(tempDir.resolve("config-db")),
            AdminFixtures.testAuthConfig()
        );
    }

    private record LibraryServices(LibraryScanService scanService, LibraryAdminService adminService) {
    }
}
