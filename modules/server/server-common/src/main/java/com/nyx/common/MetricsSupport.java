package com.nyx.common;

import io.javalin.Javalin;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class MetricsSupport {
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NUMERIC_SEGMENT_PATTERN = Pattern.compile("/[0-9]+(?=/|$)");
    private static final String ROUTE_TEMPLATE_ATTRIBUTE = "nyx.route.template";
    private static final String JAVALIN_REQUEST_START_ATTR = "nyxRequestStartNanos";

    private MetricsSupport() {
    }

    public static String normalizeRoutePath(String path) {
        String withoutUuids = UUID_PATTERN.matcher(path).replaceAll("{id}");
        return NUMERIC_SEGMENT_PATTERN.matcher(withoutUuids).replaceAll("/{n}");
    }

    public static void installMetrics(Javalin app, PrometheusMeterRegistry registry) {
        app.unsafe.routes.before(ctx -> ctx.attribute(JAVALIN_REQUEST_START_ATTR, System.nanoTime()));
        app.unsafe.routes.after(ctx -> {
            String rawPath = ctx.path();
            if (rawPath.startsWith("/api/v1/metrics") || rawPath.startsWith("/api/v1/health")) {
                return;
            }

            Long startNanos = ctx.attribute(JAVALIN_REQUEST_START_ATTR);
            if (startNanos == null) {
                return;
            }

            long durationNanos = System.nanoTime() - startNanos;
            String route = ctx.attribute(ROUTE_TEMPLATE_ATTRIBUTE);
            if (route == null || route.isBlank()) {
                try {
                    route = ctx.endpoint().path;
                } catch (RuntimeException ignored) {
                    route = null;
                }
                if (route != null && (route.isBlank() || "*".equals(route))) {
                    route = null;
                }
            }
            if (route == null) {
                route = normalizeRoutePath(rawPath);
            }

            Timer.builder("http_server_request_duration_seconds")
                .description("HTTP server request duration")
                .tag("method", ctx.method().name())
                .tag("route", route)
                .tag("status", Integer.toString(ctx.statusCode()))
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
        });
    }
}
