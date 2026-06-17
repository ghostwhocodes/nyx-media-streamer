package com.nyx.http;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class Route {
    public static final String AUTH_PRINCIPAL_ATTRIBUTE = "nyx.auth.principal";
    public static final String ROUTE_TEMPLATE_ATTRIBUTE = "nyx.route.template";
    private static final String REQUEST_ABORTED_ATTRIBUTE = "nyx.request.aborted";

    private final Javalin app;
    private final String prefix;
    private final AuthMode authMode;
    private final List<String> authProviders;
    private final OpenApiRegistry docsRegistry;
    private final TriConsumer<? super RoutingCall, ? super AuthMode, ? super List<String>> authEvaluator;

    public Route(Javalin app) {
        this(app, "", AuthMode.PUBLIC, List.of(), null, null);
    }

    public Route(Javalin app, OpenApiRegistry docsRegistry) {
        this(app, "", AuthMode.PUBLIC, List.of(), docsRegistry, null);
    }

    public Route(
        Javalin app,
        TriConsumer<? super RoutingCall, ? super AuthMode, ? super List<String>> authEvaluator
    ) {
        this(app, "", AuthMode.PUBLIC, List.of(), null, authEvaluator);
    }

    public Route(
        Javalin app,
        OpenApiRegistry docsRegistry,
        TriConsumer<? super RoutingCall, ? super AuthMode, ? super List<String>> authEvaluator
    ) {
        this(app, "", AuthMode.PUBLIC, List.of(), docsRegistry, authEvaluator);
    }

    public Route(
        Javalin app,
        String prefix,
        AuthMode authMode,
        List<String> authProviders,
        OpenApiRegistry docsRegistry,
        TriConsumer<? super RoutingCall, ? super AuthMode, ? super List<String>> authEvaluator
    ) {
        this.app = Objects.requireNonNull(app, "app");
        this.prefix = prefix == null ? "" : prefix;
        this.authMode = authMode == null ? AuthMode.PUBLIC : authMode;
        this.authProviders = authProviders == null ? List.of() : List.copyOf(authProviders);
        this.docsRegistry = docsRegistry;
        this.authEvaluator = authEvaluator;
    }

    public void route(String path, Consumer<? super Route> block) {
        block.accept(child(path));
    }

    public Route withAuth(AuthMode authMode, List<String> authProviders) {
        return new Route(app, prefix, authMode, authProviders, docsRegistry, authEvaluator);
    }

    public void get(String path, Consumer<? super RouteHandlerScope> handler) {
        register("GET", HandlerType.GET, path, noDocumentation(), handler);
    }

    public void get(
        String path,
        Consumer<? super OpenApiRouteConfig> documentation,
        Consumer<? super RouteHandlerScope> handler
    ) {
        register("GET", HandlerType.GET, path, documentation, handler);
    }

    public void post(String path, Consumer<? super RouteHandlerScope> handler) {
        register("POST", HandlerType.POST, path, noDocumentation(), handler);
    }

    public void post(
        String path,
        Consumer<? super OpenApiRouteConfig> documentation,
        Consumer<? super RouteHandlerScope> handler
    ) {
        register("POST", HandlerType.POST, path, documentation, handler);
    }

    public void put(String path, Consumer<? super RouteHandlerScope> handler) {
        register("PUT", HandlerType.PUT, path, noDocumentation(), handler);
    }

    public void put(
        String path,
        Consumer<? super OpenApiRouteConfig> documentation,
        Consumer<? super RouteHandlerScope> handler
    ) {
        register("PUT", HandlerType.PUT, path, documentation, handler);
    }

    public void patch(String path, Consumer<? super RouteHandlerScope> handler) {
        register("PATCH", HandlerType.PATCH, path, noDocumentation(), handler);
    }

    public void patch(
        String path,
        Consumer<? super OpenApiRouteConfig> documentation,
        Consumer<? super RouteHandlerScope> handler
    ) {
        register("PATCH", HandlerType.PATCH, path, documentation, handler);
    }

    public void delete(String path, Consumer<? super RouteHandlerScope> handler) {
        register("DELETE", HandlerType.DELETE, path, noDocumentation(), handler);
    }

    public void delete(
        String path,
        Consumer<? super OpenApiRouteConfig> documentation,
        Consumer<? super RouteHandlerScope> handler
    ) {
        register("DELETE", HandlerType.DELETE, path, documentation, handler);
    }

    public void sse(String path, Consumer<? super SseRouteHandlerScope> handler) {
        sse(path, noPreflight(), handler);
    }

    public void sse(
        String path,
        Consumer<? super RouteHandlerScope> preflight,
        Consumer<? super SseRouteHandlerScope> handler
    ) {
        String fullPath = fullPath(path);
        app.unsafe.routes.before(fullPath, context -> {
            context.attribute(ROUTE_TEMPLATE_ATTRIBUTE, fullPath);
            RoutingCall call = new RoutingCall(context);
            evaluateAuth(call);
            if (requestAborted(context)) {
                return;
            }
            preflight.accept(new RouteHandlerScope(call));
        });
        app.unsafe.routes.sse(fullPath, client -> {
            Context context = client.ctx();
            RoutingCall call = new RoutingCall(context);
            if (requestAborted(context)) {
                return;
            }
            handler.accept(new SseRouteHandlerScope(call, client));
        });
    }

    private void register(
        String method,
        HandlerType handlerType,
        String path,
        Consumer<? super OpenApiRouteConfig> documentation,
        Consumer<? super RouteHandlerScope> handler
    ) {
        String fullPath = fullPath(path);
        if (docsRegistry != null) {
            docsRegistry.register(method, fullPath, documentation);
        }
        app.unsafe.routes.addHttpHandler(handlerType, fullPath, context -> {
            context.attribute(ROUTE_TEMPLATE_ATTRIBUTE, fullPath);
            RoutingCall call = new RoutingCall(context);
            evaluateAuth(call);
            if (requestAborted(context)) {
                return;
            }
            handler.accept(new RouteHandlerScope(call));
        });
    }

    private void evaluateAuth(RoutingCall call) {
        if (authEvaluator != null) {
            authEvaluator.accept(call, authMode, authProviders);
        }
    }

    private boolean requestAborted(Context context) {
        return Boolean.TRUE.equals(context.attribute(REQUEST_ABORTED_ATTRIBUTE));
    }

    private Route child(String path) {
        return new Route(app, fullPath(path), authMode, authProviders, docsRegistry, authEvaluator);
    }

    private String fullPath(String path) {
        String base = trimTrailingSlashes(prefix);
        String suffix = trimLeadingSlashes(path == null ? "" : path);
        if (base.isEmpty()) {
            return normalize("/" + suffix);
        }
        if (suffix.isEmpty()) {
            return base.isEmpty() ? "/" : base;
        }
        return normalize(base + "/" + suffix);
    }

    private static String trimTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String trimLeadingSlashes(String value) {
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return value.substring(start);
    }

    private static String normalize(String value) {
        return value.replace("//", "/");
    }

    private static Consumer<OpenApiRouteConfig> noDocumentation() {
        return config -> {
        };
    }

    private static Consumer<RouteHandlerScope> noPreflight() {
        return scope -> {
        };
    }
}
