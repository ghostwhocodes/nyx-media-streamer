package com.nyx.admin;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.config.ConfigService;
import com.nyx.config.ConfigUpdateRequest;
import com.nyx.config.ConfigUpdateResponse;
import com.nyx.config.CreateUserRequest;
import com.nyx.config.CreateUserResponse;
import com.nyx.config.SanitizedConfig;
import com.nyx.http.AuthMode;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ConfigRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_CREATED = HttpStatusCode.Companion.getCreated();
    private static final HttpStatusCode HTTP_BAD_REQUEST = HttpStatusCode.Companion.getBadRequest();
    private static final HttpStatusCode HTTP_CONFLICT = HttpStatusCode.Companion.getConflict();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();

    private ConfigRoutes() {
    }

    public static void configRoutes(Route route, ConfigService configService) {
        configRoutes(route, configService, List.of());
    }

    public static void configRoutes(Route route, ConfigService configService, List<String> authProviders) {
        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/config",
                doc(config -> {
                    config.setDescription("Get current server configuration (sanitized, no secrets)");
                    config.response(response -> {
                        response.code(HTTP_OK, bodyDoc(SanitizedConfig.class));
                    });
                }),
                handler(scope -> scope.getCall().respond(configService.getSanitizedConfig()))
            );

            authenticatedRoute.get(
                "/api/v1/auth/users",
                doc(config -> {
                    config.setDescription("List all configured API usernames");
                    config.response(response -> {
                        response.code(HTTP_OK, bodyDoc(String[].class));
                    });
                }),
                handler(scope -> scope.getCall().respond(configService.listUsers()))
            );
        });

        requireAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.put(
                "/api/v1/config",
                doc(config -> {
                    config.setDescription("Update server configuration. CORS changes require a restart to take effect.");
                    config.request(request -> {
                        request.body(ConfigUpdateRequest.class);
                    });
                    config.response(response -> {
                        response.code(HTTP_OK, bodyDoc(ConfigUpdateResponse.class));
                    });
                }),
                handler(scope -> {
                    ensureAdminAuthConfigured(authProviders);
                    RoutingCall call = scope.getCall();
                    ConfigUpdateRequest request = call.receive(ConfigUpdateRequest.class);
                    List<String> reasons = new ArrayList<>();
                    configService.updateCorsOrigins(request.corsOrigins());
                    reasons.add("CORS origins updated — restart required for the server to reload them");
                    call.respond(new ConfigUpdateResponse(configService.getSanitizedConfig(), true, reasons));
                })
            );

            authenticatedRoute.post(
                "/api/v1/auth/users",
                doc(config -> {
                    config.setDescription("Create a new API user (takes effect immediately for BasicAuth)");
                    config.request(request -> {
                        request.body(CreateUserRequest.class);
                    });
                    config.response(response -> {
                        response.code(HTTP_CREATED, bodyDoc(CreateUserResponse.class));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid username or password"));
                        response.code(HTTP_CONFLICT, describe("User already exists"));
                    });
                }),
                handler(scope -> {
                    ensureAdminAuthConfigured(authProviders);
                    RoutingCall call = scope.getCall();
                    CreateUserRequest request = call.receive(CreateUserRequest.class);
                    if (request.username().isBlank()) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Username must not be blank");
                    }
                    if (request.password().length() < 8) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Password must be at least 8 characters");
                    }
                    boolean created = configService.createUser(request.username(), request.password());
                    if (!created) {
                        throw nyxException(ErrorCode.USER_ALREADY_EXISTS, "User already exists: " + request.username());
                    }
                    call.respond(HTTP_CREATED, new CreateUserResponse(request.username()));
                })
            );

            authenticatedRoute.delete(
                "/api/v1/auth/users/{username}",
                doc(config -> {
                    config.setDescription("Delete an API user (takes effect immediately for BasicAuth)");
                    config.request(request -> {
                        request.pathParameter("username", parameter -> {
                            parameter.setDescription("Username to delete");
                        });
                    });
                    config.response(response -> {
                        response.code(HTTP_NO_CONTENT, describe("User deleted"));
                        response.code(HTTP_NOT_FOUND, describe("User not found"));
                    });
                }),
                handler(scope -> {
                    ensureAdminAuthConfigured(authProviders);
                    RoutingCall call = scope.getCall();
                    String username = call.getParameters().get("username");
                    if (username == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing username");
                    }
                    boolean deleted = configService.deleteUser(username);
                    if (!deleted) {
                        throw nyxException(ErrorCode.USER_NOT_FOUND, "User not found: " + username);
                    }
                    call.respond(HTTP_NO_CONTENT);
                })
            );
        });
    }

    private static void ensureAdminAuthConfigured(List<String> authProviders) {
        if (authProviders.isEmpty()) {
            throw nyxException(ErrorCode.PATH_NOT_ALLOWED, "This endpoint requires authentication, but auth is not configured");
        }
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.OPTIONAL, authProviders));
    }

    private static void requireAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.REQUIRED, authProviders));
    }

    private static Consumer<OpenApiRouteConfig> doc(RouteDoc block) {
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

    private static RuntimeException nyxException(ErrorCode errorCode, String message) {
        return sneakyThrow(new NyxException(errorCode, message, java.util.Map.of(), null));
    }

    private static <T> T sneakyThrow(Throwable throwable) {
        ConfigRoutes.<RuntimeException>throwUnchecked(throwable);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
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
    private interface RouteHandler {
        void accept(RouteHandlerScope scope);
    }
}
