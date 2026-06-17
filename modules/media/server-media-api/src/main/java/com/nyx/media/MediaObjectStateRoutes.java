package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.common.RouteUtilsJava;
import com.nyx.common.VirtualPathResolver;
import com.nyx.http.AuthMode;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.http.UserIdPrincipal;
import com.nyx.media.contracts.MediaThumbnailReference;
import com.nyx.media.contracts.UserMediaState;
import com.nyx.media.contracts.UserMediaStateEntry;
import com.nyx.media.contracts.UserMediaStateListing;
import com.nyx.media.contracts.UserMediaStateMediaSummary;
import com.nyx.media.contracts.UserMediaStateWriteRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class MediaObjectStateRoutes {
    private static final int DEFAULT_USER_MEDIA_STATE_THUMBNAIL_SIZE = 150;
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();

    private MediaObjectStateRoutes() {
    }

    public static void mediaObjectStateRoutes(
        Route route,
        MediaObjectService mediaObjectService,
        UserMediaStateService userMediaStateService
    ) {
        mediaObjectStateRoutes(
            route,
            mediaObjectService,
            userMediaStateService,
            null,
            List.of(DEFAULT_USER_MEDIA_STATE_THUMBNAIL_SIZE),
            List.of()
        );
    }

    public static void mediaObjectStateRoutes(
        Route route,
        MediaObjectService mediaObjectService,
        UserMediaStateService userMediaStateService,
        VirtualPathResolver virtualPathResolver
    ) {
        mediaObjectStateRoutes(
            route,
            mediaObjectService,
            userMediaStateService,
            virtualPathResolver,
            List.of(DEFAULT_USER_MEDIA_STATE_THUMBNAIL_SIZE),
            List.of()
        );
    }

    public static void mediaObjectStateRoutes(
        Route route,
        MediaObjectService mediaObjectService,
        UserMediaStateService userMediaStateService,
        List<String> authProviders
    ) {
        mediaObjectStateRoutes(
            route,
            mediaObjectService,
            userMediaStateService,
            null,
            List.of(DEFAULT_USER_MEDIA_STATE_THUMBNAIL_SIZE),
            authProviders
        );
    }

    public static void mediaObjectStateRoutes(
        Route route,
        MediaObjectService mediaObjectService,
        UserMediaStateService userMediaStateService,
        VirtualPathResolver virtualPathResolver,
        List<Integer> thumbnailSizes
    ) {
        mediaObjectStateRoutes(route, mediaObjectService, userMediaStateService, virtualPathResolver, thumbnailSizes, List.of());
    }

    public static void mediaObjectStateRoutes(
        Route route,
        MediaObjectService mediaObjectService,
        UserMediaStateService userMediaStateService,
        VirtualPathResolver virtualPathResolver,
        List<Integer> thumbnailSizes,
        List<String> authProviders
    ) {
        if (authProviders.isEmpty()) {
            return;
        }

        requireAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.get(
                "/api/v1/media/objects/{objectId}/state",
                doc(config -> {
                    config.setDescription("Get the current user's object-based media state");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(UserMediaState.class));
                        response.code(HTTP_NOT_FOUND, describe("Media object not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String objectId = call.getParameters().get("objectId");
                    if (objectId == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing objectId");
                    }
                    requireMediaObject(mediaObjectService, objectId);
                    call.respond(userMediaStateService.getState(currentAuthenticatedMediaStateUserId(call), objectId));
                })
            );

            authenticatedRoute.put(
                "/api/v1/media/objects/{objectId}/state",
                doc(config -> {
                    config.setDescription("Replace the current user's object-based media state");
                    config.request(requestDoc(request -> request.body(UserMediaStateWriteRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(UserMediaState.class));
                        response.code(HTTP_NOT_FOUND, describe("Media object not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String objectId = call.getParameters().get("objectId");
                    if (objectId == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing objectId");
                    }
                    requireMediaObject(mediaObjectService, objectId);
                    UserMediaStateWriteRequest request = call.receive(UserMediaStateWriteRequest.class);
                    call.respond(
                        userMediaStateService.putState(currentAuthenticatedMediaStateUserId(call), objectId, request)
                    );
                })
            );

            authenticatedRoute.get(
                "/api/v1/media/state/favorites",
                doc(config -> {
                    config.setDescription("List favorite objects for the current user");
                    config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(UserMediaStateListing.class))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    call.respond(
                        toResponseModel(
                            userMediaStateService.listFavorites(
                                currentAuthenticatedMediaStateUserId(call),
                                RouteUtilsJava.getPageParam(call, 1),
                                RouteUtilsJava.getLimitParam(call, 50, 200)
                            ),
                            virtualPathResolver,
                            thumbnailSizes
                        )
                    );
                })
            );

            authenticatedRoute.get(
                "/api/v1/media/state/continue-watching",
                doc(config -> {
                    config.setDescription("List continue-watching objects for the current user");
                    config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(UserMediaStateListing.class))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    call.respond(
                        toResponseModel(
                            userMediaStateService.listContinueWatching(
                                currentAuthenticatedMediaStateUserId(call),
                                RouteUtilsJava.getPageParam(call, 1),
                                RouteUtilsJava.getLimitParam(call, 50, 200)
                            ),
                            virtualPathResolver,
                            thumbnailSizes
                        )
                    );
                })
            );
        });
    }

    private static void requireMediaObject(MediaObjectService mediaObjectService, String objectId) {
        if (mediaObjectService.getByObjectId(objectId) == null) {
            throw nyxException(ErrorCode.MEDIA_OBJECT_NOT_FOUND, "Media object not found: " + objectId);
        }
    }

    private static String currentAuthenticatedMediaStateUserId(RoutingCall call) {
        UserIdPrincipal principal = call.principal(UserIdPrincipal.class);
        if (principal == null) {
            throw new IllegalStateException("Media object state route requires an authenticated principal");
        }
        return principal.getName();
    }

    private static UserMediaStateListing toResponseModel(
        UserMediaStateListing listing,
        VirtualPathResolver virtualPathResolver,
        List<Integer> thumbnailSizes
    ) {
        return new UserMediaStateListing(
            listing.items().stream()
                .map(item -> toResponseModel(item, virtualPathResolver, thumbnailSizes))
                .toList(),
            listing.total(),
            listing.page(),
            listing.limit()
        );
    }

    private static UserMediaStateEntry toResponseModel(
        UserMediaStateEntry entry,
        VirtualPathResolver virtualPathResolver,
        List<Integer> thumbnailSizes
    ) {
        return new UserMediaStateEntry(
            toResponseModel(entry.media(), virtualPathResolver, thumbnailSizes),
            entry.state()
        );
    }

    private static UserMediaStateMediaSummary toResponseModel(
        UserMediaStateMediaSummary summary,
        VirtualPathResolver virtualPathResolver,
        List<Integer> thumbnailSizes
    ) {
        String responsePath = toResponseMediaPath(summary.path(), virtualPathResolver);
        String primaryThumbnailUrl = null;
        if (
            summary.primaryThumbnail() != null
                && responsePath != null
                && MediaThumbnailService.supportsGeneratedPrimaryThumbnail(summary.mediaKind())
        ) {
            primaryThumbnailUrl = buildPrimaryThumbnailUrl(responsePath, thumbnailSizes);
        }

        MediaThumbnailReference primaryThumbnailReference = null;
        if (summary.primaryThumbnail() != null) {
            primaryThumbnailReference = new MediaThumbnailReference(
                summary.primaryThumbnail().thumbnailId(),
                summary.primaryThumbnail().kind(),
                summary.primaryThumbnail().status(),
                primaryThumbnailUrl,
                summary.primaryThumbnail().width(),
                summary.primaryThumbnail().height(),
                summary.primaryThumbnail().format()
            );
        }

        return new UserMediaStateMediaSummary(
            summary.objectId(),
            summary.mediaKind(),
            responsePath,
            summary.displayName(),
            summary.mimeType(),
            summary.sizeBytes(),
            summary.modifiedAt(),
            summary.durationMillis(),
            summary.width(),
            summary.height(),
            summary.channels(),
            summary.takenAt(),
            summary.embeddedTitle(),
            summary.embeddedArtist(),
            summary.embeddedAlbum(),
            primaryThumbnailReference,
            summary.status()
        );
    }

    private static String buildPrimaryThumbnailUrl(String path, List<Integer> thumbnailSizes) {
        Integer primarySize = thumbnailSizes.stream().findFirst().orElse(null);
        if (primarySize == null) {
            return null;
        }
        return MediaTypes.buildThumbnailUrls(path, List.of(primarySize)).get(Integer.toString(primarySize));
    }

    private static String toResponseMediaPath(String mediaPath, VirtualPathResolver virtualPathResolver) {
        if (mediaPath == null) {
            return null;
        }
        if (virtualPathResolver == null) {
            return mediaPath;
        }
        Path absolutePath;
        try {
            absolutePath = Path.of(mediaPath);
        } catch (Exception ignored) {
            return null;
        }
        return virtualPathResolver.toVirtualPath(absolutePath);
    }

    private static void requireAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.REQUIRED, authProviders));
    }

    private static Consumer<OpenApiRouteConfig> doc(RouteDoc block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.RequestDoc> requestDoc(RequestDocBlock block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.ResponseCollection> responseDoc(ResponseDocBlock block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.ResponseDoc> bodyDoc(Class<?> type) {
        return response -> response.body(type);
    }

    private static Consumer<com.nyx.http.ResponseDoc> describe(String description) {
        return response -> response.setDescription(description);
    }

    private static Consumer<RouteHandlerScope> handler(RouteHandler handler) {
        return handler::accept;
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
