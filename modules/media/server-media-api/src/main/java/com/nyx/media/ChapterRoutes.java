package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.RouteUtilsJava;
import com.nyx.common.VirtualPathResolver;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.ParameterDoc;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.media.model.ChapterSet;
import com.nyx.media.model.CreateChapterMarkRequest;
import com.nyx.media.model.UpdateChapterMarkRequest;
import com.nyx.media.model.UpsertChapterSetRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class ChapterRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_CREATED = HttpStatusCode.Companion.getCreated();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();

    private ChapterRoutes() {
    }

    public static void chapterRoutes(Route route, ChapterService chapterService, PathSecurity pathSecurity) {
        chapterRoutes(route, chapterService, pathSecurity, List.of(), null);
    }

    public static void chapterRoutes(
        Route route,
        ChapterService chapterService,
        PathSecurity pathSecurity,
        List<String> authProviders,
        VirtualPathResolver virtualPathResolver
    ) {
        route.get(
            "/api/v1/chapters",
            doc(config -> {
                config.setDescription("Get chapter marks for a media file");
                config.request(requestDoc(request -> request.queryParameter("path", requiredParam(
                    "Virtual or absolute path to the media file"
                ))));
                config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(ChapterSet.class))));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String path = call.getQueryParameters().get("path");
                if (path == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                String resolvedPath = resolveChapterPath(path, pathSecurity, virtualPathResolver);
                ChapterSet chapterSet = chapterService.getByMediaPath(resolvedPath);
                call.respond(chapterSet == null
                    ? new ChapterSet(toResponseMediaPath(resolvedPath, virtualPathResolver))
                    : toResponseModel(chapterSet, virtualPathResolver));
            })
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.put(
                "/api/v1/chapters",
                doc(config -> {
                    config.setDescription("Replace the chapter set for a media file");
                    config.request(requestDoc(request -> request.body(UpsertChapterSetRequest.class)));
                    config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(ChapterSet.class))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    UpsertChapterSetRequest request = call.receive(UpsertChapterSetRequest.class);
                    String resolvedPath = resolveChapterPath(request.mediaPath(), pathSecurity, virtualPathResolver);
                    ChapterSet chapterSet = chapterService.upsertForMediaPath(
                        resolvedPath,
                        request.title(),
                        request.marks()
                    );
                    call.respond(toResponseModel(chapterSet, virtualPathResolver));
                })
            );

            authenticatedRoute.post(
                "/api/v1/chapters/marks",
                doc(config -> {
                    config.setDescription("Append a single chapter mark to a media file");
                    config.request(requestDoc(request -> request.body(CreateChapterMarkRequest.class)));
                    config.response(responseDoc(response -> response.code(HTTP_CREATED, bodyDoc(ChapterSet.class))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    CreateChapterMarkRequest request = call.receive(CreateChapterMarkRequest.class);
                    String resolvedPath = resolveChapterPath(request.mediaPath(), pathSecurity, virtualPathResolver);
                    ChapterSet chapterSet = chapterService.createMark(
                        resolvedPath,
                        request.label(),
                        request.ptsSecs(),
                        request.notes()
                    );
                    call.respond(HTTP_CREATED, toResponseModel(chapterSet, virtualPathResolver));
                })
            );

            authenticatedRoute.put(
                "/api/v1/chapters/marks/{markId}",
                doc(config -> {
                    config.setDescription("Update a single chapter mark");
                    config.request(requestDoc(request -> {
                        request.pathParameter("markId", requiredParam("Chapter mark ID"));
                        request.body(UpdateChapterMarkRequest.class);
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(ChapterSet.class));
                        response.code(HTTP_NOT_FOUND, describe("Chapter mark not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String markId = call.getPathParameters().get("markId");
                    if (markId == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing chapter mark ID");
                    }
                    UpdateChapterMarkRequest request = call.receive(UpdateChapterMarkRequest.class);
                    ChapterSet chapterSet = chapterService.updateMark(
                        markId,
                        request.label(),
                        request.ptsSecs(),
                        request.notes(),
                        request.sortOrder()
                    );
                    call.respond(toResponseModel(chapterSet, virtualPathResolver));
                })
            );

            authenticatedRoute.delete(
                "/api/v1/chapters/marks/{markId}",
                doc(config -> {
                    config.setDescription("Delete a single chapter mark");
                    config.request(requestDoc(request -> request.pathParameter("markId", requiredParam("Chapter mark ID"))));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_NO_CONTENT, describe("Chapter mark deleted"));
                        response.code(HTTP_NOT_FOUND, describe("Chapter mark not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String markId = call.getPathParameters().get("markId");
                    if (markId == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing chapter mark ID");
                    }
                    chapterService.deleteMark(markId);
                    call.respond(HTTP_NO_CONTENT);
                })
            );
        });
    }

    private static String resolveChapterPath(
        String mediaPath,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        String trimmed = mediaPath.trim();
        String existingAbsolutePath = resolveExistingAbsolutePath(trimmed, pathSecurity);
        if (existingAbsolutePath != null) {
            return existingAbsolutePath;
        }

        if (isAbsolutePath(trimmed) && hasExistingTopLevelDir(trimmed)) {
            return pathSecurity.validate(trimmed).toString();
        }

        if (virtualPathResolver != null && refersToVirtualRoot(trimmed, virtualPathResolver)) {
            return RouteUtilsJava.resolvePathParam(trimmed, pathSecurity, virtualPathResolver).toString();
        }

        if (isAbsolutePath(trimmed)) {
            return pathSecurity.validate(trimmed).toString();
        }
        if (virtualPathResolver != null) {
            return RouteUtilsJava.resolvePathParam(trimmed, pathSecurity, virtualPathResolver).toString();
        }
        return pathSecurity.validate(trimmed).toString();
    }

    private static boolean refersToVirtualRoot(String path, VirtualPathResolver virtualPathResolver) {
        String stripped = path.startsWith("/") ? path.substring(1) : path;
        if (stripped.isBlank()) {
            return false;
        }
        String rootName = stripped.contains("/") ? stripped.substring(0, stripped.indexOf('/')) : stripped;
        return virtualPathResolver.getRoots().stream().anyMatch(root -> root.displayName().equals(rootName));
    }

    private static String resolveExistingAbsolutePath(String path, PathSecurity pathSecurity) {
        Path absolutePath;
        try {
            absolutePath = Path.of(path);
        } catch (Exception ignored) {
            return null;
        }
        if (!absolutePath.isAbsolute() || !Files.exists(absolutePath)) {
            return null;
        }
        return pathSecurity.validate(path).toString();
    }

    private static ChapterSet toResponseModel(ChapterSet chapterSet, VirtualPathResolver virtualPathResolver) {
        String responseMediaPath = toResponseMediaPath(chapterSet.mediaPath(), virtualPathResolver);
        if (responseMediaPath.equals(chapterSet.mediaPath())) {
            return chapterSet;
        }
        return new ChapterSet(
            chapterSet.id(),
            responseMediaPath,
            chapterSet.title(),
            chapterSet.marks(),
            chapterSet.createdAt(),
            chapterSet.updatedAt()
        );
    }

    private static String toResponseMediaPath(String mediaPath, VirtualPathResolver virtualPathResolver) {
        if (virtualPathResolver == null) {
            return mediaPath;
        }
        Path absolutePath;
        try {
            absolutePath = Path.of(mediaPath);
        } catch (Exception ignored) {
            return mediaPath;
        }
        String virtualPath = virtualPathResolver.toVirtualPath(absolutePath);
        return virtualPath == null ? mediaPath : virtualPath;
    }

    private static boolean isAbsolutePath(String path) {
        try {
            return Path.of(path).isAbsolute();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasExistingTopLevelDir(String path) {
        Path parsed;
        try {
            parsed = Path.of(path);
        } catch (Exception ignored) {
            return false;
        }
        if (parsed.getNameCount() == 0 || parsed.getRoot() == null) {
            return false;
        }
        Path topLevel = parsed.getRoot().resolve(parsed.getName(0));
        return Files.exists(topLevel);
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(com.nyx.http.AuthMode.OPTIONAL, authProviders));
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

    private static Consumer<ParameterDoc> requiredParam(String description) {
        return parameter -> {
            parameter.setDescription(description);
            parameter.setRequired(true);
        };
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
