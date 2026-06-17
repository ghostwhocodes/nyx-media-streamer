package com.nyx.browse;

import static com.nyx.common.RouteUtilsJava.getLimitParam;
import static com.nyx.common.RouteUtilsJava.getPageParam;
import static com.nyx.common.RouteUtilsJava.getRequiredParam;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.ParameterDoc;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.media.client.MediaClientContractAdapter;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

public final class BrowseRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_BAD_REQUEST = HttpStatusCode.Companion.getBadRequest();
    private static final MediaClientContractAdapter MEDIA_CLIENT_CONTRACT = MediaClientContractAdapter.DEFAULT;

    private BrowseRoutes() {
    }

    public static void browseRoutes(Route route, BrowseService browseService) {
        route.get(
            "/api/v1/browse",
            doc(config -> {
                config.setDescription("Browse a directory of media files");
                config.request(requestDoc(request -> {
                    request.queryParameter("path", optionalParam("Virtual path to browse (empty for root)"));
                    request.queryParameter("dir", optionalParam("Alias for path"));
                    request.queryParameter("page", optionalParam("Page number (0-based)"));
                    request.queryParameter("limit", optionalParam("Items per page"));
                    request.queryParameter("sort", optionalParam("Sort order: name, date, size"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(MediaClientContractAdapter.BrowseListingResponse.class));
                    response.code(HTTP_BAD_REQUEST, describe("Invalid sort or path parameter"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String path = valueOrDefault(call.getQueryParameters().get("path"), call.getQueryParameters().get("dir"));
                int page = getPageParam(call, 1);
                int limit = getLimitParam(call, 50, 200);
                String sortParam = call.getQueryParameters().get("sort");
                BrowseSortOrder sort = switch ((sortParam == null ? "name" : sortParam).toLowerCase(Locale.ROOT)) {
                    case "name" -> BrowseSortOrder.NAME;
                    case "date" -> BrowseSortOrder.DATE;
                    case "size" -> BrowseSortOrder.SIZE;
                    default -> throw nyxException(
                        ErrorCode.INVALID_REQUEST,
                        "Invalid sort order: " + sortParam + ". Must be one of: name, date, size"
                    );
                };

                call.respond(MEDIA_CLIENT_CONTRACT.browse(
                    browseService.browse(path == null ? "" : path, page, limit, sort),
                    call
                ));
            })
        );

        route.get(
            "/api/v1/search/files",
            doc(config -> {
                config.setDescription("Search for files across all media roots");
                config.request(requestDoc(request -> {
                    request.queryParameter("query", requiredParam("Search query string"));
                    request.queryParameter("page", optionalParam("Page number (0-based)"));
                    request.queryParameter("limit", optionalParam("Items per page"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(MediaClientContractAdapter.FileSearchResultResponse.class));
                    response.code(HTTP_BAD_REQUEST, describe("Missing query parameter"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String query = getRequiredParam(call, "query");
                int page = getPageParam(call, 1);
                int limit = getLimitParam(call, 50, 200);
                call.respond(MEDIA_CLIENT_CONTRACT.search(browseService.searchFiles(query, page, limit), call));
            })
        );
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

    private static Consumer<ParameterDoc> optionalParam(String description) {
        return parameter -> {
            parameter.setDescription(description);
            parameter.setRequired(false);
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

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
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
