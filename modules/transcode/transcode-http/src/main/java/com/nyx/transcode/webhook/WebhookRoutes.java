package com.nyx.transcode.webhook;

import com.nyx.common.AuditLogger;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.http.AuthMode;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.transcode.contracts.webhook.CreateWebhookRequest;
import com.nyx.transcode.contracts.webhook.UpdateWebhookRequest;
import com.nyx.transcode.contracts.webhook.WebhookEventTypes;
import com.nyx.transcode.contracts.webhook.WebhookStore;
import com.nyx.transcode.contracts.webhook.WebhookSubscription;
import com.nyx.transcode.contracts.webhook.WebhookSubscriptionDetail;
import com.nyx.transcode.contracts.webhook.WebhookSubscriptionResponse;
import com.nyx.transcode.contracts.webhook.WebhookUrlValidator;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class WebhookRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_CREATED = HttpStatusCode.Companion.getCreated();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();
    private static final HttpStatusCode HTTP_BAD_REQUEST = HttpStatusCode.Companion.getBadRequest();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();

    private WebhookRoutes() {
    }

    public static void webhookRoutes(
        Route route,
        WebhookStore repository,
        WebhookUrlValidator urlValidator
    ) {
        webhookRoutes(route, repository, urlValidator, List.of());
    }

    public static void webhookRoutes(
        Route route,
        WebhookStore repository,
        WebhookUrlValidator urlValidator,
        List<String> authProviders
    ) {
        optionalAuth(route, authProviders, authenticatedRoute -> {
            createWebhook(authenticatedRoute, repository, urlValidator);
            listWebhooks(authenticatedRoute, repository);
            getWebhook(authenticatedRoute, repository);
            updateWebhook(authenticatedRoute, repository, urlValidator);
            deleteWebhook(authenticatedRoute, repository);
        });
    }

    private static void createWebhook(
        Route route,
        WebhookStore repository,
        WebhookUrlValidator urlValidator
    ) {
        route.post(
            "/api/v1/transcode/webhooks",
            doc(config -> {
                config.setDescription("Create a webhook subscription");
                config.request(requestDoc(request -> request.body(CreateWebhookRequest.class)));
                config.response(responseDoc(response -> {
                    response.code(HTTP_CREATED, bodyDoc(WebhookSubscriptionResponse.class));
                    response.code(HTTP_BAD_REQUEST, describe("Invalid request"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                CreateWebhookRequest request = call.receive(CreateWebhookRequest.class);
                validateUrl(urlValidator, request.getUrl());
                validateEventTypes(request.getEvents());

                String now = Instant.now().toString();
                WebhookSubscription subscription = new WebhookSubscription(
                    generateSubscriptionId(),
                    request.getUrl(),
                    request.getSecret(),
                    request.getEvents(),
                    true,
                    now,
                    now
                );
                repository.createSubscription(subscription);
                call.respond(HTTP_CREATED, toResponse(subscription));
            })
        );
    }

    private static void listWebhooks(Route route, WebhookStore repository) {
        route.get(
            "/api/v1/transcode/webhooks",
            doc(config -> {
                config.setDescription("List all webhook subscriptions");
                config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(List.class))));
            }),
            handler(scope -> scope.getCall().respond(repository.listSubscriptions().stream().map(WebhookRoutes::toResponse).toList()))
        );
    }

    private static void getWebhook(Route route, WebhookStore repository) {
        route.get(
            "/api/v1/transcode/webhooks/{id}",
            doc(config -> {
                config.setDescription("Get a webhook subscription with recent delivery history");
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(WebhookSubscriptionDetail.class));
                    response.code(HTTP_NOT_FOUND, describe("Subscription not found"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String id = requirePathParam(call, "id");
                WebhookSubscription subscription = repository.getSubscription(id);
                if (subscription == null) {
                    throw nyxException(ErrorCode.WEBHOOK_NOT_FOUND, "Webhook subscription not found: " + id);
                }
                call.respond(new WebhookSubscriptionDetail(toResponse(subscription), repository.listDeliveries(id, 20)));
            })
        );
    }

    private static void updateWebhook(
        Route route,
        WebhookStore repository,
        WebhookUrlValidator urlValidator
    ) {
        route.patch(
            "/api/v1/transcode/webhooks/{id}",
            doc(config -> {
                config.setDescription("Update a webhook subscription");
                config.request(requestDoc(request -> request.body(UpdateWebhookRequest.class)));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(WebhookSubscriptionResponse.class));
                    response.code(HTTP_NOT_FOUND, describe("Subscription not found"));
                    response.code(HTTP_BAD_REQUEST, describe("Invalid request"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String id = requirePathParam(call, "id");
                UpdateWebhookRequest request = call.receive(UpdateWebhookRequest.class);
                if (request.getUrl() != null) {
                    validateUrl(urlValidator, request.getUrl());
                }
                if (request.getEvents() != null) {
                    validateEventTypes(request.getEvents());
                }

                boolean updated = repository.updateSubscription(
                    id,
                    request.getUrl(),
                    request.getSecret(),
                    request.getEvents(),
                    request.isActive(),
                    Instant.now().toString()
                );
                if (!updated) {
                    throw nyxException(ErrorCode.WEBHOOK_NOT_FOUND, "Webhook subscription not found: " + id);
                }

                AuditLogger.log("PATCH", "/api/v1/transcode/webhooks/" + id, "anonymous", id, "200");
                call.respond(toResponse(repository.getSubscription(id)));
            })
        );
    }

    private static void deleteWebhook(Route route, WebhookStore repository) {
        route.delete(
            "/api/v1/transcode/webhooks/{id}",
            doc(config -> {
                config.setDescription("Delete a webhook subscription");
                config.response(responseDoc(response -> {
                    response.code(HTTP_NO_CONTENT, describe("Deleted"));
                    response.code(HTTP_NOT_FOUND, describe("Subscription not found"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String id = requirePathParam(call, "id");
                if (!repository.deleteSubscription(id)) {
                    throw nyxException(ErrorCode.WEBHOOK_NOT_FOUND, "Webhook subscription not found: " + id);
                }
                AuditLogger.log("DELETE", "/api/v1/transcode/webhooks/" + id, "anonymous", id, "204");
                call.respond(HTTP_NO_CONTENT);
            })
        );
    }

    private static void validateUrl(WebhookUrlValidator validator, String url) {
        try {
            validator.validateOrThrow(url);
        } catch (IllegalArgumentException exception) {
            throw nyxException(
                ErrorCode.WEBHOOK_URL_NOT_ALLOWED,
                exception.getMessage() == null ? "Invalid webhook URL" : exception.getMessage()
            );
        }
    }

    private static void validateEventTypes(List<String> events) {
        if (events.isEmpty()) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "At least one event type is required");
        }
        List<String> invalidEvents = events.stream()
            .filter(event -> !WebhookEventTypes.INSTANCE.getALL().contains(event))
            .toList();
        if (!invalidEvents.isEmpty()) {
            throw nyxException(
                ErrorCode.INVALID_REQUEST,
                "Invalid event type(s): " + invalidEvents + ". Valid types: " + WebhookEventTypes.INSTANCE.getALL()
            );
        }
    }

    private static String requirePathParam(RoutingCall call, String name) {
        String value = call.getParameters().get(name);
        if (value == null) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Missing " + name);
        }
        return value;
    }

    private static WebhookSubscriptionResponse toResponse(WebhookSubscription subscription) {
        return new WebhookSubscriptionResponse(
            subscription.getId(),
            subscription.getUrl(),
            subscription.getEvents(),
            subscription.isActive(),
            subscription.getCreatedAt(),
            subscription.getUpdatedAt()
        );
    }

    private static String generateSubscriptionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.OPTIONAL, authProviders));
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

    private static Consumer<RouteHandlerScope> handler(RouteHandler handler) {
        return handler::accept;
    }

    private static Consumer<com.nyx.http.ResponseDoc> bodyDoc(Class<?> type) {
        return response -> response.body(type);
    }

    private static Consumer<com.nyx.http.ResponseDoc> describe(String description) {
        return response -> response.setDescription(description);
    }

    private static RuntimeException nyxException(ErrorCode code, String message) {
        return sneakyThrow(new NyxException(code, message, Map.of(), null));
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
    private interface RouteHandler {
        void accept(RouteHandlerScope scope);
    }
}
