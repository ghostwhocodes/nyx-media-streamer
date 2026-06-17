package com.nyx.qloud;

import static com.nyx.common.RouteUtilsJava.resolvePathParam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.browse.BrowseService;
import com.nyx.browse.BrowseSortOrder;
import com.nyx.browse.MediaTypeFilter;
import com.nyx.ApplicationRuntime;
import com.nyx.common.ErrorCode;
import com.nyx.common.ErrorDetail;
import com.nyx.common.ErrorResponse;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.QloudCompatibilityConfig;
import com.nyx.config.ServerConfig;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.MediaProberInterop;
import com.nyx.ffmpeg.VideoPreviewRequest;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.Profile;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.ffmpeg.model.TranscodeProfiles;
import com.nyx.ffmpeg.model.VideoCodec;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.nyx.media.MediaObjectResolveOptions;
import com.nyx.media.MediaObjectResolver;
import com.nyx.media.ThumbnailService;
import com.nyx.media.VideoPreviewService;
import com.nyx.media.contracts.BrowseListing;
import com.nyx.media.contracts.FileSearchResult;
import com.nyx.media.contracts.MediaItem;
import com.nyx.media.contracts.MediaObject;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.AudioTrackSelection;
import com.nyx.playback.contracts.AudioTrackSelectionMode;
import com.nyx.playback.contracts.PlaybackDeliveryFailed;
import com.nyx.playback.contracts.PlaybackDeliveryLeasePolicy;
import com.nyx.playback.contracts.PlaybackDeliveryOutcome;
import com.nyx.playback.contracts.PlaybackDeliveryPending;
import com.nyx.playback.contracts.PlaybackDeliveryReadyManifest;
import com.nyx.playback.contracts.PlaybackDeliveryReadiness;
import com.nyx.playback.contracts.PlaybackDeliveryRequest;
import com.nyx.playback.contracts.PlaybackDeliveryService;
import com.nyx.playback.contracts.PlaybackDeliveryStartupPolicy;
import com.nyx.playback.contracts.PlaybackDeliveryTerminated;
import com.nyx.playback.contracts.PlaybackDeliveryTimeoutAction;
import com.nyx.playback.contracts.PlaybackOutputPreferences;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSelection;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.stream.representation.contracts.StreamRepresentationTraits;
import com.nyx.stream.representation.contracts.StreamSegmentContainer;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleSelection;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import com.nyx.playback.contracts.TranscodePreferences;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QloudCompatibilityRoutes {
    private static final Logger LOGGER = LoggerFactory.getLogger(QloudCompatibilityRoutes.class);
    private static final int DEFAULT_LIST_LIMIT = 250;
    private static final int DEFAULT_SEARCH_LIMIT = 50;
    private static final int DEFAULT_MANIFEST_POLL_ATTEMPTS = 20;
    private static final long DEFAULT_MANIFEST_POLL_DELAY_MS = 500L;
    private static volatile int manifestPollAttempts = DEFAULT_MANIFEST_POLL_ATTEMPTS;
    private static volatile long manifestPollDelayMs = DEFAULT_MANIFEST_POLL_DELAY_MS;
    private static final Duration COMPATIBILITY_SESSION_TTL = Duration.ofHours(24);
    private static final Duration HLS_BRIDGE_TTL = Duration.ofMinutes(30);

    private static final String SERVER_VERSION = "4.1.1";
    private static final String SERVER_ACCESS = "public";
    private static final String SERVER_PIN = "635618614128";
    private static final String QLOUD_HANDSHAKE_UPSTREAM_PROPERTY = "nyx.qloud.handshake.upstream";
    private static final String QLOUD_HANDSHAKE_UPSTREAM_ENV = "NYX_QLOUD_HANDSHAKE_UPSTREAM";
    private static final String QLOUD_LEGACY_TS_HLS_PROPERTY = "nyx.qloud.legacy.ts.hls";
    private static final String QLOUD_LEGACY_TS_HLS_ENV = "NYX_QLOUD_LEGACY_TS_HLS";
    private static final String PROC_RSA_MODULUS_HEX = """
        90949c4c328f5c8ec3faf55152d73500fc198ebf119fea2aea6d961422e0896a2fa44371818cda0c
        1738e06227b4037562c929fe75c6606c29232ae4e81c0232b9331deece6637c33c798dd10c0a06de
        b1b04da9d4f2d2c19d31291867679cbd8e3421d27bfae66bfd1ed372c03edbe3db85c5a94b9c5519
        806d2a40ddec5a9f
        """;
    private static final String PROC_RSA_PRIVATE_EXPONENT_HEX = """
        718b04242320cb43a34d9712c2c817ec2a0fb836fd9464c6474cc0ac17a7d6c2f99f3b080d019ccc
        1a00a4d6f0ef423811d8818e40d806296b351f1e9dda412e74cc3b0180e20d3d6055004c8872cade
        3f0eff53115a39f94414a93b271a002015816629300f11502547637d19308f156f39ad78431cdd05
        d34be869399b52e1
        """;
    private static final String QLOUD_MASTER_SUFFIX = ".(_n_y_x_).m3u8";
    private static final String RESULT_DONE = "done";
    private static final String RESULT_MORE = "more";

    private static final HttpStatusCode HTTP_ACCEPTED = HttpStatusCode.Companion.getAccepted();
    private static final HttpStatusCode HTTP_UNAUTHORIZED = HttpStatusCode.Companion.getUnauthorized();
    private static final ContentType HLS_CONTENT_TYPE = ContentType.Companion.parse(MediaTypes.HLS_M3U8);
    private static final ObjectMapper JSON = NyxJson.newMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration QLOUD_HANDSHAKE_TIMEOUT = Duration.ofSeconds(3);
    private static final HttpClient QLOUD_HANDSHAKE_CLIENT = HttpClient.newBuilder()
        .connectTimeout(QLOUD_HANDSHAKE_TIMEOUT)
        .build();
    private static final StreamRepresentationPolicy REPRESENTATION_POLICY = StreamRepresentationPolicy.defaultPolicy();
    private static final BigInteger PROC_RSA_MODULUS = new BigInteger(normalizeHex(PROC_RSA_MODULUS_HEX), 16);
    private static final BigInteger PROC_RSA_PRIVATE_EXPONENT = new BigInteger(
        normalizeHex(PROC_RSA_PRIVATE_EXPONENT_HEX),
        16
    );

    private final BrowseService browseService;
    private final PlaybackSessionService playbackSessionService;
    private final PlaybackDeliveryService playbackDeliveryService;
    private final PathSecurity pathSecurity;
    private final VirtualPathResolver virtualPathResolver;
    private final ServerConfig serverConfig;
    private final ConcurrentHashMap<String, String> runtimeUsers;
    private final QloudCompatibilityConfig qloudCompatibilityConfig;
    private final List<String> authProviders;
    private final MediaObjectResolver mediaObjectResolver;
    private final TranscodeApplicationService transcodeService;
    private final SegmentCacheService segmentCache;
    private final MediaProber mediaProber;
    private final ThumbnailService thumbnailService;
    private final VideoPreviewService videoPreviewService;
    private final StreamRepresentation qloudRepresentation;
    private final QloudLegacyPlaybackFields qloudPlaybackFields;
    private final QloudOriginPolicy originPolicy;
    private final QloudCompatibilitySessionPolicy compatibilitySessionPolicy;
    private final QloudHlsBridgePolicy hlsBridgePolicy;
    private final QloudRecentMediaMemory recentMediaMemory;

    private QloudCompatibilityRoutes(
        BrowseService browseService,
        PlaybackSessionService playbackSessionService,
        PlaybackDeliveryService playbackDeliveryService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver,
        ServerConfig serverConfig,
        ConcurrentHashMap<String, String> runtimeUsers,
        QloudCompatibilityConfig qloudCompatibilityConfig,
        List<String> authProviders,
        MediaObjectResolver mediaObjectResolver,
        TranscodeApplicationService transcodeService,
        SegmentCacheService segmentCache,
        ScheduledExecutorService cleanupScheduler,
        MediaProber mediaProber,
        ThumbnailService thumbnailService,
        VideoPreviewService videoPreviewService
    ) {
        this.browseService = browseService;
        this.playbackSessionService = playbackSessionService;
        this.playbackDeliveryService = Objects.requireNonNull(playbackDeliveryService, "playbackDeliveryService");
        this.pathSecurity = pathSecurity;
        this.virtualPathResolver = virtualPathResolver;
        this.serverConfig = serverConfig;
        this.runtimeUsers = runtimeUsers;
        this.qloudCompatibilityConfig = qloudCompatibilityConfig;
        this.authProviders = authProviders == null ? List.of() : List.copyOf(authProviders);
        this.mediaObjectResolver = mediaObjectResolver;
        this.transcodeService = transcodeService;
        this.segmentCache = segmentCache;
        this.mediaProber = mediaProber;
        this.thumbnailService = thumbnailService;
        this.videoPreviewService = videoPreviewService;
        this.qloudRepresentation = configuredQloudRepresentation();
        this.qloudPlaybackFields = qloudLegacyPlaybackFields(REPRESENTATION_POLICY.traits(qloudRepresentation));
        this.originPolicy = new QloudOriginPolicy(qloudCompatibilityConfig);
        this.compatibilitySessionPolicy = new QloudCompatibilitySessionPolicy(QloudCompatibilityRoutes::issueCompatibilitySessionId);
        this.hlsBridgePolicy = new QloudHlsBridgePolicy(playbackDeliveryService);
        this.recentMediaMemory = new QloudRecentMediaMemory(pathSecurity, virtualPathResolver);
        scheduleCompatibilityCleanup(cleanupScheduler);
    }

    public static void qloudRoutes(
        Route route,
        BrowseService browseService,
        PlaybackSessionService playbackSessionService,
        PlaybackDeliveryService playbackDeliveryService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver,
        ServerConfig serverConfig,
        ConcurrentHashMap<String, String> runtimeUsers,
        QloudCompatibilityConfig qloudCompatibilityConfig,
        List<String> authProviders,
        MediaObjectResolver mediaObjectResolver,
        TranscodeApplicationService transcodeService,
        SegmentCacheService segmentCache,
        ScheduledExecutorService cleanupScheduler,
        MediaProber mediaProber,
        ThumbnailService thumbnailService,
        VideoPreviewService videoPreviewService
    ) {
        new QloudCompatibilityRoutes(
            browseService,
            playbackSessionService,
            playbackDeliveryService,
            pathSecurity,
            virtualPathResolver,
            serverConfig,
            runtimeUsers,
            qloudCompatibilityConfig,
            authProviders,
            mediaObjectResolver,
            transcodeService,
            segmentCache,
            cleanupScheduler,
            mediaProber,
            thumbnailService,
            videoPreviewService
        ).install(route);
    }

    private void install(Route route) {
        route.post("/proc/hello", handler(scope -> handleHello(scope.getCall())));
        route.post("/proc/auth", handler(scope -> handleAuth(scope.getCall())));
        route.post("/proc/list", handler(scope -> handleList(scope.getCall())));
        route.post("/proc/info", handler(scope -> handleInfo(scope.getCall())));
        route.post("/proc/image", handler(scope -> handleImage(scope.getCall())));
        route.post("/proc/search", handler(scope -> handleSearch(scope.getCall())));
        route.post("/proc/video", handler(scope -> handleVideo(scope.getCall())));
        route.get("/{qloudToken}/<resource>", handler(scope -> handleHlsResource(scope.getCall())));
    }

    private void handleHello(RoutingCall call) {
        String requestText = call.receiveText();
        Map<String, Object> request = requestMap(requestText);
        String token = handshakeToken("/proc/hello", requestText);
        if (token == null) {
            token = issueHelloToken();
        }
        Map<String, Object> response = responseBase(request, "hello");
        response.put("version", valueOrDefault(request.get("version"), 11));
        response.put("protocol-version", valueOrDefault(request.get("protocol-version"), 35));
        response.put("ignore-protocol-version", booleanValue(request.get("ignore-protocol-version"), true));
        response.put("server-name", serverName());
        response.put("server-version", SERVER_VERSION);
        response.put("server-protocol-version", valueOrDefault(request.get("protocol-version"), 35));
        response.put("server-access", SERVER_ACCESS);
        response.put("token", token);
        respondJson(call, response);
    }

    private void handleAuth(RoutingCall call) {
        String requestText = call.receiveText();
        Map<String, Object> request = requestMap(requestText);
        CompatibilityAccess access = authorizeCompatibilityRequest(call, request, false);
        if (!access.allowed()) {
            return;
        }
        Map<String, Object> handshakeResponse = handshakeResponse("/proc/auth", requestText);
        if (handshakeResponse != null && handshakeResponse.containsKey("error")) {
            respondJson(call, handshakeResponse);
            return;
        }
        String token = tokenValue(handshakeResponse);
        if (token == null) {
            token = issueAuthToken();
        }
        CompatibilitySession issuedSession = compatibilityAuthRequired() ? issueCompatibilitySession(access.owner()) : null;
        QloudOriginPolicy.RequestOrigin origin = requestOrigin(call);
        Map<String, Object> response = responseBase(request, "login");
        copyIfPresent(request, response, "auth");
        copyIfPresent(request, response, "client");
        copyIfPresent(request, response, "platform");
        copyIfPresent(request, response, "platform-h264-level");
        copyIfPresent(request, response, "platform-h264-profile");
        copyIfPresent(request, response, "platform-version");
        copyIfPresent(request, response, "protocol-version");
        copyIfPresent(request, response, "screen-height");
        copyIfPresent(request, response, "screen-width");
        copyIfPresent(request, response, "session");
        if (issuedSession != null) {
            response.put("session", issuedSession.rpcSession());
        }
        response.put("server-access", SERVER_ACCESS);
        response.put("server-external-address", origin.host());
        response.put("server-external-port", origin.port());
        response.put("server-local-address", origin.host());
        response.put("server-local-port", origin.port());
        response.put("server-name", serverName());
        response.put("server-pin", SERVER_PIN);
        response.put("server-protocol-version", valueOrDefault(request.get("protocol-version"), 35));
        response.put("server-version", SERVER_VERSION);
        response.put("token", token);
        respondJson(call, response);
    }

    private void handleList(RoutingCall call) {
        Map<String, Object> request = requestMap(call);
        if (!authorizeCompatibilityRequest(call, request, true).allowed()) {
            return;
        }
        String action = valueOrDefault(stringValue(request.get("action")), "browse");
        Map<String, Object> response = responseBase(request, action);

        if ("browse-recent".equals(action)) {
            respondRecentList(call, request, response);
            return;
        }

        String qloudPath = valueOrDefault(stringValue(request.get("path")), "/");
        String nyxPath = browseNyxPath(action, qloudPath);
        BrowseListing listing = browseService.browse(qloudBrowsePath(nyxPath), 1, listLimit(request), sortOrder(request));
        response.put("cache", request.getOrDefault("cache", listing.items().size()));
        response.put("cache-folders", request.getOrDefault("cache-folders", listing.items().size()));
        response.put("items", listing.items().stream().map(this::toQloudListItem).toList());
        copyIfPresent(request, response, "media");
        response.put("path", toQloudPath(nyxPath));
        copyIfPresent(request, response, "samples");
        copyIfPresent(request, response, "session");
        copyIfPresent(request, response, "sort");
        respondJson(call, response);
    }

    private String browseNyxPath(String action, String qloudPath) {
        String nyxPath = toNyxPath(qloudPath);
        if ("browse-siblings".equals(action) || browsePathTargetsRegularFile(nyxPath)) {
            return parentNyxPath(nyxPath);
        }
        return nyxPath;
    }

    private boolean browsePathTargetsRegularFile(String nyxPath) {
        if (nyxPath == null || nyxPath.isBlank()) {
            return false;
        }
        try {
            Path absolutePath = resolvePathParam(nyxPath, pathSecurity, virtualPathResolver);
            return Files.isRegularFile(absolutePath);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private void respondRecentList(
        RoutingCall call,
        Map<String, Object> request,
        Map<String, Object> response
    ) {
        int limit = recentLimit(request);
        MediaTypeFilter filter = mediaFilter(request);
        List<QloudRecentMediaMemory.RecentMediaCandidate> recentItems = recentMediaCandidates(
            limit,
            filter == null ? MediaTypeFilter.VIDEO : filter
        );
        response.put("items", recentItems.stream().map(this::toQloudRecentListItem).toList());
        response.put("limit", limit);
        copyIfPresent(request, response, "media");
        copyIfPresent(request, response, "session");
        respondJson(call, response);
    }

    private List<QloudRecentMediaMemory.RecentMediaCandidate> recentMediaCandidates(int limit, MediaTypeFilter filter) {
        return recentMediaMemory.candidates(limit, filter);
    }

    private void rememberRecentMedia(String nyxPath) {
        recentMediaMemory.remember(nyxPath);
    }

    private static boolean matchesMediaFilter(String mimeType, MediaTypeFilter filter) {
        return QloudRecentMediaMemory.matchesMediaFilter(mimeType, filter);
    }

    private String qloudBrowsePath(String nyxPath) {
        return QloudPathPolicy.browsePath(nyxPath, virtualPathResolver);
    }

    private Map<String, Object> toQloudRecentListItem(QloudRecentMediaMemory.RecentMediaCandidate item) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", item.name());
        map.put("path", toQloudPath(item.virtualPath()));
        map.put("size", item.size());
        map.put("last-modified", item.modifiedAt());
        map.put("type", qloudType(item.mimeType()));
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("title", titleFromName(item.name()));
        map.put("extra", extra);
        return map;
    }

    private static String titleFromName(String name) {
        return QloudResponseShape.titleFromName(name);
    }

    private void handleInfo(RoutingCall call) {
        Map<String, Object> request = requestMap(call);
        if (!authorizeCompatibilityRequest(call, request, true).allowed()) {
            return;
        }
        String action = valueOrDefault(stringValue(request.get("action")), "info");
        Map<String, Object> response = responseBase(request, action);
        copyIfPresent(request, response, "session");
        copyIfPresent(request, response, "accept-lang-title");
        copyIfPresent(request, response, "image-type");
        copyIfPresent(request, response, "include-errors");

        if ("info-items".equals(action)) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (String path : stringList(request.get("path-list"))) {
                items.add(mediaInfo(path, request.get("seek"), true));
            }
            response.put("path-list", stringList(request.get("path-list")));
            response.put("items", items);
            respondJson(call, response);
            return;
        }

        String path = valueOrDefault(stringValue(request.get("path")), "/");
        response.putAll(mediaInfo(path, request.get("seek"), true));
        respondJson(call, response);
    }

    private void handleImage(RoutingCall call) {
        Map<String, Object> request = requestMap(call);
        if (!authorizeCompatibilityRequest(call, request, true).allowed()) {
            return;
        }
        String action = valueOrDefault(stringValue(request.get("action")), "prepare-items");
        Map<String, Object> response = responseBase(request, action);
        copyIfPresent(request, response, "include-errors");
        copyIfPresent(request, response, "max-screen-scale");
        copyIfPresent(request, response, "session");
        copyIfPresent(request, response, "sort");
        List<String> paths = stringList(request.get("path-list"));
        response.put("path-list", paths);
        response.put("items", paths.stream().map(this::imageItem).toList());
        respondJson(call, response);
    }

    private void handleSearch(RoutingCall call) {
        Map<String, Object> request = requestMap(call);
        if (!authorizeCompatibilityRequest(call, request, true).allowed()) {
            return;
        }
        String action = valueOrDefault(stringValue(request.get("action")), "search");
        String pattern = valueOrDefault(stringValue(request.get("pattern")), "");
        String query = normalizeSearchPattern(pattern);
        int limit = intValue(request.get("limit"), DEFAULT_SEARCH_LIMIT, 1, 200);
        FileSearchResult result = query.isBlank()
            ? new FileSearchResult(List.of(), 0, 1, limit, query)
            : browseService.searchFiles(query, 1, limit, mediaFilter(request));

        Map<String, Object> response = responseBase(request, action);
        copyIfPresent(request, response, "media");
        copyIfPresent(request, response, "path");
        copyIfPresent(request, response, "session");
        response.put("pattern", pattern);
        response.put("limit", limit);
        response.put("items", result.items().stream().map(this::toQloudListItem).toList());
        response.put("result", result.total() > result.items().size() ? RESULT_MORE : RESULT_DONE);
        respondJson(call, response);
    }

    private void handleVideo(RoutingCall call) {
        Map<String, Object> request = requestMap(call);
        CompatibilityAccess access = authorizeCompatibilityRequest(call, request, true);
        if (!access.allowed()) {
            return;
        }
        String action = valueOrDefault(stringValue(request.get("action")), "play");
        switch (action) {
            case "play" -> handleVideoPlay(call, request, access.owner());
            case "seek" -> handleVideoSeek(call, request);
            case "close" -> handleVideoClose(call, request, access.owner());
            case "clear-progress" -> respondJson(call, responseBase(request, action));
            default -> respondJson(call, responseBase(request, action));
        }
    }

    private void handleVideoPlay(RoutingCall call, Map<String, Object> request, String owner) {
        String qloudPath = requireString(request, "path");
        String nyxPath = toNyxPath(qloudPath);
        Path absolutePath = resolvePathParam(nyxPath, pathSecurity, virtualPathResolver);
        rememberRecentMedia(nyxPath);
        MediaObject mediaObject = resolveMediaObject(absolutePath);
        PlaybackRequest playbackRequest = playbackRequest(absolutePath, mediaObject, request);

        PlaybackDeliveryReadyManifest delivery = openQloudPlayback(playbackRequest, owner);
        String playbackSessionId = delivery.sessionId();
        HlsBridgeSession bridge = null;
        try {
            String hlsToken = issueHlsToken();
            String masterResource = masterResource(qloudPath);
            Instant now = Instant.now();
            bridge = new HlsBridgeSession(
                hlsToken,
                stringValue(request.get("session")),
                owner,
                playbackSessionId,
                delivery.backingJobId(),
                toQloudPath(nyxPath),
                nyxPath,
                masterResource,
                now,
                bridgeClientKey(owner, stringValue(request.get("session")), toQloudPath(nyxPath))
            );
            registerHlsBridge(bridge);

            Map<String, Object> response = responseBase(request, "play");
            copyVideoRequestEchoes(request, response);
            response.put("path", bridge.qloudPath());
            response.put("protocol", valueOrDefault(stringValue(request.get("protocol")), qloudPlaybackFields.protocol()));
            response.put("protocol-scheme", valueOrDefault(stringValue(request.get("protocol-scheme")), "http"));
            response.put("url", qloudResourceUrl(baseUrl(call), bridge.token(), bridge.masterResource()));
            response.put("precache", qloudPlaybackFields.precache());
            response.put("episodes", List.of());
            if (qloudPlaybackFields.audioBoost() != null) {
                response.put("audio-boost", qloudPlaybackFields.audioBoost());
            }
            response.putAll(videoPlaybackMetadata(absolutePath, request.get("seek")));
            respondJson(call, response);
        } catch (Exception exception) {
            if (bridge != null) {
                closeHlsBridge(bridge);
            } else {
                playbackDeliveryService.close(playbackSessionId, owner);
            }
            sneakyThrow(exception);
            return;
        }
    }

    private void handleVideoSeek(RoutingCall call, Map<String, Object> request) {
        String qloudPath = requireString(request, "path");
        Path absolutePath = resolvePathParam(toNyxPath(qloudPath), pathSecurity, virtualPathResolver);
        Map<String, Object> response = responseBase(request, "seek");
        copyIfPresent(request, response, "path");
        copyIfPresent(request, response, "session");
        double time = doubleValue(request.get("seek"), 0.0);
        response.put("time", time);
        response.putAll(durationMetadata(absolutePath));
        String image = previewBase64(absolutePath, time);
        if (image != null) {
            response.put("image", image);
        }
        respondJson(call, response);
    }

    private void handleVideoClose(RoutingCall call, Map<String, Object> request, String owner) {
        String qloudPath = valueOrDefault(stringValue(request.get("path")), "/");
        String rpcSession = stringValue(request.get("session"));
        pruneExpiredHlsBridges(Instant.now());
        HlsBridgeSession bridge = findBridgeForClose(owner, rpcSession, toQloudPath(toNyxPath(qloudPath)));
        if (bridge != null) {
            closeHlsBridge(bridge);
        }

        Map<String, Object> response = responseBase(request, "close");
        copyIfPresent(request, response, "path");
        copyIfPresent(request, response, "session");
        copyIfPresent(request, response, "progress");
        copyIfPresent(request, response, "async");
        respondJson(call, response);
    }

    private void handleHlsResource(RoutingCall call) {
        String token = call.getPathParameters().get("qloudToken");
        String resource = call.getPathParameters().get("resource");
        HlsBridgeSession bridge = hlsBridge(token);
        if (bridge == null || resource == null || resource.isBlank()) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Qloud compatibility stream not found");
        }

        if (resource.equals(bridge.masterResource())) {
            respondMasterPlaylist(call, bridge);
            return;
        }

        String prefix = bridge.masterResource() + "/";
        if (!resource.startsWith(prefix)) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid Qloud compatibility stream resource");
        }

        String suffix = resource.substring(prefix.length());
        int slashIndex = suffix.indexOf('/');
        if (slashIndex < 0 && suffix.endsWith(".m3u8")) {
            String representationId = suffix.substring(0, suffix.length() - ".m3u8".length());
            respondMediaPlaylist(call, bridge, representationId);
            return;
        }
        if (slashIndex > 0) {
            String representationId = suffix.substring(0, slashIndex);
            String segmentUri = suffix.substring(slashIndex + 1);
            respondSegment(call, bridge, representationId, segmentUri);
            return;
        }

        throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid Qloud compatibility stream resource");
    }

    private void respondMasterPlaylist(RoutingCall call, HlsBridgeSession bridge) {
        String manifest = playbackSessionService.getHlsManifest(bridge.playbackSessionId(), bridge.owner());
        if (manifest == null) {
            respondPending(call);
            return;
        }
        call.respondText(rewriteMasterPlaylist(manifest, bridge, baseUrl(call)), HLS_CONTENT_TYPE);
    }

    private void respondMediaPlaylist(RoutingCall call, HlsBridgeSession bridge, String representationId) {
        String playlist = waitForPlayableMediaPlaylist(bridge.jobId(), representationId);
        if (playlist == null) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "HLS playlist not found: " + representationId);
        }
        call.respondText(rewriteMediaPlaylist(playlist, bridge, representationId, baseUrl(call)), HLS_CONTENT_TYPE);
    }

    private String waitForPlayableMediaPlaylist(String jobId, String representationId) {
        String latest = null;
        int attempts = manifestPollAttempts;
        for (int attempt = 0; attempt < attempts; attempt++) {
            latest = transcodeService.getHlsMediaPlaylist(jobId, representationId);
            if (latest != null && (hasMediaSegment(latest) || latest.contains("#EXT-X-ENDLIST"))) {
                return latest;
            }
            sleepManifestPoll();
        }
        return latest;
    }

    private static boolean hasMediaSegment(String playlist) {
        if (playlist == null) {
            return false;
        }
        return playlist.lines().anyMatch(line -> {
            String trimmed = line.trim();
            return !trimmed.isEmpty() && !trimmed.startsWith("#");
        });
    }

    @SuppressWarnings("unused")
    private void respondSegment(
        RoutingCall call,
        HlsBridgeSession bridge,
        String representationId,
        String segmentUri
    ) {
        String segmentName = segmentName(segmentUri);
        if (segmentName.contains("/") || segmentName.contains("\\") || segmentName.contains("..")) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid segment name");
        }

        Path outputDir = transcodeService.getSegmentOutputDir(bridge.jobId());
        if (outputDir == null) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Transcode output not found: " + bridge.jobId());
        }

        Path segmentPath = outputDir.resolve(segmentName);
        if (!segmentPath.normalize().startsWith(outputDir.normalize())) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid segment name");
        }

        Path acquired = segmentCache == null ? null : segmentCache.acquire(segmentPath);
        if (acquired != null && Files.exists(acquired)) {
            try {
                call.respondFile(acquired, defaultMimeType(segmentName));
            } finally {
                segmentCache.release(segmentPath);
            }
            return;
        }
        if (Files.exists(segmentPath)) {
            call.respondFile(segmentPath, defaultMimeType(segmentName));
            return;
        }
        respondPending(call);
    }

    private Map<String, Object> toQloudListItem(MediaItem item) {
        Map<String, Object> map = new LinkedHashMap<>();
        String qloudPath = toQloudPath(item.path());
        map.put("name", item.name());
        map.put("path", qloudPath);
        map.put("size", qloudItemSize(item, qloudPath));
        Long modifiedAt = qloudModifiedAt(item, qloudPath);
        if (modifiedAt != null) {
            map.put("last-modified", modifiedAt);
        }
        map.put("type", qloudType(item));
        return map;
    }

    private long qloudItemSize(MediaItem item, String qloudPath) {
        if (item.size() > 0 || !(item instanceof MediaItem.Folder)) {
            return item.size();
        }
        try {
            Path absolutePath = resolvePathParam(toNyxPath(qloudPath), pathSecurity, virtualPathResolver);
            if (!Files.isDirectory(absolutePath)) {
                return item.size();
            }
            try (var children = Files.list(absolutePath)) {
                return children.count();
            }
        } catch (RuntimeException | java.io.IOException ignored) {
            return item.size();
        }
    }

    private Long qloudModifiedAt(MediaItem item, String qloudPath) {
        if (item.modifiedAt() != null) {
            return item.modifiedAt();
        }
        try {
            Path absolutePath = resolvePathParam(toNyxPath(qloudPath), pathSecurity, virtualPathResolver);
            return Files.getLastModifiedTime(absolutePath).toMillis();
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }

    private Map<String, Object> mediaInfo(String qloudPath, Object seek, boolean includeImage) {
        String nyxPath = toNyxPath(qloudPath);
        Path absolutePath = resolvePathParam(nyxPath, pathSecurity, virtualPathResolver);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("path", toQloudPath(nyxPath));
        info.put("title", titleFromPath(absolutePath));
        if (Files.isDirectory(absolutePath)) {
            info.put("type", "video-folder");
            info.put("size", 0L);
            return info;
        }

        String mimeType = MediaTypes.detectMimeType(absolutePath);
        info.put("type", qloudType(mimeType));
        info.put("size", fileSize(absolutePath));
        ProbeResult probe = safeProbe(absolutePath, mimeType);
        if (probe != null) {
            info.put("metadata", qloudMetadata(probe));
            info.putAll(durationMetadata(probe, fileSize(absolutePath)));
            if (MediaTypes.isVideo(mimeType)) {
                info.put("video", videoMetadata(probe, seek));
            }
        } else {
            info.put("metadata", Map.of());
        }
        if (includeImage) {
            String image = thumbnailBase64(absolutePath);
            if (image != null) {
                info.put("image", image);
            }
        }
        return info;
    }

    private Map<String, Object> imageItem(String qloudPath) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", qloudPath);
        try {
            Path absolutePath = resolvePathParam(toNyxPath(qloudPath), pathSecurity, virtualPathResolver);
            String image = thumbnailBase64(absolutePath);
            if (image != null) {
                item.put("image", image);
                return item;
            }
        } catch (RuntimeException ignored) {
        }
        item.put("error", "err.media");
        return item;
    }

    private Map<String, Object> videoPlaybackMetadata(Path absolutePath, Object seek) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String mimeType = MediaTypes.detectMimeType(absolutePath);
        ProbeResult probe = safeProbe(absolutePath, mimeType);
        long size = fileSize(absolutePath);
        if (probe != null) {
            metadata.putAll(durationMetadata(probe, size));
            Map<String, Object> video = videoMetadata(probe, seek);
            metadata.putAll(flattenVideoSummary(video));
        } else {
            metadata.put("duration", 0.0);
        }
        return metadata;
    }

    private Map<String, Object> durationMetadata(Path absolutePath) {
        String mimeType = MediaTypes.detectMimeType(absolutePath);
        ProbeResult probe = safeProbe(absolutePath, mimeType);
        return probe == null ? Map.of("duration", 0.0) : durationMetadata(probe, fileSize(absolutePath));
    }

    private Map<String, Object> durationMetadata(ProbeResult probe, long sizeBytes) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        double duration = Math.max(0.0, probe.getDurationSecs());
        metadata.put("duration", duration);
        if (duration > 0.0 && sizeBytes > 0) {
            metadata.put("bps", Math.round((sizeBytes * 8.0) / duration));
        }
        return metadata;
    }

    private Map<String, Object> videoMetadata(ProbeResult probe, Object seek) {
        Map<String, Object> video = new LinkedHashMap<>();
        VideoStream stream = probe.getStreams().getVideo().isEmpty() ? null : probe.getStreams().getVideo().getFirst();
        List<AudioStream> audioStreams = probe.getStreams().getAudio();
        if (stream != null) {
            video.put("width", stream.getWidth());
            video.put("height", stream.getHeight());
            video.put("fps", stream.getFps());
            video.put("aspect", 1);
        }
        video.put("time", videoPreviewTime(probe, seek));
        video.put("fast-playable", false);
        List<String> languages = audioStreams.stream()
            .map(AudioStream::getLanguage)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
        video.put("audio-langs", languages.isEmpty() ? "*" : String.join(",", languages));
        return video;
    }

    private static Map<String, Object> qloudMetadata(ProbeResult probe) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        probe.getTags().forEach((key, value) -> {
            if (key != null && value != null) {
                metadata.put(key.toUpperCase(Locale.ROOT), value);
            }
        });
        return metadata;
    }

    private static double videoPreviewTime(ProbeResult probe, Object seek) {
        double requested = doubleValue(seek, -1.0);
        if (requested >= 0.0) {
            return requested;
        }
        double duration = Math.max(0.0, probe.getDurationSecs());
        if (duration <= 0.0) {
            return 0.0;
        }
        return Math.min(123.04, duration / 4.0);
    }

    private Map<String, Object> flattenVideoSummary(Map<String, Object> video) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyIfPresent(video, metadata, "width");
        copyIfPresent(video, metadata, "height");
        copyIfPresent(video, metadata, "aspect");
        return metadata;
    }

    private ProbeResult safeProbe(Path absolutePath, String mimeType) {
        if (mediaProber == null || !(MediaTypes.isVideo(mimeType) || MediaTypes.isAudio(mimeType))) {
            return null;
        }
        try {
            return MediaProberInterop.probeCachedOrThrow(mediaProber, absolutePath);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String thumbnailBase64(Path absolutePath) {
        if (thumbnailService == null || !Files.isRegularFile(absolutePath)) {
            return null;
        }
        try {
            byte[] bytes = thumbnailService.getThumbnail(absolutePath, thumbnailService.getPrimaryThumbnailSize());
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String previewBase64(Path absolutePath, double seekSeconds) {
        if (videoPreviewService == null || !Files.isRegularFile(absolutePath)) {
            return null;
        }
        try {
            long positionMillis = Math.max(0L, Math.round(seekSeconds * 1000.0));
            byte[] bytes = videoPreviewService
                .getPreview(absolutePath, new VideoPreviewRequest(positionMillis, null, 480, null))
                .bytes();
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private MediaObject resolveMediaObject(Path absolutePath) {
        return mediaObjectResolver == null
            ? null
            : mediaObjectResolver.resolveOrCreate(absolutePath, MediaObjectResolveOptions.DEFAULT);
    }

    private PlaybackRequest playbackRequest(Path absolutePath, MediaObject mediaObject, Map<String, Object> request) {
        MediaSourceRef source = new MediaSourceRef(
            absolutePath.toString(),
            null,
            mediaObject == null ? null : mediaObject.objectId(),
            mediaObject == null ? null : mediaObject.mediaKind()
        );
        PlaybackSelection selection = playbackSelectionForRequest(absolutePath, request);
        return new PlaybackRequest(
            source,
            Math.max(0L, Math.round(doubleValue(request.get("seek"), 0.0) * 1000.0)),
            new PlaybackOutputPreferences(
                Set.of(StreamingProtocol.HLS),
                StreamingProtocol.HLS,
                true,
                qloudRepresentation
            ),
            null,
            null,
            selection,
            null,
            new TranscodePreferences(profileForRequest(request), null, null)
        );
    }

    private static QloudLegacyPlaybackFields qloudLegacyPlaybackFields(StreamRepresentationTraits traits) {
        boolean mpegTsSegments = traits.segmentContainer() == StreamSegmentContainer.MPEG_TS;
        return new QloudLegacyPlaybackFields(
            mpegTsSegments ? "httplive" : "hls",
            mpegTsSegments ? 0 : false,
            mpegTsSegments ? 1 : null
        );
    }

    private static StreamRepresentation configuredQloudRepresentation() {
        return booleanPropertyOrEnv(QLOUD_LEGACY_TS_HLS_PROPERTY, QLOUD_LEGACY_TS_HLS_ENV)
            ? StreamRepresentation.HLS_MPEG_TS
            : StreamRepresentation.HLS_FMP4;
    }

    private CompatibilityAccess authorizeCompatibilityRequest(
        RoutingCall call,
        Map<String, Object> request,
        boolean allowSessionBridge
    ) {
        pruneExpiredHlsBridges(Instant.now());
        if (!compatibilityAuthRequired()) {
            return CompatibilityAccess.allowed(null);
        }

        String authorization = call.getRequest().getHeaders().get(HttpHeaders.Authorization);
        if (authorization != null && !authorization.isBlank()) {
            UserIdPrincipal principal = ApplicationRuntime.authenticateRequest(
                authorization,
                serverConfig.getAuth(),
                runtimeUsers,
                authProviders
            );
            if (principal == null) {
                respondUnauthorized(call);
                return CompatibilityAccess.denied();
            }
            return CompatibilityAccess.allowed(principal.getName());
        }

        if (allowSessionBridge) {
            String rpcSession = stringValue(request.get("session"));
            String owner = compatibilitySessionOwner(rpcSession);
            if (owner != null) {
                return CompatibilityAccess.allowed(owner);
            }
        }

        respondUnauthorized(call);
        return CompatibilityAccess.denied();
    }

    private boolean compatibilityAuthRequired() {
        return serverConfig.getAuth().getEnabled() && !authProviders.isEmpty();
    }

    private CompatibilitySession issueCompatibilitySession(String owner) {
        return compatibilitySessionPolicy.issue(owner);
    }

    private String compatibilitySessionOwner(String rpcSession) {
        return compatibilitySessionPolicy.owner(rpcSession);
    }

    private CompatibilitySession compatibilitySession(String rpcSession, Instant now) {
        return compatibilitySessionPolicy.session(rpcSession, now);
    }

    private void pruneExpiredCompatibilitySessions(Instant now) {
        compatibilitySessionPolicy.prune(now);
    }

    private static void respondUnauthorized(RoutingCall call) {
        call.respond(
            HTTP_UNAUTHORIZED,
            new ErrorResponse(new ErrorDetail("UNAUTHORIZED", "Authentication required", Map.of()))
        );
        call.abort();
    }

    private String profileForRequest(Map<String, Object> request) {
        Map<String, String> qualityPresets = serverConfig.getFfmpeg().getQualityPresets().isEmpty()
            ? FfmpegConfig.DEFAULT_QUALITY_PRESETS
            : serverConfig.getFfmpeg().getQualityPresets();
        String quality = qualityForBandwidth(longValue(request.get("bandwidth"), 0L));
        String profile = qualityPresets.get(quality);
        if (isQloudCompatibleProfile(profile)) {
            return profile;
        }
        String fallback = qloudCompatibleFallbackProfile(qualityPresets, quality);
        if (fallback != null) {
            return fallback;
        }
        return "h264_balanced";
    }

    private PlaybackSelection playbackSelectionForRequest(Path absolutePath, Map<String, Object> request) {
        String mimeType = MediaTypes.detectMimeType(absolutePath);
        ProbeResult probe = safeProbe(absolutePath, mimeType);
        return new PlaybackSelection(
            audioSelectionForRequest(request, probe),
            subtitleSelectionForRequest(request, probe)
        );
    }

    private AudioTrackSelection audioSelectionForRequest(Map<String, Object> request, ProbeResult probe) {
        List<String> requestedLanguages = requestLanguagePreferences(request.get("audio-lang"));
        if (requestedLanguages.isEmpty()) {
            return new AudioTrackSelection();
        }
        if (requestedLanguages.size() == 1) {
            String keyword = requestedLanguages.getFirst();
            if ("all".equals(keyword) || "*".equals(keyword)) {
                return new AudioTrackSelection(AudioTrackSelectionMode.ALL);
            }
            if ("default".equals(keyword)) {
                return new AudioTrackSelection(AudioTrackSelectionMode.DEFAULT);
            }
        }
        if (probe != null) {
            Integer trackIndex = firstMatchingAudioTrackIndex(probe.getStreams().getAudio(), requestedLanguages);
            if (trackIndex != null) {
                return new AudioTrackSelection(AudioTrackSelectionMode.SPECIFIC, List.of(trackIndex));
            }
        }
        return new AudioTrackSelection();
    }

    private SubtitleSelection subtitleSelectionForRequest(Map<String, Object> request, ProbeResult probe) {
        List<String> requestedLanguages = requestLanguagePreferences(request.get("subtitle-lang"));
        boolean burnInRequested = booleanValue(request.get("uniform-subtitle"), false);
        if (requestedLanguages.size() == 1 && isSubtitleDisableKeyword(requestedLanguages.getFirst())) {
            return new SubtitleSelection(SubtitleSelectionMode.DISABLE, null);
        }
        SubtitleSelectionMode mode = burnInRequested ? SubtitleSelectionMode.BURN_IN : SubtitleSelectionMode.EXTRACT;
        if (requestedLanguages.isEmpty()) {
            return burnInRequested ? new SubtitleSelection(mode, null) : new SubtitleSelection();
        }
        Integer trackIndex = probe == null ? null : firstMatchingSubtitleTrackIndex(probe.getStreams().getSubtitle(), requestedLanguages);
        return new SubtitleSelection(mode, trackIndex);
    }

    private static Integer firstMatchingAudioTrackIndex(List<AudioStream> audioStreams, List<String> requestedLanguages) {
        for (String requestedLanguage : requestedLanguages) {
            for (AudioStream stream : audioStreams) {
                if (languageMatches(requestedLanguage, stream.getLanguage())) {
                    return stream.getIndex();
                }
            }
        }
        return null;
    }

    private static Integer firstMatchingSubtitleTrackIndex(List<SubtitleStream> subtitleStreams, List<String> requestedLanguages) {
        for (String requestedLanguage : requestedLanguages) {
            for (SubtitleStream stream : subtitleStreams) {
                if (languageMatches(requestedLanguage, stream.getLanguage())) {
                    return stream.getIndex();
                }
            }
        }
        return null;
    }

    private static List<String> requestLanguagePreferences(Object value) {
        String text = stringValue(value);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> languages = new ArrayList<>();
        for (String segment : text.split(",")) {
            String normalized = normalizeLanguageToken(segment);
            if (!normalized.isBlank()) {
                languages.add(normalized);
            }
        }
        return languages;
    }

    private static boolean isSubtitleDisableKeyword(String value) {
        return switch (value) {
            case "disable", "disabled", "none", "off" -> true;
            default -> false;
        };
    }

    private static boolean languageMatches(String requestedLanguage, String actualLanguage) {
        if (requestedLanguage == null || requestedLanguage.isBlank() || actualLanguage == null || actualLanguage.isBlank()) {
            return false;
        }
        String requested = normalizeLanguageToken(requestedLanguage);
        String actual = normalizeLanguageToken(actualLanguage);
        if (requested.equals(actual)) {
            return true;
        }
        String requestedIso3 = iso3Language(requested);
        String actualIso3 = iso3Language(actual);
        return requestedIso3 != null && requestedIso3.equals(actualIso3);
    }

    private static String normalizeLanguageToken(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = normalized.indexOf('-');
        return dash >= 0 ? normalized.substring(0, dash) : normalized;
    }

    private static String iso3Language(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() == 3) {
            return value;
        }
        try {
            return Locale.of(value).getISO3Language().toLowerCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String qloudCompatibleFallbackProfile(Map<String, String> qualityPresets, String requestedQuality) {
        if ("high".equals(requestedQuality)) {
            String medium = qualityPresets.get("medium");
            if (isQloudCompatibleProfile(medium)) {
                return medium;
            }
        }
        String low = qualityPresets.get("low");
        if (isQloudCompatibleProfile(low)) {
            return low;
        }
        String medium = qualityPresets.get("medium");
        if (isQloudCompatibleProfile(medium)) {
            return medium;
        }
        return qualityPresets.values().stream()
            .filter(QloudCompatibilityRoutes::isQloudCompatibleProfile)
            .findFirst()
            .orElse(null);
    }

    private static boolean isQloudCompatibleProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return false;
        }
        Profile profile = TranscodeProfiles.findByName(profileName);
        if (profile != null) {
            return profile.getVideoCodec() instanceof VideoCodec.H264;
        }
        String normalized = profileName.toLowerCase(Locale.ROOT);
        if (normalized.contains("h265") || normalized.contains("hevc") || normalized.contains("av1")) {
            return false;
        }
        return true;
    }

    private void scheduleCompatibilityCleanup(ScheduledExecutorService cleanupScheduler) {
        if (cleanupScheduler == null) {
            return;
        }
        long cleanupIntervalMs = Math.min(
            QloudCompatibilitySessionPolicy.CLEANUP_INTERVAL.toMillis(),
            QloudHlsBridgePolicy.CLEANUP_INTERVAL.toMillis()
        );
        cleanupScheduler.scheduleWithFixedDelay(
            this::runScheduledCompatibilityCleanup,
            cleanupIntervalMs,
            cleanupIntervalMs,
            TimeUnit.MILLISECONDS
        );
    }

    private void runScheduledCompatibilityCleanup() {
        Instant now = Instant.now();
        try {
            pruneExpiredCompatibilitySessions(now);
            pruneExpiredHlsBridges(now);
        } catch (RuntimeException exception) {
            LOGGER.warn("Scheduled Qloud compatibility cleanup failed", exception);
        }
    }

    private void registerHlsBridge(HlsBridgeSession bridge) {
        hlsBridgePolicy.register(bridge);
    }

    private void closeHlsBridge(HlsBridgeSession bridge) {
        hlsBridgePolicy.close(bridge);
    }

    private HlsBridgeSession findBridgeForClose(String owner, String rpcSession, String qloudPath) {
        return hlsBridgePolicy.findForClose(owner, rpcSession, qloudPath);
    }

    private HlsBridgeSession hlsBridge(String token) {
        return hlsBridgePolicy.bridge(token);
    }

    private void pruneExpiredHlsBridges(Instant now) {
        hlsBridgePolicy.prune(now);
    }

    private PlaybackDeliveryReadyManifest openQloudPlayback(PlaybackRequest playbackRequest, String owner) {
        PlaybackDeliveryOutcome outcome = playbackDeliveryService.open(new PlaybackDeliveryRequest(
            playbackRequest,
            owner,
            PlaybackDeliveryReadiness.hlsManifestWithBackingJob(),
            qloudStartupPolicy(),
            qloudStartupLeasePolicy()
        ));
        if (outcome instanceof PlaybackDeliveryReadyManifest ready) {
            return ready;
        }
        if (outcome instanceof PlaybackDeliveryFailed failed) {
            throw deliveryFailure(failed);
        }
        if (outcome instanceof PlaybackDeliveryTerminated terminated) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, terminated.message());
        }
        if (outcome instanceof PlaybackDeliveryPending pending) {
            throw nyxException(
                ErrorCode.TRANSCODE_FAILED,
                "Playback session did not produce an HLS manifest before the compatibility timeout: " + pending.sessionId()
            );
        }
        throw nyxException(ErrorCode.TRANSCODE_FAILED, "Playback delivery did not produce a Qloud-compatible HLS manifest");
    }

    private static PlaybackDeliveryStartupPolicy qloudStartupPolicy() {
        return new PlaybackDeliveryStartupPolicy(
            manifestPollAttempts,
            manifestPollDelayMs,
            PlaybackDeliveryTimeoutAction.FAIL,
            null
        );
    }

    private static PlaybackDeliveryLeasePolicy qloudStartupLeasePolicy() {
        return new PlaybackDeliveryLeasePolicy(
            null,
            0L,
            false,
            false,
            true,
            true,
            false
        );
    }

    private static RuntimeException deliveryFailure(PlaybackDeliveryFailed failed) {
        ErrorCode errorCode = "JOB_NOT_FOUND".equals(failed.failureCode())
            ? ErrorCode.JOB_NOT_FOUND
            : ErrorCode.TRANSCODE_FAILED;
        return nyxException(errorCode, failed.message());
    }

    private String rewriteMasterPlaylist(String manifest, HlsBridgeSession bridge, String baseUrl) {
        StringBuilder builder = new StringBuilder();
        for (String line : manifest.split("\\R", -1)) {
            if (line.isBlank() || line.startsWith("#")) {
                builder.append(line).append('\n');
                continue;
            }
            String representationId = representationId(line);
            builder
                .append(qloudResourceUrl(baseUrl, bridge.token(), bridge.masterResource() + "/" + representationId + ".m3u8"))
                .append('\n');
        }
        return builder.toString();
    }

    private String rewriteMediaPlaylist(
        String playlist,
        HlsBridgeSession bridge,
        String representationId,
        String baseUrl
    ) {
        StringBuilder builder = new StringBuilder();
        for (String line : playlist.split("\\R", -1)) {
            if (line.startsWith("#EXT-X-MAP:")) {
                builder.append(rewriteUriAttribute(line, uri -> qloudResourceUrl(
                    baseUrl,
                    bridge.token(),
                    bridge.masterResource() + "/" + representationId + "/" + uri
                ))).append('\n');
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                builder.append(line).append('\n');
                continue;
            }
            builder
                .append(qloudResourceUrl(baseUrl, bridge.token(), bridge.masterResource() + "/" + representationId + "/" + line))
                .append('\n');
        }
        return builder.toString();
    }

    private static String rewriteUriAttribute(String line, UriRewriter rewriter) {
        String marker = "URI=\"";
        int start = line.indexOf(marker);
        if (start < 0) {
            return line;
        }
        int valueStart = start + marker.length();
        int valueEnd = line.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return line;
        }
        String uri = line.substring(valueStart, valueEnd);
        return line.substring(0, valueStart) + rewriter.rewrite(uri) + line.substring(valueEnd);
    }

    private static String representationId(String uri) {
        String withoutQuery = uri;
        int queryIndex = withoutQuery.indexOf('?');
        if (queryIndex >= 0) {
            withoutQuery = withoutQuery.substring(0, queryIndex);
        }
        int slashIndex = withoutQuery.lastIndexOf('/');
        String fileName = slashIndex >= 0 ? withoutQuery.substring(slashIndex + 1) : withoutQuery;
        return fileName.endsWith(".m3u8") ? fileName.substring(0, fileName.length() - ".m3u8".length()) : fileName;
    }

    private static String qloudResourceUrl(String baseUrl, String token, String resource) {
        return baseUrl + "/" + encodePathSegment(token) + "/" + encodePathPreservingSlash(resource);
    }

    private static String encodePathPreservingSlash(String resource) {
        String[] segments = resource.split("/", -1);
        List<String> encoded = new ArrayList<>(segments.length);
        for (String segment : segments) {
            encoded.add(encodePathSegment(segment));
        }
        return String.join("/", encoded);
    }

    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] leftPad(byte[] value, int length) {
        byte[] normalized = new byte[length];
        int sourceOffset = Math.max(0, value.length - length);
        int copyLength = Math.min(value.length, length);
        System.arraycopy(value, sourceOffset, normalized, length - copyLength, copyLength);
        return normalized;
    }

    private static String normalizeHex(String value) {
        return value.replaceAll("\\s+", "");
    }

    private static URI configuredHandshakeUpstream() {
        String value = firstNonBlank(
            System.getProperty(QLOUD_HANDSHAKE_UPSTREAM_PROPERTY),
            System.getenv(QLOUD_HANDSHAKE_UPSTREAM_ENV)
        );
        if (value == null) {
            return null;
        }
        String normalized = value.endsWith("/") ? value : value + "/";
        return URI.create(normalized);
    }

    private static boolean booleanPropertyOrEnv(String property, String env) {
        String value = firstNonBlank(System.getProperty(property), System.getenv(env));
        return value != null && Boolean.parseBoolean(value);
    }

    private static String handshakeToken(String path, String requestText) {
        return tokenValue(handshakeResponse(path, requestText));
    }

    private static Map<String, Object> handshakeResponse(String path, String requestText) {
        URI upstream = configuredHandshakeUpstream();
        if (upstream == null) {
            return null;
        }
        HttpRequest request = HttpRequest.newBuilder(upstream.resolve(path))
            .timeout(QLOUD_HANDSHAKE_TIMEOUT)
            .header("Accept", "application/json")
            .header("Accept-Encoding", "identity")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestText, StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<String> response = QLOUD_HANDSHAKE_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            return requestMap(response.body());
        } catch (IOException | RuntimeException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String tokenValue(Map<String, Object> response) {
        if (response == null) {
            return null;
        }
        String token = stringValue(response.get("token"));
        return token == null || token.isBlank() ? null : token;
    }

    private void respondPending(RoutingCall call) {
        call.getResponse().header(HttpHeaders.RetryAfter, "2");
        call.getContext().disableCompression();
        call.respond(HTTP_ACCEPTED, Map.of("status", "pending", "retry_after", 2));
    }

    private String baseUrl(RoutingCall call) {
        return requestOrigin(call).baseUrl();
    }

    // Qloud auth and playback URLs must advertise the same compatibility-listener origin.
    private QloudOriginPolicy.RequestOrigin requestOrigin(RoutingCall call) {
        return originPolicy.from(call);
    }

    private static QloudOriginPolicy.HostPort parseHostPort(String authority) {
        return QloudOriginPolicy.parseHostPort(authority);
    }

    private static QloudOriginPolicy.HostPort parseAuthority(String authority) {
        return QloudOriginPolicy.parseAuthority(authority);
    }

    private static boolean sameHost(String left, String right) {
        return QloudOriginPolicy.sameHost(left, right);
    }

    private static Integer forwardedHostFallbackPort(
        QloudOriginPolicy.HostPort forwardedHost,
        QloudOriginPolicy.HostPort hostHeader
    ) {
        return QloudOriginPolicy.forwardedHostFallbackPort(forwardedHost, hostHeader);
    }

    private static boolean isLoopbackHost(String host) {
        return QloudOriginPolicy.isLoopbackHost(host);
    }

    private static String normalizedScheme(String scheme) {
        return QloudOriginPolicy.normalizedScheme(scheme);
    }

    private static int defaultPort(String scheme) {
        return QloudOriginPolicy.defaultPort(scheme);
    }

    private String serverName() {
        return "UberBeast";
    }

    private String issueHelloToken() {
        byte[] tokenBytes = signedProcToken();
        return Base64.getEncoder().encodeToString(tokenBytes);
    }

    private String issueAuthToken() {
        byte[] tokenBytes = new byte[64];
        SECURE_RANDOM.nextBytes(tokenBytes);
        return Base64.getEncoder().encodeToString(tokenBytes);
    }

    private static byte[] signedProcToken() {
        byte[] payload = new byte[64];
        SECURE_RANDOM.nextBytes(payload);

        int keyBytes = 128;
        byte[] padded = new byte[keyBytes];
        padded[0] = 0;
        padded[1] = 1;
        int separatorIndex = keyBytes - payload.length - 1;
        for (int index = 2; index < separatorIndex; index++) {
            padded[index] = (byte) 0xff;
        }
        padded[separatorIndex] = 0;
        System.arraycopy(payload, 0, padded, separatorIndex + 1, payload.length);

        BigInteger message = new BigInteger(1, padded);
        return leftPad(message.modPow(PROC_RSA_PRIVATE_EXPONENT, PROC_RSA_MODULUS).toByteArray(), keyBytes);
    }

    private static String issueHlsToken() {
        return Long.toUnsignedString(ThreadLocalRandom.current().nextLong());
    }

    private static String issueCompatibilitySessionId() {
        byte[] tokenBytes = new byte[32];
        SECURE_RANDOM.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private static String clientSessionKey(String owner, String rpcSession, String qloudPath) {
        return valueOrDefault(owner, "") + "|" + valueOrDefault(rpcSession, "") + "|" + qloudPath;
    }

    static String bridgeClientKey(String owner, String rpcSession, String qloudPath) {
        if ((owner == null || owner.isBlank()) && (rpcSession == null || rpcSession.isBlank())) {
            return null;
        }
        return clientSessionKey(owner, rpcSession, qloudPath);
    }

    private static String masterResource(String qloudPath) {
        String normalized = qloudPath.startsWith("/") ? qloudPath.substring(1) : qloudPath;
        return normalized + QLOUD_MASTER_SUFFIX;
    }

    private static String segmentName(String segmentUri) {
        String normalized = segmentUri.startsWith("segments/") ? segmentUri.substring("segments/".length()) : segmentUri;
        int queryIndex = normalized.indexOf('?');
        return queryIndex >= 0 ? normalized.substring(0, queryIndex) : normalized;
    }

    private static String qloudType(MediaItem item) {
        return QloudResponseShape.qloudType(item);
    }

    private static String qloudType(String mimeType) {
        return QloudResponseShape.qloudType(mimeType);
    }

    private static String toNyxPath(String qloudPath) {
        return QloudPathPolicy.toNyxPath(qloudPath);
    }

    private static String toQloudPath(String nyxPath) {
        return QloudPathPolicy.toQloudPath(nyxPath);
    }

    private static String parentNyxPath(String nyxPath) {
        return QloudPathPolicy.parentNyxPath(nyxPath);
    }

    private static String titleFromPath(Path path) {
        return QloudResponseShape.titleFromPath(path);
    }

    private static String qualityForBandwidth(long bandwidth) {
        if (bandwidth > 0 && bandwidth <= 1_000_000L) {
            return "low";
        }
        if (bandwidth >= 4_000_000L) {
            return "high";
        }
        return "medium";
    }

    private static BrowseSortOrder sortOrder(Map<String, Object> request) {
        String raw = stringValue(request.get("sort"));
        if (raw == null) {
            return BrowseSortOrder.NAME;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "date", "time", "modified", "last-modified" -> BrowseSortOrder.DATE;
            case "size" -> BrowseSortOrder.SIZE;
            default -> BrowseSortOrder.NAME;
        };
    }

    private static MediaTypeFilter mediaFilter(Map<String, Object> request) {
        String raw = stringValue(request.get("media"));
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "video", "videos" -> MediaTypeFilter.VIDEO;
            case "music", "audio" -> MediaTypeFilter.MUSIC;
            case "image", "images", "photo", "photos" -> MediaTypeFilter.IMAGE;
            default -> null;
        };
    }

    private static int listLimit(Map<String, Object> request) {
        int cache = intValue(request.get("cache"), DEFAULT_LIST_LIMIT, 1, 1000);
        int folders = intValue(request.get("cache-folders"), DEFAULT_LIST_LIMIT, 1, 1000);
        return Math.max(cache, folders);
    }

    private static int recentLimit(Map<String, Object> request) {
        return intValue(request.get("limit"), DEFAULT_SEARCH_LIMIT, 1, 1000);
    }

    private static String normalizeSearchPattern(String pattern) {
        String normalized = pattern.trim();
        while (normalized.startsWith("*")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("*")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.trim();
    }

    private static long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private static String defaultMimeType(String path) {
        String mimeType = MediaTypes.mimeTypeForPath(path);
        return mimeType == null ? MediaTypes.APPLICATION_OCTET_STREAM : mimeType;
    }

    private static Map<String, Object> requestMap(RoutingCall call) {
        return requestMap(call.receiveText());
    }

    private static Map<String, Object> requestMap(String requestText) {
        Object body;
        try {
            body = JSON.readValue(requestText, Map.class);
        } catch (Exception exception) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Expected JSON object request");
        }
        if (!(body instanceof Map<?, ?> raw)) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Expected JSON object request");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                request.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return request;
    }

    private static Map<String, Object> responseBase(Map<String, Object> request, String defaultAction) {
        return QloudResponseShape.responseBase(request, defaultAction);
    }

    private static void respondJson(RoutingCall call, Map<String, Object> response) {
        call.getContext().disableCompression();
        call.respond(response);
    }

    private static void copyVideoRequestEchoes(Map<String, Object> request, Map<String, Object> response) {
        QloudResponseShape.copyVideoRequestEchoes(request, response);
    }

    private static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        QloudResponseShape.copyIfPresent(source, target, key);
    }

    private static String requireString(Map<String, Object> request, String key) {
        String value = stringValue(request.get(key));
        if (value == null || value.isBlank()) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required field: " + key);
        }
        return value;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
            .map(QloudCompatibilityRoutes::stringValue)
            .filter(item -> item != null && !item.isBlank())
            .toList();
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null || second.isBlank() ? null : second;
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Object valueOrDefault(Object value, Object defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private static int intValue(Object value, int defaultValue, int min, int max) {
        long parsed = longValue(value, defaultValue);
        if (parsed < min) {
            return min;
        }
        if (parsed > max) {
            return max;
        }
        return (int) parsed;
    }

    private static long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static int parsePort(String raw, int defaultValue) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static Integer explicitPort(String raw) {
        return QloudOriginPolicy.explicitPort(raw);
    }

    private static String firstForwardedHeader(String value) {
        return QloudOriginPolicy.firstForwardedHeader(value);
    }

    private static void sleepManifestPoll() {
        long delayMs = manifestPollDelayMs;
        if (delayMs == 0L) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(interruptedException);
        }
    }

    private static AutoCloseable useManifestPollingForTesting(int attempts, long delayMs) {
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

    private static Consumer<RouteHandlerScope> handler(RouteHandler handler) {
        return handler::accept;
    }

    private static RuntimeException nyxException(ErrorCode errorCode, String message) {
        return sneakyThrow(new NyxException(errorCode, message));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    record HlsBridgeSession(
        String token,
        String rpcSession,
        String owner,
        String playbackSessionId,
        String jobId,
        String qloudPath,
        String nyxPath,
        String masterResource,
        Instant createdAt,
        AtomicReference<Instant> lastAccessedAt,
        String clientLookupKey,
        AtomicBoolean closed
    ) {
        HlsBridgeSession(
            String token,
            String rpcSession,
            String owner,
            String playbackSessionId,
            String jobId,
            String qloudPath,
            String nyxPath,
            String masterResource,
            Instant createdAt,
            String clientLookupKey
        ) {
            this(
                token,
                rpcSession,
                owner,
                playbackSessionId,
                jobId,
                qloudPath,
                nyxPath,
                masterResource,
                createdAt,
                new AtomicReference<>(createdAt),
                clientLookupKey,
                new AtomicBoolean(false)
            );
        }

        boolean isExpiredAt(Instant now) {
            return lastAccessedAt.get().plus(HLS_BRIDGE_TTL).isBefore(now);
        }

        void touch(Instant now) {
            lastAccessedAt.set(now);
        }

        boolean markClosed() {
            return closed.compareAndSet(false, true);
        }
    }

    record CompatibilitySession(
        String rpcSession,
        String owner,
        Instant authenticatedAt,
        AtomicReference<Instant> lastAccessedAt
    ) {
        CompatibilitySession(String rpcSession, String owner, Instant authenticatedAt) {
            this(rpcSession, owner, authenticatedAt, new AtomicReference<>(authenticatedAt));
        }

        boolean isExpiredAt(Instant now) {
            return lastAccessedAt.get().plus(COMPATIBILITY_SESSION_TTL).isBefore(now);
        }

        void touch(Instant now) {
            lastAccessedAt.set(now);
        }
    }

    private record CompatibilityAccess(
        boolean allowed,
        String owner
    ) {
        private static CompatibilityAccess allowed(String owner) {
            return new CompatibilityAccess(true, owner);
        }

        private static CompatibilityAccess denied() {
            return new CompatibilityAccess(false, null);
        }
    }

    private record QloudLegacyPlaybackFields(String protocol, Object precache, Integer audioBoost) {
    }

    @FunctionalInterface
    private interface RouteHandler {
        void accept(RouteHandlerScope scope);
    }

    @FunctionalInterface
    private interface UriRewriter {
        String rewrite(String uri);
    }
}
