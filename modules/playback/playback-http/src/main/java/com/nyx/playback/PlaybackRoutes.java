package com.nyx.playback;

import static com.nyx.common.RouteUtilsJava.enforceUserRateLimit;
import static com.nyx.common.RouteUtilsJava.resolvePathParam;

import com.nyx.common.ErrorCode;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.common.RangeSupport;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.ServerConfig;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.ParameterDoc;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.http.UserIdPrincipal;
import com.nyx.media.MediaObjectResolver;
import com.nyx.media.MediaObjectResolveOptions;
import com.nyx.media.contracts.MediaObject;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSessionReportResult;
import com.nyx.playback.contracts.MediaSessionReportService;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackDeliveryFailed;
import com.nyx.playback.contracts.PlaybackDeliveryLeasePolicy;
import com.nyx.playback.contracts.PlaybackDeliveryOutcome;
import com.nyx.playback.contracts.PlaybackDeliveryPending;
import com.nyx.playback.contracts.PlaybackDeliveryReadyFile;
import com.nyx.playback.contracts.PlaybackDeliveryReadyManifest;
import com.nyx.playback.contracts.PlaybackDeliveryReadiness;
import com.nyx.playback.contracts.PlaybackDeliveryRequest;
import com.nyx.playback.contracts.PlaybackDeliveryRetry;
import com.nyx.playback.contracts.PlaybackDeliveryService;
import com.nyx.playback.contracts.PlaybackDeliverySessionRequest;
import com.nyx.playback.contracts.PlaybackDeliveryStartupPolicy;
import com.nyx.playback.contracts.PlaybackDeliveryTerminated;
import com.nyx.playback.contracts.PlaybackDeliveryTimeoutAction;
import com.nyx.playback.contracts.PlaybackDeliveryUnavailable;
import com.nyx.playback.contracts.PlaybackOutputPreferences;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.TranscodePreferences;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class PlaybackRoutes {
    private static final int DEFAULT_MANIFEST_POLL_ATTEMPTS = 20;
    private static final long DEFAULT_MANIFEST_POLL_DELAY_MS = 500L;
    private static final long DEFAULT_SIMPLE_STREAM_IDLE_TTL_MS = TimeUnit.HOURS.toMillis(12);
    private static final String SIMPLE_STREAM_SESSION_HEADER = "X-Nyx-Playback-Session-Id";
    private static volatile int manifestPollAttempts = DEFAULT_MANIFEST_POLL_ATTEMPTS;
    private static volatile long manifestPollDelayMs = DEFAULT_MANIFEST_POLL_DELAY_MS;
    private static volatile long simpleStreamIdleTtlMs = DEFAULT_SIMPLE_STREAM_IDLE_TTL_MS;

    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_ACCEPTED = HttpStatusCode.Companion.getAccepted();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();
    private static final HttpStatusCode HTTP_BAD_REQUEST = HttpStatusCode.Companion.getBadRequest();

    private PlaybackRoutes() {
    }

    public static void playbackRoutes(
        Route route,
        PlaybackSessionService playbackSessionService,
        PlaybackDeliveryService playbackDeliveryService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver,
        ServerConfig serverConfig
    ) {
        playbackRoutes(
            route,
            playbackSessionService,
            playbackDeliveryService,
            null,
            pathSecurity,
            virtualPathResolver,
            serverConfig,
            List.of(),
            null,
            null
        );
    }

    public static void playbackRoutes(
        Route route,
        PlaybackSessionService playbackSessionService,
        PlaybackDeliveryService playbackDeliveryService,
        MediaSessionReportService mediaSessionReportService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver,
        ServerConfig serverConfig,
        List<String> authProviders,
        QuotaService quotaService,
        MediaObjectResolver mediaObjectResolver
    ) {
        MediaSessionReportService activeMediaSessionReportService = mediaSessionReportService;
        MediaObjectResolver activeMediaObjectResolver = mediaObjectResolver;
        Map<String, String> qualityPresets = serverConfig.getFfmpeg().getQualityPresets().isEmpty()
            ? FfmpegConfig.DEFAULT_QUALITY_PRESETS
            : new LinkedHashMap<>(serverConfig.getFfmpeg().getQualityPresets());

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/playback/sessions",
                doc(config -> {
                    config.setDescription("Open a playback session for a media source");
                    config.request(requestDoc(request -> request.body(PlaybackRequest.class)));
                    config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(PlaybackSession.class))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    String userId = principalName(call);
                    PlaybackRequest request = call.receive(PlaybackRequest.class);
                    Path absolutePath = resolvePathParam(request.source().path(), pathSecurity, virtualPathResolver);
                    MediaObject mediaObject = activeMediaObjectResolver == null
                        ? null
                        : activeMediaObjectResolver.resolveOrCreate(
                            absolutePath,
                            MediaObjectResolveOptions.DEFAULT
                        );
                    MediaSourceRef resolvedSource = new MediaSourceRef(
                        absolutePath.toString(),
                        request.source().characteristics(),
                        mediaObject == null ? request.source().objectId() : mediaObject.objectId(),
                        mediaObject == null ? request.source().mediaKind() : mediaObject.mediaKind()
                    );
                    PlaybackRequest resolvedRequest = new PlaybackRequest(
                        resolvedSource,
                        request.startPositionMillis(),
                        request.output(),
                        request.clientProfile(),
                        request.capabilities(),
                        request.selection(),
                        request.constraints(),
                        request.transcode()
                    );
                    call.respond(playbackSessionService.openSession(resolvedRequest, userId));
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/playback/sessions/{sessionId}",
                doc(config -> {
                    config.setDescription("Get playback session state");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(PlaybackSession.class));
                        response.code(HTTP_NOT_FOUND, describe("Session not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String sessionId = requireSessionId(call);
                    PlaybackSession session = requireSession(playbackSessionService, sessionId, userId);
                    call.respond(session);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/media/sessions/{sessionId}/report",
                doc(config -> {
                    config.setDescription("Report playback start, heartbeat, stop, or completion for an audio or playback session");
                    config.request(requestDoc(request -> request.body(MediaSessionPlaybackReport.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Updated media session"));
                        response.code(HTTP_NOT_FOUND, describe("Session not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    if (activeMediaSessionReportService == null) {
                        throw new IllegalStateException("MediaSessionReportService is required for media session report routes");
                    }
                    String userId = principalName(call);
                    String sessionId = requireSessionId(call);
                    MediaSessionPlaybackReport report = call.receive(MediaSessionPlaybackReport.class);
                    respondReportedMediaSession(
                        call,
                        activeMediaSessionReportService.reportPlayback(sessionId, report, userId)
                    );
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.delete(
                "/api/v1/playback/sessions/{sessionId}",
                doc(config -> {
                    config.setDescription("Close a playback session");
                    config.response(responseDoc(response -> response.code(HTTP_NO_CONTENT, describe("Session closed"))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String sessionId = requireSessionId(call);
                    playbackDeliveryService.close(sessionId, userId);
                    call.respond(HTTP_NO_CONTENT);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/playback/sessions/{sessionId}/manifest.mpd",
                doc(config -> {
                    config.setDescription("Get DASH manifest for a playback session");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("DASH manifest"));
                        response.code(HTTP_ACCEPTED, describe("Session pending"));
                        response.code(HTTP_NOT_FOUND, describe("Session not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String sessionId = requireSessionId(call);
                    PlaybackDeliveryOutcome outcome = playbackDeliveryService.observe(
                        new PlaybackDeliverySessionRequest(
                            sessionId,
                            userId,
                            PlaybackDeliveryReadiness.dashManifest(),
                            immediateStartupPolicy(),
                            new PlaybackDeliveryLeasePolicy()
                        )
                    );
                    respondDashManifestOutcome(call, outcome, sessionId);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/playback/sessions/{sessionId}/master.m3u8",
                doc(config -> {
                    config.setDescription("Get HLS master playlist for a playback session");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("HLS master playlist"));
                        response.code(HTTP_ACCEPTED, describe("Session pending"));
                        response.code(HTTP_NOT_FOUND, describe("Session not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String sessionId = requireSessionId(call);
                    PlaybackDeliveryOutcome outcome = playbackDeliveryService.observe(
                        new PlaybackDeliverySessionRequest(
                            sessionId,
                            userId,
                            PlaybackDeliveryReadiness.hlsManifest(),
                            immediateStartupPolicy(),
                            new PlaybackDeliveryLeasePolicy()
                        )
                    );
                    respondHlsManifestOutcome(call, outcome, sessionId);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/playback/sessions/{sessionId}/content",
                doc(config -> {
                    config.setDescription("Serve direct-play content for a playback session");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Direct-play media content"));
                        response.code(HTTP_NOT_FOUND, describe("Session not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String userId = principalName(call);
                    String sessionId = requireSessionId(call);
                    PlaybackDeliveryOutcome outcome = playbackDeliveryService.observe(
                        new PlaybackDeliverySessionRequest(
                            sessionId,
                            userId,
                            PlaybackDeliveryReadiness.directFile(),
                            immediateStartupPolicy(),
                            new PlaybackDeliveryLeasePolicy()
                        )
                    );
                    respondDirectContentOutcome(call, outcome, sessionId);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/stream.m3u8",
                doc(config -> {
                    config.setDescription("Open an HLS playback session from a media path and return the master playlist when ready");
                    config.request(requestDoc(request -> {
                        request.queryParameter("path", parameterDoc(param -> {
                            param.setDescription("Virtual path to the media file");
                            param.setRequired(true);
                        }));
                        request.queryParameter("quality", parameterDoc(param -> {
                            param.setDescription("Quality preset: " + qualityDescription(qualityPresets) + ", or passthrough");
                            param.setRequired(false);
                        }));
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("HLS master playlist (m3u8)"));
                        response.code(HTTP_ACCEPTED, bodyDoc(StreamPendingResponse.class));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid path or quality parameter"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    String userId = principalName(call);
                    String virtualPath = call.getQueryParameters().get("path");
                    if (virtualPath == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                    }
                    String quality = valueOrDefault(call.getQueryParameters().get("quality"), "medium");
                    Path absolutePath = resolvePathParam(virtualPath, pathSecurity, virtualPathResolver);
                    MediaObject mediaObject = activeMediaObjectResolver == null
                        ? null
                        : activeMediaObjectResolver.resolveOrCreate(
                            absolutePath,
                            MediaObjectResolveOptions.DEFAULT
                    );
                    PlaybackRequest playbackRequest = toStreamPlaybackRequest(quality, qualityPresets, absolutePath, mediaObject);
                    SimpleStreamRequestKey simpleStreamKey = new SimpleStreamRequestKey(
                        userId,
                        absolutePath.toString(),
                        quality.toLowerCase(Locale.ROOT)
                    );
                    PlaybackDeliveryOutcome outcome = playbackDeliveryService.open(
                        new PlaybackDeliveryRequest(
                            playbackRequest,
                            userId,
                            "passthrough".equalsIgnoreCase(quality)
                                ? PlaybackDeliveryReadiness.directFile()
                                : PlaybackDeliveryReadiness.hlsManifest(),
                            streamStartupPolicy(),
                            simpleStreamLeasePolicy(simpleStreamKey)
                        )
                    );
                    respondSimpleStreamOutcome(call, outcome);
                })
            );
        });
    }

    private static PlaybackRequest toStreamPlaybackRequest(
        String quality,
        Map<String, String> qualityPresets,
        Path absolutePath,
        MediaObject mediaObject
    ) {
        MediaSourceRef source = new MediaSourceRef(
            absolutePath.toString(),
            null,
            mediaObject == null ? null : mediaObject.objectId(),
            mediaObject == null ? null : mediaObject.mediaKind()
        );
        if ("passthrough".equalsIgnoreCase(quality)) {
            return new PlaybackRequest(
                source,
                0L,
                new PlaybackOutputPreferences(Set.of(StreamingProtocol.FILE), StreamingProtocol.FILE, false),
                null,
                null,
                null,
                null,
                null
            );
        }
        String profile = qualityPresets.get(quality.toLowerCase());
        if (profile == null) {
            throw nyxException(
                ErrorCode.INVALID_REQUEST,
                "Invalid quality '" + quality + "'. Available: passthrough, " + qualityDescription(qualityPresets)
            );
        }
        return new PlaybackRequest(
            source,
            0L,
            new PlaybackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS, true),
            null,
            null,
            null,
            null,
            new TranscodePreferences(profile, null, null)
        );
    }

    private static void respondDashManifestOutcome(RoutingCall call, PlaybackDeliveryOutcome outcome, String sessionId) {
        if (outcome instanceof PlaybackDeliveryReadyManifest manifest) {
            call.respondText(manifest.manifest(), new ContentType("application", "xml"));
            return;
        }
        respondExplicitDeliveryOutcome(call, outcome, sessionId, "DASH manifest not found for session: " + sessionId);
    }

    private static void respondHlsManifestOutcome(RoutingCall call, PlaybackDeliveryOutcome outcome, String sessionId) {
        if (outcome instanceof PlaybackDeliveryReadyManifest manifest) {
            call.respondText(manifest.manifest(), ContentType.Companion.parse(MediaTypes.HLS_M3U8));
            return;
        }
        respondExplicitDeliveryOutcome(call, outcome, sessionId, "HLS manifest not found for session: " + sessionId);
    }

    private static void respondDirectContentOutcome(RoutingCall call, PlaybackDeliveryOutcome outcome, String sessionId) {
        if (outcome instanceof PlaybackDeliveryReadyFile file) {
            RangeSupport.INSTANCE.respondFile(
                call,
                file.path(),
                MediaTypes.INSTANCE.detectMimeType(file.path()),
                3600
            );
            return;
        }
        if (outcome instanceof PlaybackDeliveryUnavailable unavailable) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, unavailable.message());
        }
        if (outcome instanceof PlaybackDeliveryTerminated terminated && terminated.session() == null) {
            throwDeliveryTerminated(sessionId, terminated);
        }
        if (
            outcome instanceof PlaybackDeliveryPending ||
                outcome instanceof PlaybackDeliveryFailed ||
                outcome instanceof PlaybackDeliveryTerminated
        ) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Playback session is not ready for direct content");
        }
        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Playback delivery unavailable for session: " + sessionId);
    }

    private static void respondExplicitDeliveryOutcome(
        RoutingCall call,
        PlaybackDeliveryOutcome outcome,
        String sessionId,
        String missingReadyArtifactMessage
    ) {
        if (outcome instanceof PlaybackDeliveryUnavailable unavailable) {
            throw nyxException(
                ErrorCode.JOB_NOT_FOUND,
                unavailable.message() == null ? missingReadyArtifactMessage : unavailable.message()
            );
        }
        if (outcome instanceof PlaybackDeliveryPending pending) {
            respondPending(call, pending.session());
            return;
        }
        if (outcome instanceof PlaybackDeliveryFailed failed) {
            throwDeliveryFailure(sessionId, failed);
        }
        if (outcome instanceof PlaybackDeliveryTerminated terminated) {
            throwDeliveryTerminated(sessionId, terminated);
        }
        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Playback delivery unavailable for session: " + sessionId);
    }

    private static void respondSimpleStreamOutcome(RoutingCall call, PlaybackDeliveryOutcome outcome) {
        if (outcome instanceof PlaybackDeliveryReadyFile file) {
            addSimpleStreamSessionHeader(call, file.session());
            RangeSupport.INSTANCE.respondFile(
                call,
                file.path(),
                MediaTypes.INSTANCE.detectMimeType(file.path()),
                3600
            );
            return;
        }
        if (outcome instanceof PlaybackDeliveryReadyManifest manifest) {
            addSimpleStreamSessionHeader(call, manifest.session());
            call.respondText(manifest.manifest(), ContentType.Companion.parse(MediaTypes.HLS_M3U8));
            return;
        }
        if (outcome instanceof PlaybackDeliveryPending pending) {
            PlaybackSession session = pending.session();
            PlaybackDeliveryRetry retry = pending.retry();
            call.getResponse().header(HttpHeaders.RetryAfter, Integer.toString(retry.retryAfterSeconds()));
            call.respond(
                HTTP_ACCEPTED,
                new StreamPendingResponse(
                    session.sessionId(),
                    session.artifacts() == null ? null : session.artifacts().playbackUrl(),
                    retry.status()
                )
            );
            return;
        }
        if (outcome instanceof PlaybackDeliveryUnavailable unavailable) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, unavailable.message());
        }
        if (outcome instanceof PlaybackDeliveryFailed failed) {
            throwDeliveryFailure(failed.sessionId(), failed);
        }
        if (outcome instanceof PlaybackDeliveryTerminated terminated) {
            throwDeliveryTerminated(terminated.sessionId(), terminated);
        }
        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Playback delivery unavailable");
    }

    private static PlaybackDeliveryStartupPolicy streamStartupPolicy() {
        return new PlaybackDeliveryStartupPolicy(
            manifestPollAttempts,
            manifestPollDelayMs,
            PlaybackDeliveryTimeoutAction.RETURN_PENDING,
            new PlaybackDeliveryRetry(2, "pending")
        );
    }

    private static PlaybackDeliveryStartupPolicy immediateStartupPolicy() {
        return new PlaybackDeliveryStartupPolicy(
            1,
            0L,
            PlaybackDeliveryTimeoutAction.RETURN_PENDING,
            new PlaybackDeliveryRetry(2, "pending")
        );
    }

    private static PlaybackDeliveryLeasePolicy simpleStreamLeasePolicy(SimpleStreamRequestKey key) {
        return new PlaybackDeliveryLeasePolicy(
            simpleStreamLeaseKey(key),
            simpleStreamIdleTtlMs,
            true,
            true,
            true,
            false,
            true
        );
    }

    private static String simpleStreamLeaseKey(SimpleStreamRequestKey key) {
        return key.userId() + "\n" + key.resolvedPath() + "\n" + key.quality();
    }

    private static void addSimpleStreamSessionHeader(RoutingCall call, PlaybackSession session) {
        call.getResponse().header(SIMPLE_STREAM_SESSION_HEADER, session.sessionId());
    }

    private static PlaybackSession requireSession(
        PlaybackSessionService playbackSessionService,
        String sessionId,
        String userId
    ) {
        PlaybackSession session = playbackSessionService.getSession(sessionId, userId);
        if (session == null) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Playback session not found: " + sessionId);
        }
        return session;
    }

    private static void respondPending(RoutingCall call, PlaybackSession session) {
        call.getResponse().header(HttpHeaders.RetryAfter, "2");
        call.respond(HTTP_ACCEPTED, session);
    }

    private static String requireSessionId(RoutingCall call) {
        String sessionId = call.getParameters().get("sessionId");
        if (sessionId == null) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Missing sessionId");
        }
        return sessionId;
    }

    private static void respondReportedMediaSession(RoutingCall call, MediaSessionReportResult result) {
        if (result instanceof MediaSessionReportResult.Audio audio) {
            call.respond(audio.session());
            return;
        }
        if (result instanceof MediaSessionReportResult.Playback playback) {
            call.respond(playback.session());
            return;
        }
        throw new IllegalStateException("Unknown media session report result: " + result);
    }

    private static void throwDeliveryFailure(String sessionId, PlaybackDeliveryFailed failed) {
        ErrorCode errorCode = "JOB_NOT_FOUND".equals(failed.failureCode())
            ? ErrorCode.JOB_NOT_FOUND
            : ErrorCode.TRANSCODE_FAILED;
        throw nyxException(
            errorCode,
            failed.message() == null ? "Playback session failed: " + sessionId : failed.message()
        );
    }

    private static void throwDeliveryTerminated(String sessionId, PlaybackDeliveryTerminated terminated) {
        String effectiveSessionId = sessionId == null ? terminated.sessionId() : sessionId;
        if (terminated.message() != null) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, terminated.message());
        }
        String message = switch (terminated.phase()) {
            case STOPPED -> "Playback session stopped: " + effectiveSessionId;
            case ABANDONED -> "Playback session abandoned: " + effectiveSessionId;
            default -> "Playback session unavailable: " + effectiveSessionId;
        };
        throw nyxException(ErrorCode.JOB_NOT_FOUND, message);
    }

    static AutoCloseable useManifestPollingForTesting(int attempts, long delayMs) {
        if (attempts < 1) {
            throw new IllegalArgumentException("Manifest poll attempts must be positive");
        }
        if (delayMs < 0L) {
            throw new IllegalArgumentException("Manifest poll delay must not be negative");
        }
        int previousAttempts = manifestPollAttempts;
        long previousDelayMs = manifestPollDelayMs;
        manifestPollAttempts = attempts;
        manifestPollDelayMs = delayMs;
        return () -> {
            manifestPollAttempts = previousAttempts;
            manifestPollDelayMs = previousDelayMs;
        };
    }

    static AutoCloseable useSimpleStreamIdleCleanupForTesting(long idleTtlMs) {
        if (idleTtlMs < 1L) {
            throw new IllegalArgumentException("Simple stream idle TTL must be positive");
        }
        long previousIdleTtlMs = simpleStreamIdleTtlMs;
        simpleStreamIdleTtlMs = idleTtlMs;
        return () -> simpleStreamIdleTtlMs = previousIdleTtlMs;
    }

    private static String principalName(RoutingCall call) {
        UserIdPrincipal principal = call.principal(UserIdPrincipal.class);
        return principal == null ? null : principal.getName();
    }

    private static String qualityDescription(Map<String, String> qualityPresets) {
        return qualityPresets.keySet().stream().sorted().reduce((left, right) -> left + ", " + right).orElse("");
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(com.nyx.http.AuthMode.OPTIONAL, authProviders));
    }

    private static Consumer<OpenApiRouteConfig> doc(RouteDoc doc) {
        return doc::accept;
    }

    private static Consumer<com.nyx.http.RequestDoc> requestDoc(RequestDocBlock block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.ResponseCollection> responseDoc(ResponseDocBlock block) {
        return block::accept;
    }

    private static Consumer<ParameterDoc> parameterDoc(ParameterDocBlock block) {
        return block::accept;
    }

    private static Consumer<RouteHandlerScope> handler(RouteHandler handler) {
        return handler::accept;
    }

    private static Consumer<com.nyx.http.ResponseDoc> describe(String description) {
        return response -> response.setDescription(description);
    }

    private static Consumer<com.nyx.http.ResponseDoc> bodyDoc(Class<?> type) {
        return response -> response.body(type);
    }

    private record SimpleStreamRequestKey(String userId, String resolvedPath, String quality) {
    }

    private static RuntimeException nyxException(ErrorCode errorCode, String message) {
        return sneakyThrow(new NyxException(errorCode, message, Map.of(), null));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    @FunctionalInterface
    private interface RouteRegistrar {
        void accept(Route route);
    }

    @FunctionalInterface
    private interface RouteDoc {
        void accept(OpenApiRouteConfig config);
    }

    @FunctionalInterface
    private interface RequestDocBlock {
        void accept(com.nyx.http.RequestDoc request);
    }

    @FunctionalInterface
    private interface ResponseDocBlock {
        void accept(com.nyx.http.ResponseCollection response);
    }

    @FunctionalInterface
    private interface ParameterDocBlock {
        void accept(ParameterDoc parameter);
    }

    @FunctionalInterface
    private interface RouteHandler {
        void accept(RouteHandlerScope scope);
    }
}

record StreamPendingResponse(
    String sessionId,
    String playbackUrl,
    String status
) {
}
