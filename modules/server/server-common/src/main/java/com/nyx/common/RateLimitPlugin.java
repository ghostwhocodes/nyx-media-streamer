package com.nyx.common;

import com.nyx.config.RateLimitConfig;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import java.util.Map;
import java.util.Set;

/**
 * Per-IP sliding window rate limiter.
 */
public final class RateLimitPlugin {
    public static final Set<String> DEFAULT_EXEMPT_PATHS = Set.of("/api/v1/health", "/api/v1/metrics");

    private final RateLimitConfig config;
    private final Set<String> exemptPaths;
    private final SlidingWindowCounter counter = new SlidingWindowCounter();

    public RateLimitPlugin(RateLimitConfig config) {
        this(config, DEFAULT_EXEMPT_PATHS);
    }

    public RateLimitPlugin(RateLimitConfig config, Set<String> exemptPaths) {
        this.config = config;
        this.exemptPaths = Set.copyOf(exemptPaths);
    }

    public boolean allowRequest(String ip) {
        long windowMs = config.getWindowSeconds() * 1_000L;
        int maxRequests = Math.min(
            config.getBurstSize(),
            Math.toIntExact(config.getRequestsPerSecond() * config.getWindowSeconds())
        );
        return counter.tryConsume(ip, maxRequests, windowMs);
    }

    public void install(Javalin application) {
        application.unsafe.routes.beforeMatched(ctx -> {
            String path = ctx.path();
            if (isExemptPath(path)) {
                return;
            }

            String ip = ctx.ip();
            if (!allowRequest(ip)) {
                ctx.status(HttpStatus.TOO_MANY_REQUESTS);
                ctx.json(new ErrorResponse(new ErrorDetail(
                    ErrorCode.RATE_LIMITED.name(),
                    "Rate limit exceeded. Retry after " + config.getWindowSeconds() + "s.",
                    Map.of()
                )));
                ctx.skipRemainingHandlers();
            }
        });
    }

    private boolean isExemptPath(String path) {
        return exemptPaths.stream().anyMatch(exemptPath -> path.equals(exemptPath) || path.startsWith(exemptPath + "/"));
    }
}
