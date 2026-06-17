package com.nyx.media;

import static com.nyx.common.RouteUtilsJava.enforceUserRateLimit;
import static com.nyx.common.RouteUtilsJava.getLimitParam;
import static com.nyx.common.RouteUtilsJava.getPageParam;
import static com.nyx.common.RouteUtilsJava.getRequiredParam;
import static com.nyx.common.RouteUtilsJava.resolveDirParam;
import static com.nyx.common.RouteUtilsJava.resolvePathParam;

import com.nyx.browse.BrowseService;
import com.nyx.browse.MediaTypeFilter;
import com.nyx.common.ErrorCode;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.common.RangeSupport;
import com.nyx.common.VirtualPathResolver;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.MediaProberInterop;
import com.nyx.ffmpeg.TrickplayAssetKind;
import com.nyx.ffmpeg.VideoPreviewOutput;
import com.nyx.ffmpeg.VideoPreviewRequest;
import com.nyx.ffmpeg.VideoTrickplayAssetOutput;
import com.nyx.ffmpeg.VideoTrickplayAssetPlan;
import com.nyx.ffmpeg.VideoTrickplayPlan;
import com.nyx.ffmpeg.VideoTrickplayRequest;
import com.nyx.ffmpeg.VideoTrickplayTimelineEntry;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.ParameterDoc;
import com.nyx.http.Parameters;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.media.contracts.FileSearchResult;
import com.nyx.media.contracts.Gallery;
import com.nyx.media.contracts.ImageDimensions;
import com.nyx.media.contracts.ImageTransformFit;
import com.nyx.media.contracts.ImageTransformRequest;
import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.SortOrder;
import com.nyx.media.contracts.TrickplayAsset;
import com.nyx.media.contracts.TrickplayManifest;
import com.nyx.media.contracts.TrickplayRequest;
import com.nyx.media.contracts.TrickplayTileLayout;
import com.nyx.media.contracts.TrickplayTileLayoutRequest;
import com.nyx.media.contracts.TrickplayTimelineEntry;
import com.nyx.media.client.MediaClientContractAdapter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

@SuppressWarnings({"LongMethod", "CyclomaticComplexity"})
public final class ImageRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_BAD_REQUEST = HttpStatusCode.Companion.getBadRequest();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();
    private static final HttpStatusCode HTTP_NOT_MODIFIED = HttpStatusCode.Companion.getNotModified();
    private static final HttpStatusCode HTTP_INTERNAL_SERVER_ERROR = HttpStatusCode.Companion.getInternalServerError();
    private static final HttpStatusCode HTTP_SERVICE_UNAVAILABLE = HttpStatusCode.Companion.getServiceUnavailable();
    private static final MediaClientContractAdapter MEDIA_CLIENT_CONTRACT = MediaClientContractAdapter.DEFAULT;

    private ImageRoutes() {
    }

    public static void imageRoutes(
        Route route,
        MediaFileService mediaFileService,
        ThumbnailService thumbnailService,
        ExifExtractor exifExtractor,
        StrippedImageCache strippedImageCache,
        PathSecurity pathSecurity,
        ImageTransformService imageTransformService,
        VideoPreviewService videoPreviewService,
        VideoTrickplayService videoTrickplayService
    ) {
        imageRoutes(
            route,
            mediaFileService,
            thumbnailService,
            exifExtractor,
            strippedImageCache,
            pathSecurity,
            null,
            null,
            null,
            null,
            imageTransformService,
            videoPreviewService,
            videoTrickplayService,
            null,
            null,
            null
        );
    }

    public static void imageRoutes(
        Route route,
        MediaFileService mediaFileService,
        ThumbnailService thumbnailService,
        ExifExtractor exifExtractor,
        StrippedImageCache strippedImageCache,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver,
        BrowseService browseService,
        MediaProber probeService,
        QuotaService quotaService,
        ImageTransformService imageTransformService,
        VideoPreviewService videoPreviewService,
        VideoTrickplayService videoTrickplayService,
        MediaObjectResolver mediaObjectResolver,
        MediaThumbnailService mediaThumbnailService,
        MediaThumbnailLifecycle mediaThumbnailLifecycle
    ) {
        MediaObjectResolver activeMediaObjectResolver = mediaObjectResolver;
        MediaThumbnailLifecycle activeMediaThumbnailLifecycle = mediaThumbnailLifecycle != null
            ? mediaThumbnailLifecycle
            : mediaThumbnailService == null ? null : new BestEffortMediaThumbnailLifecycle(mediaThumbnailService);

        route.get(
            "/api/v1/images",
            doc(config -> {
                config.setDescription("List images in a directory");
                config.request(requestDoc(request -> {
                    request.queryParameter("dir", requiredParam("Virtual path to the directory"));
                    request.queryParameter("page", optionalParam("Page number (0-based)"));
                    request.queryParameter("limit", optionalParam("Items per page"));
                    request.queryParameter("sort", optionalParam("Sort order: name, date, size"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(MediaClientContractAdapter.GalleryResponse.class));
                    response.code(HTTP_BAD_REQUEST, describe("Invalid directory or sort parameter"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String dir = getRequiredParam(call, "dir");
                int page = getPageParam(call, 1);
                int limit = getLimitParam(call, 50, 200);
                String sortParam = call.getQueryParameters().get("sort");
                SortOrder sort = switch ((sortParam == null ? "name" : sortParam).toLowerCase(Locale.ROOT)) {
                    case "name" -> SortOrder.NAME;
                    case "date" -> SortOrder.DATE;
                    case "size" -> SortOrder.SIZE;
                    default -> throw nyxException(
                        ErrorCode.INVALID_REQUEST,
                        "Invalid sort order: " + sortParam + ". Must be one of: name, date, size"
                    );
                };

                Path resolvedDir = virtualPathResolver == null
                    ? pathSecurity.validateDirectory(dir)
                    : resolveDirParam(dir, pathSecurity, virtualPathResolver);
                call.respond(MEDIA_CLIENT_CONTRACT.gallery(mediaFileService.listImages(resolvedDir, page, limit, sort), call));
            })
        );

        route.get(
            "/api/v1/images/thumb",
            doc(config -> {
                config.setDescription("Get a thumbnail for an image file");
                config.request(requestDoc(request -> {
                    request.queryParameter("path", requiredParam("Virtual path to the image file"));
                    request.queryParameter("size", requiredParam("Thumbnail size in pixels (square)"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, describe("JPEG thumbnail bytes"));
                    response.code(HTTP_BAD_REQUEST, describe("Missing or invalid path/size parameter"));
                    response.code(HTTP_NOT_FOUND, describe("Image not found"));
                    response.code(HTTP_SERVICE_UNAVAILABLE, describe("FFmpeg not available"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                Integer size = optionalInt(call.getQueryParameters(), "size");
                if (size == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing or invalid parameter: size");
                }

                Path validPath = resolveValidatedPath(pathParam, pathSecurity, virtualPathResolver);
                MediaObject mediaObject = resolveMediaObject(
                    validPath,
                    activeMediaObjectResolver,
                    MediaObjectResolveOptions.IMAGE_OR_VIDEO
                );
                boolean primaryRequest = size == thumbnailService.getPrimaryThumbnailSize();
                String storageKey = mediaObject == null ? null : MediaThumbnailService.buildStorageKey(mediaObject.objectId(), size);

                byte[] bytes;
                try {
                    bytes = thumbnailService.getThumbnail(validPath, size, storageKey);
                } catch (IllegalArgumentException exception) {
                    throw nyxException(
                        ErrorCode.INVALID_THUMBNAIL_SIZE,
                        exception.getMessage() == null ? "Invalid thumbnail size" : exception.getMessage(),
                        exception
                    );
                } catch (Exception exception) {
                    if (primaryRequest && mediaObject != null && storageKey != null && activeMediaThumbnailLifecycle != null) {
                        try {
                            activeMediaThumbnailLifecycle.markPrimaryThumbnailFailed(mediaObject.objectId(), storageKey);
                        } catch (Exception ignored) {
                        }
                    }
                    throw exception;
                }

                if (primaryRequest && mediaObject != null && storageKey != null && activeMediaThumbnailLifecycle != null) {
                    activeMediaThumbnailLifecycle.markPrimaryThumbnailReady(mediaObject.objectId(), storageKey, size);
                }
                String etag = RangeSupport.generateETag(validPath);
                String ifNoneMatch = call.getRequest().getHeaders().get(HttpHeaders.IfNoneMatch);
                if (etag.equals(ifNoneMatch)) {
                    call.getResponse().header(HttpHeaders.ETag, etag);
                    call.respond(HTTP_NOT_MODIFIED);
                    return;
                }
                call.getResponse().header(HttpHeaders.ETag, etag);
                call.getResponse().header(HttpHeaders.CacheControl, "public, max-age=86400");
                call.respondBytes(bytes, ContentType.Image.JPEG);
            })
        );

        route.get(
            "/api/v1/images/file",
            doc(config -> {
                config.setDescription("Serve an image file with EXIF metadata stripped for privacy");
                config.request(requestDoc(request -> request.queryParameter("path", requiredParam("Virtual path to the image file"))));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, describe("Image bytes with EXIF stripped"));
                    response.code(HTTP_NOT_MODIFIED, describe("Not modified (ETag match)"));
                    response.code(HTTP_BAD_REQUEST, describe("Missing path parameter"));
                    response.code(HTTP_NOT_FOUND, describe("Image not found or path is not an image"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }

                Path validPath = ensureImagePath(pathParam, pathSecurity, virtualPathResolver);
                resolveMediaObject(validPath, activeMediaObjectResolver, MediaObjectResolveOptions.IMAGE_ONLY);

                String etag = RangeSupport.generateETag(validPath);
                String ifNoneMatch = call.getRequest().getHeaders().get(HttpHeaders.IfNoneMatch);
                if (etag.equals(ifNoneMatch)) {
                    call.getResponse().header(HttpHeaders.ETag, etag);
                    call.respond(HTTP_NOT_MODIFIED);
                    return;
                }

                byte[] strippedBytes = strippedImageCache.getStrippedImage(validPath);
                call.getResponse().header(HttpHeaders.ETag, etag);
                call.getResponse().header(HttpHeaders.CacheControl, "public, max-age=3600");
                call.respondBytes(strippedBytes, ContentType.parse(MediaTypes.detectMimeType(validPath)));
            })
        );

        route.get(
            "/api/v1/images/view",
            doc(config -> {
                config.setDescription("Serve a parameterized image view with privacy-stripped transforms");
                config.request(requestDoc(request -> {
                    request.queryParameter("path", requiredParam("Virtual path to the image file"));
                    request.queryParameter("width", optionalParam("Target width in pixels"));
                    request.queryParameter("height", optionalParam("Target height in pixels"));
                    request.queryParameter("maxWidth", optionalParam("Maximum width in pixels"));
                    request.queryParameter("maxHeight", optionalParam("Maximum height in pixels"));
                    request.queryParameter("quality", optionalParam("Output quality (1-100) where applicable"));
                    request.queryParameter("fit", optionalParam("Fit mode: contain, cover, fill"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, describe("Transformed image bytes"));
                    response.code(HTTP_NOT_MODIFIED, describe("Not modified (ETag match)"));
                    response.code(HTTP_BAD_REQUEST, describe("Missing or invalid transform parameters"));
                    response.code(HTTP_NOT_FOUND, describe("Image not found"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }

                Path validPath = ensureImagePath(pathParam, pathSecurity, virtualPathResolver);
                resolveMediaObject(validPath, activeMediaObjectResolver, MediaObjectResolveOptions.IMAGE_ONLY);

                ImageTransformRequest request = toImageTransformRequest(call.getRequest().getQueryParameters());
                ImageTransformOutput output;
                try {
                    output = imageTransformService.getImage(validPath, request);
                } catch (IllegalArgumentException exception) {
                    throw nyxException(
                        ErrorCode.INVALID_REQUEST,
                        exception.getMessage() == null ? "Invalid image transform request" : exception.getMessage(),
                        exception
                    );
                }

                String etag = transformedImageETag(validPath, output.getPlan().getCacheKey());
                String ifNoneMatch = call.getRequest().getHeaders().get(HttpHeaders.IfNoneMatch);
                if (etag.equals(ifNoneMatch)) {
                    call.getResponse().header(HttpHeaders.ETag, etag);
                    call.respond(HTTP_NOT_MODIFIED);
                    return;
                }

                call.getResponse().header(HttpHeaders.ETag, etag);
                call.getResponse().header(HttpHeaders.CacheControl, "public, max-age=3600");
                call.respondBytes(output.getBytes(), ContentType.parse(output.getPlan().getOutputMimeType()));
            })
        );

        route.get(
            "/api/v1/images/preview",
            doc(config -> {
                config.setDescription("Serve a cacheable video seek-preview frame as an image");
                config.request(requestDoc(request -> {
                    request.queryParameter("path", requiredParam("Virtual path to the video file"));
                    request.queryParameter("positionMs", optionalParam("Preview frame position in milliseconds"));
                    request.queryParameter("percent", optionalParam("Preview frame position as a percentage of duration"));
                    request.queryParameter("width", optionalParam("Maximum preview width in pixels"));
                    request.queryParameter("height", optionalParam("Maximum preview height in pixels"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, describe("Preview frame image bytes"));
                    response.code(HTTP_NOT_MODIFIED, describe("Not modified (ETag match)"));
                    response.code(HTTP_BAD_REQUEST, describe("Missing or invalid preview parameters"));
                    response.code(HTTP_NOT_FOUND, describe("Video not found"));
                    response.code(HTTP_SERVICE_UNAVAILABLE, describe("FFmpeg not available"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }

                Path validPath = ensureVideoPath(pathParam, pathSecurity, virtualPathResolver);
                resolveMediaObject(validPath, activeMediaObjectResolver, MediaObjectResolveOptions.VIDEO_ONLY);

                VideoPreviewRequest request = toVideoPreviewRequest(call.getRequest().getQueryParameters());
                VideoPreviewOutput output;
                try {
                    output = videoPreviewService.getPreview(validPath, request);
                } catch (IllegalArgumentException exception) {
                    throw nyxException(
                        ErrorCode.INVALID_REQUEST,
                        exception.getMessage() == null ? "Invalid video preview request" : exception.getMessage(),
                        exception
                    );
                }

                String etag = transformedImageETag(validPath, output.plan().getCacheKey());
                String ifNoneMatch = call.getRequest().getHeaders().get(HttpHeaders.IfNoneMatch);
                if (etag.equals(ifNoneMatch)) {
                    call.getResponse().header(HttpHeaders.ETag, etag);
                    call.respond(HTTP_NOT_MODIFIED);
                    return;
                }

                call.getResponse().header(HttpHeaders.ETag, etag);
                call.getResponse().header(HttpHeaders.CacheControl, "public, max-age=3600");
                call.respondBytes(output.bytes(), ContentType.parse(output.plan().getOutputMimeType()));
            })
        );

        route.get(
            "/api/v1/images/trickplay",
            doc(config -> {
                config.setDescription("Describe cacheable multi-frame trickplay assets for a video file");
                config.request(requestDoc(request -> {
                    request.queryParameter("path", requiredParam("Virtual path to the video file"));
                    request.queryParameter("assetKinds", optionalParam("Comma-separated asset kinds: storyboard_sheet, preview_strip"));
                    request.queryParameter("intervalMs", optionalParam("Requested capture interval in milliseconds"));
                    request.queryParameter("thumbnailWidth", optionalParam("Maximum trickplay thumbnail width in pixels"));
                    request.queryParameter("thumbnailHeight", optionalParam("Maximum trickplay thumbnail height in pixels"));
                    request.queryParameter("columns", optionalParam("Requested tile column count"));
                    request.queryParameter("rows", optionalParam("Requested tile row count"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(TrickplayManifest.class));
                    response.code(HTTP_NOT_MODIFIED, describe("Not modified (ETag match)"));
                    response.code(HTTP_BAD_REQUEST, describe("Missing or invalid trickplay parameters"));
                    response.code(HTTP_NOT_FOUND, describe("Video not found"));
                    response.code(HTTP_SERVICE_UNAVAILABLE, describe("FFmpeg not available"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }

                Path validPath = ensureVideoPath(pathParam, pathSecurity, virtualPathResolver);
                resolveMediaObject(validPath, activeMediaObjectResolver, MediaObjectResolveOptions.VIDEO_ONLY);

                VideoTrickplayRequest request = toVideoTrickplayRequest(call.getRequest().getQueryParameters());
                VideoTrickplayPlan plan;
                try {
                    plan = videoTrickplayService.getPlan(validPath, request);
                } catch (IllegalArgumentException exception) {
                    throw nyxException(
                        ErrorCode.INVALID_REQUEST,
                        exception.getMessage() == null ? "Invalid video trickplay request" : exception.getMessage(),
                        exception
                    );
                }

                String etag = transformedImageETag(validPath, plan.getCacheKey());
                String ifNoneMatch = call.getRequest().getHeaders().get(HttpHeaders.IfNoneMatch);
                if (etag.equals(ifNoneMatch)) {
                    call.getResponse().header(HttpHeaders.ETag, etag);
                    call.respond(HTTP_NOT_MODIFIED);
                    return;
                }

                call.getResponse().header(HttpHeaders.ETag, etag);
                call.getResponse().header(HttpHeaders.CacheControl, "public, max-age=3600");
                call.respond(toTrickplayManifest(plan, pathParam));
            })
        );

        route.get(
            "/api/v1/images/trickplay/asset",
            doc(config -> {
                config.setDescription("Serve a cacheable trickplay image asset for a video file");
                config.request(requestDoc(request -> {
                    request.queryParameter("path", requiredParam("Virtual path to the video file"));
                    request.queryParameter("assetIndex", requiredParam("Asset index within the requested assetKinds result set"));
                    request.queryParameter("assetKinds", optionalParam("Comma-separated asset kinds: storyboard_sheet, preview_strip"));
                    request.queryParameter("intervalMs", optionalParam("Requested capture interval in milliseconds"));
                    request.queryParameter("thumbnailWidth", optionalParam("Maximum trickplay thumbnail width in pixels"));
                    request.queryParameter("thumbnailHeight", optionalParam("Maximum trickplay thumbnail height in pixels"));
                    request.queryParameter("columns", optionalParam("Requested tile column count"));
                    request.queryParameter("rows", optionalParam("Requested tile row count"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, describe("Trickplay asset image bytes"));
                    response.code(HTTP_NOT_MODIFIED, describe("Not modified (ETag match)"));
                    response.code(HTTP_BAD_REQUEST, describe("Missing or invalid trickplay parameters"));
                    response.code(HTTP_NOT_FOUND, describe("Video not found"));
                    response.code(HTTP_SERVICE_UNAVAILABLE, describe("FFmpeg not available"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                Integer assetIndex = optionalInt(call.getQueryParameters(), "assetIndex");
                if (assetIndex == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing or invalid parameter: assetIndex");
                }

                Path validPath = ensureVideoPath(pathParam, pathSecurity, virtualPathResolver);
                resolveMediaObject(validPath, activeMediaObjectResolver, MediaObjectResolveOptions.VIDEO_ONLY);

                VideoTrickplayRequest request = toVideoTrickplayRequest(call.getRequest().getQueryParameters());
                VideoTrickplayAssetOutput output;
                try {
                    output = videoTrickplayService.getAsset(validPath, request, assetIndex);
                } catch (IllegalArgumentException exception) {
                    throw nyxException(
                        ErrorCode.INVALID_REQUEST,
                        exception.getMessage() == null ? "Invalid video trickplay asset request" : exception.getMessage(),
                        exception
                    );
                }

                String etag = transformedImageETag(validPath, output.plan().getCacheKey());
                String ifNoneMatch = call.getRequest().getHeaders().get(HttpHeaders.IfNoneMatch);
                if (etag.equals(ifNoneMatch)) {
                    call.getResponse().header(HttpHeaders.ETag, etag);
                    call.respond(HTTP_NOT_MODIFIED);
                    return;
                }

                call.getResponse().header(HttpHeaders.ETag, etag);
                call.getResponse().header(HttpHeaders.CacheControl, "public, max-age=3600");
                call.respondBytes(output.bytes(), ContentType.parse(output.plan().getOutputMimeType()));
            })
        );

        route.get(
            "/api/v1/images/exif",
            doc(config -> {
                config.setDescription("Extract EXIF metadata from an image file");
                config.request(requestDoc(request -> request.queryParameter("path", requiredParam("Virtual path to the image file"))));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, describe("EXIF metadata as key-value map"));
                    response.code(HTTP_BAD_REQUEST, describe("Missing path parameter"));
                    response.code(HTTP_NOT_FOUND, describe("Image not found or path is not an image"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }

                Path validPath = ensureImagePath(pathParam, pathSecurity, virtualPathResolver);
                resolveMediaObject(validPath, activeMediaObjectResolver, MediaObjectResolveOptions.IMAGE_ONLY);
                call.respond(exifExtractor.extractExif(validPath));
            })
        );

        if (browseService != null) {
            route.get(
                "/api/v1/images/search",
                doc(config -> {
                    config.setDescription("Search for image files");
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
                    enforceUserRateLimit(call, quotaService);
                    String query = getRequiredParam(call, "query");
                    int page = getPageParam(call, 1);
                    int limit = getLimitParam(call, 50, 200);
                    call.respond(MEDIA_CLIENT_CONTRACT.search(
                        browseService.searchFiles(query, page, limit, MediaTypeFilter.IMAGE),
                        call
                    ));
                })
            );
        }

        route.get(
            "/api/v1/images/subtitles",
            doc(config -> {
                config.setDescription("List subtitle streams in a media file");
                config.request(requestDoc(request -> request.queryParameter("path", requiredParam("Path to the media file"))));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(List.class));
                    response.code(HTTP_BAD_REQUEST, describe("Missing path or probe service not available"));
                    response.code(HTTP_INTERNAL_SERVER_ERROR, describe("Probe failed"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                MediaProber probe = probeService;
                if (probe == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Probe service not available");
                }

                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }

                Path validPath = ensureVideoPath(pathParam, pathSecurity, virtualPathResolver);
                ProbeResult probeResult;
                try {
                    probeResult = MediaProberInterop.probeCachedOrThrow(probe, validPath);
                } catch (Throwable throwable) {
                    throw nyxException(ErrorCode.PROBE_FAILED, "Failed to probe: " + throwable.getMessage(), throwable);
                }

                resolveMediaObject(validPath, activeMediaObjectResolver, MediaObjectResolveOptions.VIDEO_ONLY);
                List<SubtitleStream> subtitles = probeResult.getStreams().getSubtitle();
                call.respond(subtitles);
            })
        );
    }

    private static Path resolveValidatedPath(
        String path,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        return virtualPathResolver == null
            ? pathSecurity.validate(path)
            : resolvePathParam(path, pathSecurity, virtualPathResolver);
    }

    private static Path ensureImagePath(
        String path,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        Path validPath = resolveValidatedPath(path, pathSecurity, virtualPathResolver);
        String mimeType = MediaTypes.detectMimeType(validPath);
        if (!MediaTypes.isImage(mimeType)) {
            throw nyxException(ErrorCode.IMAGE_NOT_FOUND, "Not an image file: " + path);
        }
        return validPath;
    }

    private static Path ensureVideoPath(
        String path,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        Path validPath = resolveValidatedPath(path, pathSecurity, virtualPathResolver);
        String mimeType = MediaTypes.detectMimeType(validPath);
        if (!MediaTypes.isVideo(mimeType)) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Not a video file: " + path);
        }
        return validPath;
    }

    private static MediaObject resolveMediaObject(
        Path validPath,
        MediaObjectResolver mediaObjectResolver,
        MediaObjectResolveOptions options
    ) {
        return mediaObjectResolver == null ? null : mediaObjectResolver.resolveOrCreate(validPath, options);
    }

    private static ImageTransformRequest toImageTransformRequest(Parameters parameters) {
        return new ImageTransformRequest(
            optionalInt(parameters, "width"),
            optionalInt(parameters, "height"),
            optionalInt(parameters, "maxWidth"),
            optionalInt(parameters, "maxHeight"),
            optionalInt(parameters, "quality"),
            optionalFit(parameters, "fit")
        );
    }

    private static VideoPreviewRequest toVideoPreviewRequest(Parameters parameters) {
        return new VideoPreviewRequest(
            optionalLong(parameters, "positionMs"),
            optionalInt(parameters, "percent"),
            optionalInt(parameters, "width"),
            optionalInt(parameters, "height")
        );
    }

    private static VideoTrickplayRequest toVideoTrickplayRequest(Parameters parameters) {
        Set<TrickplayAssetKind> assetKinds = optionalTrickplayAssetKinds(parameters, "assetKinds");
        return new VideoTrickplayRequest(
            assetKinds == null ? new VideoTrickplayRequest().assetKinds() : assetKinds,
            optionalLong(parameters, "intervalMs"),
            optionalInt(parameters, "thumbnailWidth"),
            optionalInt(parameters, "thumbnailHeight"),
            optionalInt(parameters, "columns"),
            optionalInt(parameters, "rows")
        );
    }

    private static Set<TrickplayAssetKind> optionalTrickplayAssetKinds(Parameters parameters, String name) {
        String raw = parameters.get(name);
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid " + name + ": value must not be blank");
        }

        List<String> tokens = List.of(raw.split(","));
        Set<TrickplayAssetKind> kinds = new java.util.LinkedHashSet<>();
        for (String token : tokens) {
            String normalized = token.trim().toLowerCase(Locale.ROOT);
            kinds.add(switch (normalized) {
                case "storyboard_sheet", "storyboard-sheet", "storyboard" -> TrickplayAssetKind.STORYBOARD_SHEET;
                case "preview_strip", "preview-strip", "strip" -> TrickplayAssetKind.PREVIEW_STRIP;
                default -> throw nyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Invalid " + name + " entry: " + token.trim() + ". Must be one of: storyboard_sheet, preview_strip"
                );
            });
        }
        return Set.copyOf(kinds);
    }

    private static Integer optionalInt(Parameters parameters, String name) {
        String raw = parameters.get(name);
        if (raw == null) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException exception) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid " + name + ": " + raw, exception);
        }
    }

    private static Long optionalLong(Parameters parameters, String name) {
        String raw = parameters.get(name);
        if (raw == null) {
            return null;
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException exception) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid " + name + ": " + raw, exception);
        }
    }

    private static ImageTransformFit optionalFit(Parameters parameters, String name) {
        String raw = parameters.get(name);
        if (raw == null || raw.isBlank() || "contain".equalsIgnoreCase(raw)) {
            return ImageTransformFit.CONTAIN;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "cover" -> ImageTransformFit.COVER;
            case "fill" -> ImageTransformFit.FILL;
            default -> throw nyxException(
                ErrorCode.INVALID_REQUEST,
                "Invalid " + name + ": " + raw + ". Must be one of: contain, cover, fill"
            );
        };
    }

    private static TrickplayManifest toTrickplayManifest(VideoTrickplayPlan plan, String pathParam) {
        Set<com.nyx.media.contracts.TrickplayAssetKind> resolvedKinds = plan.assets().stream()
            .map(asset -> toContractKind(asset.kind()))
            .collect(java.util.stream.Collectors.toSet());
        Map<TrickplayAssetKind, Integer> requestIndexes = new HashMap<>();
        List<TrickplayAsset> assets = new ArrayList<>(plan.assets().size());
        for (VideoTrickplayAssetPlan asset : plan.assets()) {
            int requestAssetIndex = requestIndexes.getOrDefault(asset.kind(), 0);
            requestIndexes.put(asset.kind(), requestAssetIndex + 1);
            assets.add(new TrickplayAsset(
                toContractKind(asset.kind()),
                buildTrickplayAssetUrl(pathParam, asset, requestAssetIndex),
                asset.outputMimeType(),
                new ImageDimensions(asset.outputWidth(), asset.outputHeight()),
                new ImageDimensions(asset.thumbnailWidth(), asset.thumbnailHeight()),
                asset.intervalMillis(),
                asset.startMillis(),
                asset.endMillis(),
                asset.frameCount(),
                new TrickplayTileLayout(asset.tileColumns(), asset.tileRows())
            ));
        }

        List<TrickplayTimelineEntry> timeline = new ArrayList<>(plan.timeline().size());
        for (VideoTrickplayTimelineEntry entry : plan.timeline()) {
            timeline.add(new TrickplayTimelineEntry(
                entry.positionMillis(),
                toContractKind(entry.kind()),
                entry.assetIndex(),
                entry.column(),
                entry.row()
            ));
        }

        return new TrickplayManifest(
            plan.durationMillis(),
            new TrickplayRequest(
                resolvedKinds,
                plan.intervalMillis(),
                plan.thumbnailWidth(),
                plan.thumbnailHeight(),
                new TrickplayTileLayoutRequest(plan.tileColumns(), plan.tileRows())
            ),
            plan.intervalMillis(),
            assets,
            timeline,
            true
        );
    }

    private static String buildTrickplayAssetUrl(
        String pathParam,
        VideoTrickplayAssetPlan asset,
        int assetIndex
    ) {
        List<String> pairs = new ArrayList<>();
        pairs.add("path=" + encodeQueryValue(pathParam));
        pairs.add("assetIndex=" + assetIndex);
        pairs.add("assetKinds=" + toQueryValue(asset.kind()));
        pairs.add("intervalMs=" + asset.intervalMillis());
        pairs.add("thumbnailWidth=" + asset.thumbnailWidth());
        pairs.add("thumbnailHeight=" + asset.thumbnailHeight());
        pairs.add("columns=" + asset.tileColumns());
        pairs.add("rows=" + asset.tileRows());
        return "/api/v1/images/trickplay/asset?" + String.join("&", pairs);
    }

    private static com.nyx.media.contracts.TrickplayAssetKind toContractKind(TrickplayAssetKind kind) {
        return switch (kind) {
            case STORYBOARD_SHEET -> com.nyx.media.contracts.TrickplayAssetKind.STORYBOARD_SHEET;
            case PREVIEW_STRIP -> com.nyx.media.contracts.TrickplayAssetKind.PREVIEW_STRIP;
        };
    }

    private static String toQueryValue(TrickplayAssetKind kind) {
        return switch (kind) {
            case STORYBOARD_SHEET -> "storyboard_sheet";
            case PREVIEW_STRIP -> "preview_strip";
        };
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String transformedImageETag(Path path, String cacheKey) {
        String sourceTag = RangeSupport.generateETag(path).replace("\"", "");
        return '"' + sourceTag + '-' + cacheKey + '"';
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
        return sneakyThrow(new NyxException(code, message));
    }

    private static RuntimeException nyxException(ErrorCode code, String message, Throwable cause) {
        return sneakyThrow(new NyxException(code, message, cause));
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
