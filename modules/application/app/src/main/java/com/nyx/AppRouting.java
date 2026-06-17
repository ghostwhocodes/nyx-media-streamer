package com.nyx;

import com.nyx.admin.BackupService;
import com.nyx.admin.HealthService;
import com.nyx.admin.MetricsService;
import com.nyx.common.ManagedService;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.common.storage.StorageBackend;
import com.nyx.config.ConfigBindings;
import com.nyx.config.ConfigModule;
import com.nyx.config.ServerConfig;
import com.nyx.di.FfmpegBindings;
import com.nyx.di.StorageBackendFactory;
import com.nyx.eforms.EFormsBindings;
import com.nyx.eforms.EFormsModule;
import com.nyx.ffmpeg.model.TranscodeProfiles;
import com.nyx.http.AuthMode;
import com.nyx.http.OpenApiRegistry;
import com.nyx.http.Route;
import com.nyx.media.MediaBindings;
import com.nyx.media.MediaModule;
import com.nyx.playback.PlaybackRuntimeBindings;
import com.nyx.playback.PlaybackRuntimeModule;
import com.nyx.transcode.TranscodeBindings;
import com.nyx.transcode.TranscodeModule;
import com.nyx.transcode.TranscodePersistenceBindings;
import com.nyx.transcode.TranscodePersistenceSqliteModule;
import io.javalin.Javalin;
import io.javalin.config.Key;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppRouting {
    public static final Key<Map<String, DataSource>> SHARED_DATABASES_KEY = new Key<>("shared-databases");

    private AppRouting() {
    }

    public static Javalin createApplication(
        ServerConfig config,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        requireCompatibilityListenersDisabled(config);
        PreparedApplication prepared = prepareApplication(config, runtimeUsers, false);
        registerShutdownHook(prepared.mainApp(), prepared.shutdownAction());
        return prepared.mainApp();
    }

    public static ApplicationServerGroup createApplicationServerGroup(
        ServerConfig config,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        PreparedApplication prepared = prepareApplication(config, runtimeUsers, true);
        return new ApplicationServerGroup(
            prepared.mainApp(),
            prepared.compatibilityServers(),
            prepared.shutdownAction()
        );
    }

    public static ApplicationServerGroup createApplicationServerGroup(ServerConfig config) {
        return createApplicationServerGroup(config, new ConcurrentHashMap<>(config.getAuth().getUsers()));
    }

    public static Javalin createApplication(ServerConfig config) {
        return createApplication(config, new ConcurrentHashMap<>(config.getAuth().getUsers()));
    }

    static void validateStartupConfig(ServerConfig config) {
        List<String> invalidPresets = new ArrayList<>();
        for (Map.Entry<String, String> entry : config.getFfmpeg().getQualityPresets().entrySet()) {
            if (TranscodeProfiles.findByName(entry.getValue()) == null) {
                invalidPresets.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        if (!invalidPresets.isEmpty()) {
            throw new IllegalStateException(
                "Invalid quality preset profile(s): " + invalidPresets + ". Known profiles: " + TranscodeProfiles.allNames()
            );
        }

        if (config.getBackup().getEnabled() && config.getBackup().getRetainCount() < 1) {
            throw new IllegalStateException("backup.retain_count=" + config.getBackup().getRetainCount() + " must be >= 1");
        }

        if (config.getQuota().getEnabled() && !config.getAuth().getEnabled()) {
            throw new IllegalStateException(
                "quota.enabled=true requires auth.enabled=true because per-user quotas need authenticated user identity"
            );
        }

        if (config.getQuota().getEnabled()) {
            List<String> errors = new ArrayList<>();
            if (config.getQuota().getDefaultMaxConcurrentJobs() < 1) {
                errors.add(
                    "default_max_concurrent_jobs=" + config.getQuota().getDefaultMaxConcurrentJobs() + " must be >= 1"
                );
            }
            if (config.getQuota().getDefaultMaxRequestsPerMinute() < 1) {
                errors.add(
                    "default_max_requests_per_minute=" + config.getQuota().getDefaultMaxRequestsPerMinute() + " must be >= 1"
                );
            }
            if (config.getQuota().getDefaultMaxStorageBytes() < 1) {
                errors.add(
                    "default_max_storage_bytes=" + config.getQuota().getDefaultMaxStorageBytes() + " must be >= 1"
                );
            }
            config.getQuota().getUserOverrides().forEach((userId, overrideConfig) -> {
                if (overrideConfig.getMaxConcurrentJobs() != null && overrideConfig.getMaxConcurrentJobs() < 1) {
                    errors.add(
                        "user_overrides." + userId + ".max_concurrent_jobs=" + overrideConfig.getMaxConcurrentJobs()
                            + " must be >= 1"
                    );
                }
                if (overrideConfig.getMaxRequestsPerMinute() != null && overrideConfig.getMaxRequestsPerMinute() < 1) {
                    errors.add(
                        "user_overrides." + userId + ".max_requests_per_minute=" + overrideConfig.getMaxRequestsPerMinute()
                            + " must be >= 1"
                    );
                }
                if (overrideConfig.getMaxStorageBytes() != null && overrideConfig.getMaxStorageBytes() < 1) {
                    errors.add(
                        "user_overrides." + userId + ".max_storage_bytes=" + overrideConfig.getMaxStorageBytes()
                            + " must be >= 1"
                    );
                }
            });
            if (!errors.isEmpty()) {
                throw new IllegalStateException("Invalid quota configuration: " + String.join("; ", errors));
            }
        }

        if (config.getAuth().getEnabled()) {
            List<String> invalidUserIds = new ArrayList<>();
            config.getAuth().getTokens().forEach((token, userId) -> {
                if (userId.isBlank() || userId.length() > 128 || userId.chars().anyMatch(Character::isISOControl)) {
                    invalidUserIds.add(token + " → '" + userId + "'");
                }
            });
            if (!invalidUserIds.isEmpty()) {
                throw new IllegalStateException(
                    "Invalid auth token userId(s): " + invalidUserIds
                        + ". User IDs must be non-blank, <=128 chars, and contain no control characters."
                );
            }
        }

        validateCompatibilityConfig(config);
    }

    private static PreparedApplication prepareApplication(
        ServerConfig config,
        ConcurrentHashMap<String, String> runtimeUsers,
        boolean includeCompatibilityServers
    ) {
        Logger log = LoggerFactory.getLogger("com.nyx.AppRouting");
        validateStartupConfig(config);

        AppComponent appComponent = DaggerAppComponent.factory().create(config, runtimeUsers);
        AppRuntimeDependencies dependencies = createRuntimeDependencies(
            config,
            runtimeUsers,
            appComponent.cleanupScheduler(),
            appComponent.backgroundExecutor(),
            appComponent.metricsRegistry(),
            appComponent.metricsService()
        );
        Runnable shutdownAction = shutdownAction(dependencies, log);
        return runStartupWithFailureCleanup(() -> {
                DomainRouteDependencies domainRouteDependencies = resolveDomainRouteDependencies(
                    dependencies,
                    config,
                    runtimeUsers
                );
                Javalin mainApp = createMainApplication(config, runtimeUsers, dependencies, domainRouteDependencies);
                List<CompatibilityShimServer> compatibilityServers = includeCompatibilityServers
                    ? createCompatibilityServers(config, runtimeUsers, dependencies, domainRouteDependencies)
                    : List.of();
                dependencies.configBindings().configService().initialize();
                configureMetrics(dependencies, config);
                runStartupHealthChecks(dependencies.healthService(), log);
                return new PreparedApplication(mainApp, compatibilityServers, shutdownAction);
            }, shutdownAction
        );
    }

    private static Javalin createMainApplication(
        ServerConfig config,
        ConcurrentHashMap<String, String> runtimeUsers,
        AppRuntimeDependencies dependencies,
        DomainRouteDependencies domainRouteDependencies
    ) {
        ApplicationRuntime.JavalinAppRuntime javalinRuntime = ApplicationRuntime.createConfiguredApp(
            config,
            config.getAuth(),
            dependencies.metricsRegistry(),
            runtimeUsers
        );
        Javalin app = javalinRuntime.app();
        OpenApiRegistry openApiRegistry = new OpenApiRegistry();
        Route route = new Route(
            app,
            "",
            AuthMode.PUBLIC,
            List.of(),
            openApiRegistry,
            javalinRuntime.authEvaluator()
        );

        app.unsafe.appData(SHARED_DATABASES_KEY, databaseRegistry(dependencies));
        AppOperationalRoutes.installOperationalRoutes(
            route,
            dependencies.healthService(),
            dependencies.metricsService(),
            dependencies.backupService(),
            openApiRegistry
        );
        AppOperationalRoutes.installDomainRoutes(route, domainRouteDependencies);
        return app;
    }

    private static List<CompatibilityShimServer> createCompatibilityServers(
        ServerConfig config,
        ConcurrentHashMap<String, String> runtimeUsers,
        AppRuntimeDependencies dependencies,
        DomainRouteDependencies domainRouteDependencies
    ) {
        var qloud = config.getCompatibility().getQloud();
        if (!qloud.getEnabled()) {
            return List.of();
        }

        ApplicationRuntime.JavalinAppRuntime javalinRuntime = ApplicationRuntime.createConfiguredApp(
            config,
            qloud.getHost(),
            qloud.getPort(),
            false,
            config.getAuth(),
            dependencies.metricsRegistry(),
            runtimeUsers
        );
        Javalin app = javalinRuntime.app();
        Route route = new Route(
            app,
            "",
            AuthMode.PUBLIC,
            List.of(),
            new OpenApiRegistry(),
            javalinRuntime.authEvaluator()
        );
        AppOperationalRoutes.installQloudCompatibilityRoutes(route, domainRouteDependencies, qloud);
        return List.of(new CompatibilityShimServer(
            "qloud",
            qloud.getHost(),
            qloud.getPort(),
            app
        ));
    }

    private static void validateCompatibilityConfig(ServerConfig config) {
        var qloud = config.getCompatibility().getQloud();
        if (!qloud.getEnabled()) {
            return;
        }

        String host = qloud.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("compatibility.qloud.host must not be blank when Qloud compatibility is enabled");
        }

        int port = qloud.getPort();
        if (port < 0 || port > 65_535) {
            throw new IllegalStateException("compatibility.qloud.port=" + port + " must be between 0 and 65535");
        }

        if (port != 0 && port == config.getPort()) {
            throw new IllegalStateException(
                "compatibility.qloud.port=" + port + " must not match server.port=" + config.getPort()
            );
        }

        if (config.getTls().getEnabled() && port != 0 && port == config.getTls().getPort()) {
            throw new IllegalStateException(
                "compatibility.qloud.port=" + port + " must not match tls.port=" + config.getTls().getPort()
            );
        }
    }

    private static void requireCompatibilityListenersDisabled(ServerConfig config) {
        if (!config.getCompatibility().getQloud().getEnabled()) {
            return;
        }
        throw new IllegalStateException(
            "Compatibility listeners are enabled. Use AppRouting.createApplicationServerGroup(...) so dedicated"
                + " compatibility listener lifecycles start and stop with the main app."
        );
    }

    private static void checkpointWal(DataSource database) {
        try (var connection = database.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to checkpoint WAL", exception);
        }
    }

    static AppRuntimeDependencies createRuntimeDependencies(
        ServerConfig serverConfig,
        ConcurrentHashMap<String, String> runtimeUsers,
        ScheduledExecutorService cleanupScheduler,
        ExecutorService backgroundExecutor,
        PrometheusMeterRegistry metricsRegistry,
        MetricsService metricsService
    ) {
        PathSecurity pathSecurity = new PathSecurity(serverConfig.getMediaRoots().stream().map(root -> root.getPath()).toList());
        VirtualPathResolver virtualPathResolver = new VirtualPathResolver(serverConfig.getMediaRoots());
        StorageBackend storageBackend = StorageBackendFactory.create(serverConfig.getStorage());
        ConfigBindings configBindings = ConfigModule.createConfigBindings(serverConfig, runtimeUsers);
        Semaphore ffmpegSemaphore = new Semaphore(serverConfig.getFfmpeg().getMaxConcurrentMediaProcesses());
        FfmpegBindings ffmpegBindings = FfmpegBindings.createFfmpegBindings(
            serverConfig.getFfmpeg().getPath(),
            serverConfig.getFfmpeg().getFfprobePath(),
            ffmpegSemaphore,
            metricsService
        );
        TranscodePersistenceBindings transcodePersistenceBindings =
            TranscodePersistenceSqliteModule.createTranscodePersistenceBindings(serverConfig);
        TranscodeBindings transcodeBindings = TranscodeModule.createTranscodeBindings(
            serverConfig,
            pathSecurity,
            ffmpegBindings.getMediaProber(),
            transcodePersistenceBindings.jobStore(),
            metricsService,
            runtimeUsers,
            cleanupScheduler,
            backgroundExecutor,
            transcodePersistenceBindings.webhookStore()
        );
        HealthService healthService = new HealthService(
            serverConfig,
            transcodePersistenceBindings.jobStore(),
            () -> transcodeBindings.transcodeApplicationService().getCircuitBreakerOpen()
        );
        MediaBindings mediaBindings = MediaModule.createMediaBindings(
            serverConfig,
            pathSecurity,
            virtualPathResolver,
            storageBackend,
            backgroundExecutor,
            cleanupScheduler,
            ffmpegSemaphore,
            ffmpegBindings.getMediaProber(),
            ffmpegBindings.getVideoPreviewGenerator(),
            ffmpegBindings.getVideoTrickplayGenerator(),
            metricsService,
            healthService
        );
        PlaybackRuntimeBindings playbackRuntimeBindings = PlaybackRuntimeModule.createPlaybackRuntimeBindings(
            ffmpegBindings.getMediaProber(),
            transcodeBindings.transcodeApplicationService(),
            mediaBindings.getAudioNegotiationService(),
            mediaBindings.getMediaPlaystateProjector(),
            cleanupScheduler
        );
        AppRuntimeDependencies dependencies = new AppRuntimeDependencies(
            metricsRegistry,
            cleanupScheduler,
            backgroundExecutor,
            metricsService,
            pathSecurity,
            virtualPathResolver,
            storageBackend,
            configBindings,
            ffmpegBindings,
            transcodePersistenceBindings,
            transcodeBindings,
            EFormsModule.createEFormsBindings(serverConfig),
            mediaBindings,
            playbackRuntimeBindings,
            healthService,
            null
        );
        BackupService backupService = serverConfig.getBackup().getEnabled()
            ? new BackupService(
                databaseRegistry(dependencies),
                serverConfig.getBackup(),
                serverConfig.getDatabase().getDir(),
                metricsService,
                cleanupScheduler
            )
            : null;
        return dependencies.withBackupService(backupService);
    }

    private static Map<String, DataSource> databaseRegistry(AppRuntimeDependencies dependencies) {
        Map<String, DataSource> databases = new LinkedHashMap<>();
        databases.put("transcode", dependencies.transcodePersistenceBindings().jobResources().dataSource());
        databases.put("eforms", dependencies.eFormsBindings().getResources().getDataSource());
        databases.put("playlists", dependencies.mediaBindings().getPlaylistResources().getDataSource());
        databases.put("chapters", dependencies.mediaBindings().getChapterResources().getDataSource());
        databases.put("audio_probes", dependencies.mediaBindings().getFileProbeCacheResources().getDataSource());
        databases.put("media_objects", dependencies.mediaBindings().getMediaObjectResources().getDataSource());
        databases.put("libraries", dependencies.mediaBindings().getLibraryResources().getDataSource());
        databases.put("config", dependencies.configBindings().resources().dataSource());
        DataSource webhookDataSource = dependencies.transcodePersistenceBindings().webhookPersistenceResources().dataSource();
        if (webhookDataSource != null) {
            databases.put("webhooks", webhookDataSource);
        }
        return databases;
    }

    private static List<String> buildAuthProviders(
        ServerConfig serverConfig,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        List<String> providers = new ArrayList<>();
        if (serverConfig.getAuth().getEnabled()
            && (!serverConfig.getAuth().getToken().isBlank() || !serverConfig.getAuth().getTokens().isEmpty())) {
            providers.add("api-token");
        }
        if (serverConfig.getAuth().getEnabled() && !runtimeUsers.isEmpty()) {
            providers.add("api-basic");
        }
        return providers;
    }

    private static DomainRouteDependencies resolveDomainRouteDependencies(
        AppRuntimeDependencies dependencies,
        ServerConfig serverConfig,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        return new DomainRouteDependencies(
            dependencies.mediaBindings().getBrowseService(),
            dependencies.playbackRuntimeBindings().playbackSessionService(),
            dependencies.playbackRuntimeBindings().playbackDecisionService(),
            dependencies.playbackRuntimeBindings().playbackDeliveryService(),
            dependencies.playbackRuntimeBindings().mediaSessionReportService(),
            dependencies.pathSecurity(),
            dependencies.virtualPathResolver(),
            serverConfig,
            runtimeUsers,
            dependencies.cleanupScheduler(),
            buildAuthProviders(serverConfig, runtimeUsers),
            dependencies.transcodeBindings().quotaService(),
            dependencies.mediaBindings().getMediaObjectResolver(),
            dependencies.transcodeBindings().transcodeApplicationService(),
            dependencies.transcodeBindings().segmentCacheService(),
            dependencies.ffmpegBindings().getProbeService(),
            dependencies.ffmpegBindings().getSubtitleExtractor(),
            dependencies.healthService(),
            dependencies.eFormsBindings().getEFormService(),
            dependencies.eFormsBindings().getExportImportService(),
            dependencies.eFormsBindings().getRelocationService(),
            dependencies.mediaBindings().getMediaFileService(),
            dependencies.mediaBindings().getThumbnailService(),
            dependencies.mediaBindings().getExifExtractor(),
            dependencies.mediaBindings().getStrippedImageCache(),
            dependencies.mediaBindings().getImageTransformService(),
            dependencies.mediaBindings().getVideoPreviewService(),
            dependencies.mediaBindings().getVideoTrickplayService(),
            dependencies.mediaBindings().getMediaThumbnailService(),
            dependencies.mediaBindings().getMediaThumbnailLifecycle(),
            dependencies.mediaBindings().getLibraryService(),
            dependencies.mediaBindings().getLibraryCatalogService(),
            dependencies.mediaBindings().getLibraryUserStateService(),
            dependencies.mediaBindings().getMediaObjectService(),
            dependencies.mediaBindings().getUserMediaStateService(),
            dependencies.mediaBindings().getChapterService(),
            dependencies.mediaBindings().getAudioTranscoder(),
            dependencies.mediaBindings().getAudioNegotiationService(),
            dependencies.playbackRuntimeBindings().audioSessionService(),
            dependencies.mediaBindings().getPlaylistService(),
            dependencies.configBindings().configService(),
            dependencies.transcodePersistenceBindings().webhookStore(),
            dependencies.transcodeBindings().webhookResources().urlValidator(),
            dependencies.metricsService(),
            dependencies.backupService(),
            databaseRegistry(dependencies),
            serverConfig.getMediaRoots().stream().map(root -> root.getPath()).toList(),
            dependencies.mediaBindings().getLibraryScanService(),
            dependencies.mediaBindings().getLibraryAdminService(),
            serverConfig.getStorage().getBackend()
        );
    }

    private static void configureMetrics(AppRuntimeDependencies dependencies, ServerConfig serverConfig) {
        dependencies.metricsService().setSegmentCacheSizeSupplier(
            (Supplier<Integer>) () -> dependencies.transcodeBindings().segmentCache().entryCount()
        );
        dependencies.metricsService().setProbeCacheSizeSupplier(
            (Supplier<Integer>) () -> dependencies.ffmpegBindings().getProbeService().getCacheSize()
        );
        dependencies.metricsService().registerDiskSpaceGauges(
            serverConfig.getMediaRoots().stream().map(root -> root.getPath()).toList(),
            serverConfig.getDatabase().getDir()
        );
        if (dependencies.backupService() != null) {
            dependencies.metricsService().registerBackupGauges(dependencies.backupService());
        }
    }

    private static void runStartupHealthChecks(HealthService healthService, Logger log) {
        try {
            var report = healthService.runStartupChecks();
            log.info(
                "Startup health report: FFmpeg={}, HW accels={}, media roots={}",
                report.ffmpegVersion(),
                report.hwAccels(),
                report.mediaRoots().size()
            );
        } catch (Throwable error) {
            log.error("Startup health check failed: {}", error.getMessage(), error);
            throw error;
        }
    }

    static <T> T runStartupWithFailureCleanup(Supplier<T> startupSteps, Runnable shutdownAction) {
        try {
            return startupSteps.get();
        } catch (Throwable error) {
            try {
                shutdownAction.run();
            } catch (Throwable cleanupFailure) {
                error.addSuppressed(cleanupFailure);
            }
            throw error;
        }
    }

    static void runPostAllocationStartup(Runnable startupSteps, Runnable shutdownAction) {
        runStartupWithFailureCleanup(() -> {
            startupSteps.run();
            return null;
        }, shutdownAction);
    }

    private static List<ManagedService> managedServices(AppRuntimeDependencies dependencies) {
        List<ManagedService> managedServices = new ArrayList<>();
        if (dependencies.playbackRuntimeBindings().audioSessionService() instanceof ManagedService service) {
            managedServices.add(service);
        }
        if (dependencies.playbackRuntimeBindings().playbackDeliveryService() instanceof ManagedService service) {
            managedServices.add(service);
        }
        if (dependencies.transcodeBindings().segmentCache() instanceof ManagedService service) {
            managedServices.add(service);
        }
        if (dependencies.playbackRuntimeBindings().playbackSessionService() instanceof ManagedService service) {
            managedServices.add(service);
        }
        ManagedService webhookService = dependencies.transcodeBindings().webhookResources().service();
        if (webhookService != null) {
            managedServices.add(webhookService);
        }
        if (dependencies.backupService() != null) {
            managedServices.add(dependencies.backupService());
        }
        return managedServices;
    }

    private static void closePersistenceResources(AppRuntimeDependencies dependencies) {
        dependencies.transcodePersistenceBindings().jobResources().dataSource().close();
        dependencies.eFormsBindings().getResources().getDataSource().close();
        dependencies.mediaBindings().getPlaylistResources().getDataSource().close();
        dependencies.mediaBindings().getChapterResources().getDataSource().close();
        dependencies.mediaBindings().getFileProbeCacheResources().getDataSource().close();
        dependencies.mediaBindings().getMediaObjectResources().getDataSource().close();
        dependencies.mediaBindings().getLibraryResources().getDataSource().close();
        dependencies.configBindings().resources().dataSource().close();
        var webhookDataSource = dependencies.transcodePersistenceBindings().webhookPersistenceResources().dataSource();
        if (webhookDataSource != null) {
            webhookDataSource.close();
        }
    }

    static Runnable shutdownAction(AppRuntimeDependencies dependencies, Logger log) {
        List<ManagedService> managedServices = managedServices(dependencies);
        AtomicBoolean shutdown = new AtomicBoolean(false);
        return () -> {
            if (!shutdown.compareAndSet(false, true)) {
                return;
            }
            dependencies.transcodeBindings().managedTranscodeApplicationService().shutdown();
            managedServices.forEach(ManagedService::shutdown);
            databaseRegistry(dependencies).values().forEach(AppRouting::checkpointWal);
            dependencies.cleanupScheduler().shutdownNow();
            dependencies.backgroundExecutor().shutdownNow();
            dependencies.storageBackend().close();
            closePersistenceResources(dependencies);
            log.info("Graceful shutdown complete");
        };
    }

    private static void registerShutdownHook(Javalin app, Runnable shutdownAction) {
        AtomicBoolean shutdownPending = new AtomicBoolean(false);
        app.unsafe.events.serverStopping(() -> shutdownPending.set(true));
        app.unsafe.events.serverStopFailed(() -> shutdownPending.set(false));
        app.unsafe.events.serverStopped(() -> {
            if (shutdownPending.compareAndSet(true, false)) {
                shutdownAction.run();
            }
        });
    }

    record AppRuntimeDependencies(
        PrometheusMeterRegistry metricsRegistry,
        ScheduledExecutorService cleanupScheduler,
        ExecutorService backgroundExecutor,
        MetricsService metricsService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver,
        StorageBackend storageBackend,
        ConfigBindings configBindings,
        FfmpegBindings ffmpegBindings,
        TranscodePersistenceBindings transcodePersistenceBindings,
        TranscodeBindings transcodeBindings,
        EFormsBindings eFormsBindings,
        MediaBindings mediaBindings,
        PlaybackRuntimeBindings playbackRuntimeBindings,
        HealthService healthService,
        BackupService backupService
    ) {
        AppRuntimeDependencies withBackupService(BackupService backupService) {
            return new AppRuntimeDependencies(
                metricsRegistry,
                cleanupScheduler,
                backgroundExecutor,
                metricsService,
                pathSecurity,
                virtualPathResolver,
                storageBackend,
                configBindings,
                ffmpegBindings,
                transcodePersistenceBindings,
                transcodeBindings,
                eFormsBindings,
                mediaBindings,
                playbackRuntimeBindings,
                healthService,
                backupService
            );
        }
    }

    private record PreparedApplication(
        Javalin mainApp,
        List<CompatibilityShimServer> compatibilityServers,
        Runnable shutdownAction
    ) {
    }
}
