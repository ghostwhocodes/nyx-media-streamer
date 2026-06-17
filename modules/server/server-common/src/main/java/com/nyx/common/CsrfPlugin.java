package com.nyx.common;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import java.util.Map;
import java.util.Set;

/**
 * CSRF protection for state-changing endpoints.
 */
public final class CsrfPlugin {
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    public static final String REQUIRED_HEADER = "X-Requested-With";
    public static final String REQUIRED_HEADER_VALUE = "XMLHttpRequest";

    private final Set<String> exemptPaths;

    public CsrfPlugin() {
        this(Set.of());
    }

    public CsrfPlugin(Set<String> exemptPaths) {
        this.exemptPaths = Set.copyOf(exemptPaths);
    }

    public void install(Javalin application) {
        application.unsafe.routes.beforeMatched(ctx -> {
            String method = ctx.method().name();
            if (!MUTATING_METHODS.contains(method)) {
                return;
            }

            String path = ctx.path();
            if (isExemptPath(path)) {
                return;
            }

            String header = ctx.header(REQUIRED_HEADER);
            if (!REQUIRED_HEADER_VALUE.equals(header)) {
                ctx.status(HttpStatus.FORBIDDEN);
                ctx.json(new ErrorResponse(new ErrorDetail(
                    ErrorCode.PATH_NOT_ALLOWED.name(),
                    "CSRF check failed. Include '" + REQUIRED_HEADER + ": " + REQUIRED_HEADER_VALUE + "' header in mutating requests.",
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
