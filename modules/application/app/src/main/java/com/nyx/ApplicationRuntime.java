package com.nyx;

import static com.nyx.common.MetricsSupport.installMetrics;

import com.nyx.common.AuditLogger;
import com.nyx.common.CsrfPlugin;
import com.nyx.common.ErrorCode;
import com.nyx.common.ErrorDetail;
import com.nyx.common.ErrorResponse;
import com.nyx.common.ErrorResponsesConfig;
import com.nyx.common.RateLimitPlugin;
import com.nyx.common.SlidingWindowCounter;
import com.nyx.config.AuthConfig;
import com.nyx.config.AuthUtils;
import com.nyx.config.ServerConfig;
import com.nyx.http.AuthMode;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.Route;
import com.nyx.http.RoutingCall;
import com.nyx.http.TriConsumer;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.compression.Gzip;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ApplicationRuntime {
    public static final long REQUEST_BODY_MAX_BYTES = 10L * 1024 * 1024;
    public static final long AUTH_RATE_LIMIT_WINDOW_SECONDS = 60L;
    public static final int AUTH_RATE_LIMIT_MAX_FAILURES = 10;
    public static final int AUTH_RATE_LIMIT_MAX_TRACKED_KEYS = 20_000;
    public static final int MIN_COMPRESSED_RESPONSE_BYTES = 200;
    private static final Set<String> DEFAULT_RATE_LIMIT_EXEMPT_PATHS = Set.of("/api/v1/health", "/api/v1/metrics");
    private static final HttpStatusCode HTTP_UNAUTHORIZED = HttpStatusCode.Companion.getUnauthorized();
    private static final HttpStatusCode HTTP_FORBIDDEN = HttpStatusCode.Companion.getForbidden();
    private static final HttpStatusCode HTTP_PAYLOAD_TOO_LARGE = HttpStatusCode.Companion.getPayloadTooLarge();
    private static final HttpStatusCode HTTP_TOO_MANY_REQUESTS = HttpStatusCode.Companion.getTooManyRequests();

    private ApplicationRuntime() {
    }

    public static void clearAuthFailureStateForTesting() {
    }

    public static JavalinAppRuntime createConfiguredApp(
        ServerConfig serverConfig,
        AuthConfig authConfig,
        PrometheusMeterRegistry metricsRegistry,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        return createConfiguredApp(
            serverConfig,
            serverConfig.getHost(),
            serverConfig.getPort(),
            true,
            authConfig,
            metricsRegistry,
            runtimeUsers
        );
    }

    public static JavalinAppRuntime createConfiguredApp(
        ServerConfig serverConfig,
        String bindHost,
        int bindPort,
        boolean serveAdminUi,
        AuthConfig authConfig,
        PrometheusMeterRegistry metricsRegistry,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        Logger log = LoggerFactory.getLogger("com.nyx.Application");
        SlidingWindowCounter authFailureCounter = new SlidingWindowCounter(
            AUTH_RATE_LIMIT_WINDOW_SECONDS * 1_000L,
            AUTH_RATE_LIMIT_MAX_TRACKED_KEYS,
            System::currentTimeMillis
        );
        Set<String> allowedCorsOrigins = resolveAllowedCorsOrigins(serverConfig, log);

        Javalin app = Javalin.create(config -> {
            CompressionStrategy compressionStrategy = new CompressionStrategy(null, new Gzip());
            compressionStrategy.setDefaultMinSizeForCompression(MIN_COMPRESSED_RESPONSE_BYTES);
            config.startup.showJavalinBanner = false;
            config.jsonMapper(new JavalinJackson(NyxJson.newMapper(), false));
            config.http.generateEtags = false;
            config.http.maxRequestSize = REQUEST_BODY_MAX_BYTES;
            config.http.compressionStrategy = compressionStrategy;
            config.jetty.host = bindHost;
            config.jetty.port = bindPort;
            if (serveAdminUi) {
                config.staticFiles.enableWebjars();
                config.staticFiles.add("admin-ui", Location.CLASSPATH);
                config.spaRoot.addFile("/", "admin-ui/index.html", Location.CLASSPATH);
            }
        });

        ErrorResponsesConfig.configureErrorHandling(app);
        if (metricsRegistry != null) {
            installMetrics(app, metricsRegistry);
        }

        if (serverConfig.getRateLimit().getEnabled()) {
            new RateLimitPlugin(serverConfig.getRateLimit(), DEFAULT_RATE_LIMIT_EXEMPT_PATHS).install(app);
        }

        if (serverConfig.getCsrf().getEnabled() && !authConfig.getEnabled()) {
            new CsrfPlugin().install(app);
        }

        app.unsafe.routes.before(ctx -> {
            String requestId = ctx.header(HttpHeaders.XRequestId);
            if (requestId == null) {
                requestId = UUID.randomUUID().toString();
            }
            ctx.attribute(HttpHeaders.XRequestId, requestId);
            ctx.header(HttpHeaders.XRequestId, requestId);

            String origin = ctx.header("Origin");
            if (origin != null) {
                boolean sameOrigin = isSameOrigin(origin, ctx.scheme(), ctx.host());
                if (!sameOrigin && (allowedCorsOrigins.isEmpty() || !allowedCorsOrigins.contains(origin))) {
                    ctx.status(HTTP_FORBIDDEN.getValue());
                    ctx.skipRemainingHandlers();
                    return;
                }

                if (!sameOrigin) {
                    ctx.header("Access-Control-Allow-Origin", origin);
                    ctx.header("Vary", "Origin");
                    ctx.header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,PATCH,OPTIONS");
                    ctx.header("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Requested-With,X-Request-ID");
                }

                if ("OPTIONS".equals(ctx.method().name())) {
                    ctx.status(204);
                    ctx.skipRemainingHandlers();
                    return;
                }
            }

            long contentLength = ctx.contentLength();
            if (contentLength > REQUEST_BODY_MAX_BYTES) {
                ctx.status(HTTP_PAYLOAD_TOO_LARGE.getValue());
                ctx.json(new ErrorResponse(
                    new ErrorDetail(
                        ErrorCode.REQUEST_TOO_LARGE.name(),
                        "Request body exceeds limit of " + (REQUEST_BODY_MAX_BYTES / 1024 / 1024) + " MB",
                        Map.of()
                    )
                ));
                ctx.skipRemainingHandlers();
                return;
            }

            String ip = ctx.ip();
            long windowMs = AUTH_RATE_LIMIT_WINDOW_SECONDS * 1_000L;
            if (authFailureCounter.count(ip, windowMs) >= AUTH_RATE_LIMIT_MAX_FAILURES) {
                ctx.status(HTTP_TOO_MANY_REQUESTS.getValue());
                ctx.json(new ErrorResponse(
                    new ErrorDetail(
                        ErrorCode.RATE_LIMITED.name(),
                        "Too many failed authentication attempts. Try again later.",
                        Map.of()
                    )
                ));
                ctx.skipRemainingHandlers();
            }
        });

        app.unsafe.routes.afterMatched(ctx -> {
            String method = ctx.method().name();
            if (!Set.of("POST", "PUT", "DELETE", "PATCH").contains(method)) {
                return;
            }
            UserIdPrincipal principal = ctx.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE);
            String user = principal != null ? principal.getName() : "anonymous";
            AuditLogger.log(method, ctx.path(), user, null, Integer.toString(ctx.statusCode()));
        });

        return new JavalinAppRuntime(
            app,
            buildAuthEvaluator(authConfig, runtimeUsers, authFailureCounter)
        );
    }

    private static boolean isSameOrigin(String origin, String requestScheme, String requestHost) {
        try {
            URI originUri = new URI(origin);
            URI requestUri = new URI(requestScheme + "://" + requestHost);
            return originUri.getScheme() != null
                && requestUri.getScheme() != null
                && originUri.getHost() != null
                && requestUri.getHost() != null
                && originUri.getScheme().equalsIgnoreCase(requestUri.getScheme())
                && originUri.getHost().equalsIgnoreCase(requestUri.getHost())
                && effectivePort(originUri) == effectivePort(requestUri);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    public static JavalinAppRuntime createConfiguredApp(
        ServerConfig serverConfig,
        AuthConfig authConfig,
        PrometheusMeterRegistry metricsRegistry
    ) {
        return createConfiguredApp(serverConfig, authConfig, metricsRegistry, null);
    }

    public static JavalinAppRuntime createConfiguredApp(ServerConfig serverConfig) {
        return createConfiguredApp(serverConfig, serverConfig.getAuth(), null, null);
    }

    public static JavalinAppRuntime createConfiguredApp(
        ServerConfig serverConfig,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        return createConfiguredApp(serverConfig, serverConfig.getAuth(), null, runtimeUsers);
    }

    private static Set<String> resolveAllowedCorsOrigins(ServerConfig serverConfig, Logger log) {
        List<String> origins = serverConfig.getCorsOrigins();
        if (origins.stream().anyMatch("*"::equals)) {
            log.warn(
                "CORS wildcard '*' is not allowed. All cross-origin requests will be rejected. Set NYX_CORS_ORIGINS to specific origins."
            );
            return Set.of();
        }
        if (origins.isEmpty()) {
            log.warn(
                "CORS origins not configured. All cross-origin requests will be rejected. Set NYX_CORS_ORIGINS to allow specific origins."
            );
            return Set.of();
        }
        return Set.copyOf(origins);
    }

    private static TriConsumer<RoutingCall, AuthMode, List<String>> buildAuthEvaluator(
        AuthConfig authConfig,
        ConcurrentHashMap<String, String> runtimeUsers,
        SlidingWindowCounter authFailureCounter
    ) {
        return (call, authMode, authProviders) -> {
            if (authMode == AuthMode.PUBLIC || !authConfig.getEnabled() || authProviders.isEmpty()) {
                return;
            }

            String ip = call.requestIp();
            String authorization = call.getRequest().getHeaders().get(HttpHeaders.Authorization);
            UserIdPrincipal principal = authenticateRequest(authorization, authConfig, runtimeUsers, authProviders);
            if (principal != null) {
                authFailureCounter.remove(ip);
                call.attribute(Route.AUTH_PRINCIPAL_ATTRIBUTE, principal);
                return;
            }

            recordAuthFailure(authFailureCounter, ip);
            call.respond(
                HTTP_UNAUTHORIZED,
                new ErrorResponse(new ErrorDetail("UNAUTHORIZED", "Authentication required", Map.of()))
            );
            call.abort();
        };
    }

    public static UserIdPrincipal authenticateRequest(
        String authorization,
        AuthConfig authConfig,
        ConcurrentHashMap<String, String> runtimeUsers,
        List<String> authProviders
    ) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7) && authProviders.contains("api-token")) {
            return authenticateBearerRequest(authorization, authConfig);
        }
        if (authorization.regionMatches(true, 0, "Basic ", 0, 6) && authProviders.contains("api-basic")) {
            return authenticateBasicRequest(authorization, authConfig, runtimeUsers);
        }
        return null;
    }

    private static UserIdPrincipal authenticateBearerRequest(String authorization, AuthConfig authConfig) {
        String token = authorization.substring(7).trim();
        String multiTokenUserId = authConfig.getTokens().get(token);
        if (multiTokenUserId != null) {
            return new UserIdPrincipal(multiTokenUserId);
        }
        if (!authConfig.getToken().isBlank() && token.equals(authConfig.getToken())) {
            return new UserIdPrincipal("api");
        }
        return null;
    }

    private static UserIdPrincipal authenticateBasicRequest(
        String authorization,
        AuthConfig authConfig,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        String raw = authorization.substring(6).trim();
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        int separatorIndex = decoded.indexOf(':');
        String username = separatorIndex >= 0 ? decoded.substring(0, separatorIndex) : decoded;
        String password = separatorIndex >= 0 ? decoded.substring(separatorIndex + 1) : "";
        ConcurrentHashMap<String, String> users =
            runtimeUsers != null ? runtimeUsers : new ConcurrentHashMap<>(authConfig.getUsers());
        String hash = users.get(username);
        if (hash == null) {
            return null;
        }
        return AuthUtils.verifyPassword(password, hash) ? new UserIdPrincipal(username) : null;
    }

    private static void recordAuthFailure(SlidingWindowCounter counter, String ip) {
        long windowMs = AUTH_RATE_LIMIT_WINDOW_SECONDS * 1_000L;
        counter.tryConsume(ip, Integer.MAX_VALUE, windowMs);
    }

    public record JavalinAppRuntime(
        Javalin app,
        TriConsumer<RoutingCall, AuthMode, List<String>> authEvaluator
    ) {
    }
}
