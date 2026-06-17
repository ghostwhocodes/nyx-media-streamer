package com.nyx;

import com.nyx.admin.AdminRoutes;
import com.nyx.admin.BackupService;
import com.nyx.admin.ConfigRoutes;
import com.nyx.admin.HealthService;
import com.nyx.admin.LivenessResponse;
import com.nyx.admin.MetricsService;
import com.nyx.admin.ReadinessResponse;
import com.nyx.admin.RuntimeHealthResponse;
import com.nyx.browse.BrowseRoutes;
import com.nyx.eforms.EFormRoutes;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRegistry;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.Route;
import com.nyx.config.QloudCompatibilityConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.http.AuthMode;
import com.nyx.media.AudioRoutes;
import com.nyx.media.ChapterRoutes;
import com.nyx.media.ImageRoutes;
import com.nyx.media.LibraryRoutes;
import com.nyx.media.MediaObjectStateRoutes;
import com.nyx.playback.PlaybackRoutes;
import com.nyx.qloud.QloudCompatibilityRoutes;
import com.nyx.transcode.TranscodeRoutes;
import com.nyx.transcode.webhook.WebhookRoutes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;

public final class AppOperationalRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_SERVICE_UNAVAILABLE = HttpStatusCode.Companion.getServiceUnavailable();

    private AppOperationalRoutes() {
    }

    static void installDomainRoutes(Route route, DomainRouteDependencies dependencies) {
        installClientCapabilitiesRoute(route, dependencies);
        Route clientRoute = route.withAuth(AuthMode.OPTIONAL, dependencies.authProviders());
        BrowseRoutes.browseRoutes(clientRoute, dependencies.browseService());
        PlaybackRoutes.playbackRoutes(
            route,
            dependencies.playbackSessionService(),
            dependencies.playbackDeliveryService(),
            dependencies.mediaSessionReportService(),
            dependencies.pathSecurity(),
            dependencies.virtualPathResolver(),
            dependencies.serverConfig(),
            dependencies.authProviders(),
            dependencies.quotaService(),
            dependencies.mediaObjectResolver()
        );
        TranscodeRoutes.transcodeRoutes(
            route,
            dependencies.transcodeService(),
            dependencies.segmentCache(),
            dependencies.probeService(),
            dependencies.pathSecurity(),
            dependencies.authProviders(),
            dependencies.subtitleExtractor(),
            dependencies.virtualPathResolver(),
            dependencies.playbackSessionService(),
            dependencies.playbackDecisionService(),
            dependencies.healthMonitor(),
            dependencies.quotaService()
        );
        EFormRoutes.eformRoutes(
            route,
            dependencies.eformService(),
            dependencies.exportImportService(),
            dependencies.relocationService(),
            dependencies.pathSecurity(),
            dependencies.authProviders(),
            dependencies.virtualPathResolver()
        );
        ImageRoutes.imageRoutes(
            clientRoute,
            dependencies.mediaFileService(),
            dependencies.thumbnailService(),
            dependencies.exifExtractor(),
            dependencies.strippedImageCache(),
            dependencies.pathSecurity(),
            dependencies.virtualPathResolver(),
            dependencies.browseService(),
            dependencies.probeService(),
            dependencies.quotaService(),
            dependencies.imageTransformService(),
            dependencies.videoPreviewService(),
            dependencies.videoTrickplayService(),
            dependencies.mediaObjectResolver(),
            dependencies.mediaThumbnailService(),
            dependencies.mediaThumbnailLifecycle()
        );
        if (dependencies.webhookStore() != null && dependencies.webhookUrlValidator() != null) {
            WebhookRoutes.webhookRoutes(
                route,
                dependencies.webhookStore(),
                dependencies.webhookUrlValidator(),
                dependencies.authProviders()
            );
        }
        MediaObjectStateRoutes.mediaObjectStateRoutes(
            route,
            dependencies.mediaObjectService(),
            dependencies.userMediaStateService(),
            dependencies.virtualPathResolver(),
            dependencies.serverConfig().getThumbnails().getSizes(),
            dependencies.authProviders()
        );
        LibraryRoutes.libraryRoutes(
            route,
            dependencies.libraryService(),
            dependencies.libraryCatalogService(),
            dependencies.libraryUserStateService(),
            dependencies.authProviders()
        );
        AudioRoutes.audioRoutes(
            clientRoute,
            dependencies.mediaFileService(),
            dependencies.audioTranscoder(),
            dependencies.audioNegotiationService(),
            dependencies.audioSessionService(),
            dependencies.playlistService(),
            dependencies.pathSecurity(),
            dependencies.authProviders(),
            dependencies.virtualPathResolver(),
            dependencies.browseService(),
            dependencies.quotaService(),
            dependencies.mediaObjectResolver()
        );
        ChapterRoutes.chapterRoutes(
            route,
            dependencies.chapterService(),
            dependencies.pathSecurity(),
            dependencies.authProviders(),
            dependencies.virtualPathResolver()
        );
        AdminRoutes.adminRoutes(
            route,
            dependencies.thumbnailService(),
            dependencies.segmentCache(),
            dependencies.databases(),
            dependencies.mediaRoots(),
            dependencies.metricsService(),
            dependencies.virtualPathResolver(),
            dependencies.quotaService(),
            dependencies.backupService(),
            dependencies.libraryScanService(),
            dependencies.libraryAdminService(),
            dependencies.authProviders(),
            dependencies.storageBackendType()
        );
        ConfigRoutes.configRoutes(route, dependencies.configService(), dependencies.authProviders());
    }

    static void installQloudCompatibilityRoutes(
        Route route,
        DomainRouteDependencies dependencies,
        QloudCompatibilityConfig qloudCompatibilityConfig
    ) {
        QloudCompatibilityRoutes.qloudRoutes(
            route,
            dependencies.browseService(),
            dependencies.playbackSessionService(),
            dependencies.playbackDeliveryService(),
            dependencies.pathSecurity(),
            dependencies.virtualPathResolver(),
            dependencies.serverConfig(),
            dependencies.runtimeUsers(),
            qloudCompatibilityConfig,
            dependencies.authProviders(),
            dependencies.mediaObjectResolver(),
            dependencies.transcodeService(),
            dependencies.segmentCache(),
            dependencies.cleanupScheduler(),
            dependencies.probeService(),
            dependencies.thumbnailService(),
            dependencies.videoPreviewService()
        );
    }

    static void installOperationalRoutes(
        Route route,
        HealthService healthService,
        MetricsService metricsService,
        BackupService backupService,
        OpenApiRegistry openApiRegistry
    ) {
        route.get(
            "/api/v1/health",
            doc(config -> {
                config.setDescription("Runtime health status");
                config.response(response -> {
                    response.code(HTTP_OK, bodyDoc(RuntimeHealthResponse.class));
                });
            }),
            handlerScope -> {
                var runtime = healthService.runtimeHealth();
                RuntimeHealthResponse enriched = runtime;
                if (backupService != null) {
                    long timestampSeconds = backupService.getLastBackupTimestampEpochSeconds();
                    enriched = new RuntimeHealthResponse(
                        runtime.status(),
                        runtime.ffmpegAvailable(),
                        runtime.activeJobs(),
                        runtime.dbWritable(),
                        runtime.diskSpaceWarning(),
                        runtime.dbConnectivity(),
                        runtime.stuckJobsWarning(),
                        runtime.circuitBreakerOpen(),
                        timestampSeconds > 0 ? Instant.ofEpochSecond(timestampSeconds).toString() : null,
                        backupService.getLastBackupTotalBytes(),
                        serverVersion(),
                        buildMetadata()
                    );
                } else {
                    enriched = new RuntimeHealthResponse(
                        runtime.status(),
                        runtime.ffmpegAvailable(),
                        runtime.activeJobs(),
                        runtime.dbWritable(),
                        runtime.diskSpaceWarning(),
                        runtime.dbConnectivity(),
                        runtime.stuckJobsWarning(),
                        runtime.circuitBreakerOpen(),
                        runtime.lastBackupTimestamp(),
                        runtime.lastBackupBytes(),
                        serverVersion(),
                        buildMetadata()
                    );
                }
                handlerScope.getCall().respond(enriched);
            }
        );

        route.get(
            "/api/v1/health/live",
            doc(config -> {
                config.setDescription("Liveness probe");
                config.response(response -> {
                    response.code(HTTP_OK, bodyDoc(LivenessResponse.class));
                });
            }),
            handlerScope -> {
                handlerScope.getCall().respond(healthService.liveness());
            }
        );

        route.get(
            "/api/v1/health/ready",
            doc(config -> {
                config.setDescription("Readiness probe");
                config.response(response -> {
                    response.code(HTTP_OK, bodyDoc(ReadinessResponse.class));
                    response.code(HTTP_SERVICE_UNAVAILABLE, bodyDoc(ReadinessResponse.class));
                });
            }),
            handlerScope -> {
                ReadinessResponse readiness = healthService.readiness();
                HttpStatusCode status = "ready".equals(readiness.status()) ? HTTP_OK : HTTP_SERVICE_UNAVAILABLE;
                handlerScope.getCall().respond(status, readiness);
            }
        );

        route.get(
            "/api/v1/metrics",
            doc(config -> {
                config.setDescription("Prometheus metrics");
                config.response(response -> {
                    response.code(HTTP_OK, bodyDoc(String.class));
                });
            }),
            handlerScope -> {
                handlerScope.getCall().respondText(metricsService.scrape(), ContentType.Companion.parse("text/plain"));
            }
        );

        route.get(
            "/api/v1/openapi.json",
            doc(config -> config.setDescription("OpenAPI specification")),
            handlerScope -> {
                handlerScope.getCall().respond(
                    openApiRegistry.buildSpec(
                        "Nyx Media Streamer API",
                        "1.0.0",
                        "Adaptive media server: transcoding, images, audio, eForms"
                    )
                );
            }
        );

        route.get(
            "/api/v1/swagger",
            doc(config -> config.setDescription("Swagger UI")),
            handlerScope -> {
                handlerScope.getCall().respondText(
                    buildSwaggerUiHtml("/api/v1/openapi.json"),
                    ContentType.Companion.parse("text/html")
                );
            }
        );

        route.get(
            "/api/v1/swagger/",
            doc(config -> config.setDescription("Swagger UI")),
            handlerScope -> {
                handlerScope.getCall().respondText(
                    buildSwaggerUiHtml("/api/v1/openapi.json"),
                    ContentType.Companion.parse("text/html")
                );
            }
        );
    }

    private static void installClientCapabilitiesRoute(Route route, DomainRouteDependencies dependencies) {
        route.get(
            "/api/v1/client/capabilities",
            doc(config -> {
                config.setDescription("Client discovery contract for mobile and compatibility integrations");
                config.response(response -> response.code(HTTP_OK, bodyDoc(ClientCapabilitiesResponse.class)));
            }),
            handlerScope -> handlerScope.getCall().respond(clientCapabilities(dependencies))
        );
    }

    private static ClientCapabilitiesResponse clientCapabilities(DomainRouteDependencies dependencies) {
        Map<String, String> qualityPresets = dependencies.serverConfig().getFfmpeg().getQualityPresets().isEmpty()
            ? FfmpegConfig.DEFAULT_QUALITY_PRESETS
            : new LinkedHashMap<>(dependencies.serverConfig().getFfmpeg().getQualityPresets());
        QloudCompatibilityConfig qloud = dependencies.serverConfig().getCompatibility().getQloud();
        List<String> features = new ArrayList<>(List.of(
            "browse",
            "search",
            "images",
            "audio",
            "simple_hls_playback",
            "typed_errors",
            "media_capability_hints",
            "openapi_json"
        ));
        if (qloud.getEnabled()) {
            features.add("qloud_compatibility");
        }
        return new ClientCapabilitiesResponse(
            dependencies.serverConfig().getAuth().getEnabled(),
            serverVersion(),
            qualityPresets.keySet().stream().sorted().toList(),
            dependencies.serverConfig().getThumbnails().getSizes(),
            qloud.getEnabled() ? qloud.getPort() : null,
            List.copyOf(features),
            Map.of(
                "browse", "/api/v1/browse?path={path}",
                "search", "/api/v1/search/files?query={query}",
                "thumbnail", "/api/v1/images/thumb?path={path}&size={size}",
                "image", "/api/v1/images/file?path={path}",
                "audio", "/api/v1/audio/file?path={path}",
                "playback", "/api/v1/stream.m3u8?path={path}&quality={quality}"
            )
        );
    }

    private static String serverVersion() {
        String implementationVersion = AppOperationalRoutes.class.getPackage().getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank() ? "0.1.0-SNAPSHOT" : implementationVersion;
    }

    private static Map<String, String> buildMetadata() {
        String implementationVersion = AppOperationalRoutes.class.getPackage().getImplementationVersion();
        return Map.of(
            "implementationVersion",
            implementationVersion == null || implementationVersion.isBlank() ? "unknown" : implementationVersion,
            "javaVersion",
            System.getProperty("java.version", "unknown")
        );
    }

    private static String buildSwaggerUiHtml(String specUrl) {
        String assetBase = "/webjars/swagger-ui/" + loadSwaggerUiVersion();
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>Nyx API Docs</title>
              <link rel="stylesheet" href="%s/swagger-ui.css">
            </head>
            <body>
              <div id="swagger-ui"></div>
              <script src="%s/swagger-ui-bundle.js"></script>
              <script src="%s/swagger-ui-standalone-preset.js"></script>
              <script>
                window.ui = SwaggerUIBundle({
                  url: '%s',
                  dom_id: '#swagger-ui',
                  deepLinking: true,
                  presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
                  layout: 'BaseLayout'
                });
              </script>
            </body>
            </html>
            """.formatted(assetBase, assetBase, assetBase, specUrl);
    }

    private static String loadSwaggerUiVersion() {
        Properties properties = new Properties();
        var stream = Thread.currentThread()
            .getContextClassLoader()
            .getResourceAsStream("META-INF/maven/org.webjars/swagger-ui/pom.properties");
        if (stream != null) {
            try (stream) {
                properties.load(stream);
            } catch (Exception ignored) {
            }
        }
        return properties.getProperty("version", "5.25.3");
    }

    private static Consumer<OpenApiRouteConfig> doc(RouteDoc block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.ResponseDoc> bodyDoc(Class<?> type) {
        return response -> response.body(type);
    }

    @FunctionalInterface
    private interface RouteDoc {
        void accept(OpenApiRouteConfig config);
    }

    record ClientCapabilitiesResponse(
        boolean authEnabled,
        String serverVersion,
        List<String> supportedPlaybackQualities,
        List<Integer> supportedThumbnailSizes,
        Integer qloudCompatibilityPort,
        List<String> features,
        Map<String, String> routeTemplates
    ) {
        ClientCapabilitiesResponse {
            supportedPlaybackQualities = List.copyOf(supportedPlaybackQualities);
            supportedThumbnailSizes = List.copyOf(supportedThumbnailSizes);
            features = List.copyOf(features);
            routeTemplates = Map.copyOf(routeTemplates);
        }
    }
}
