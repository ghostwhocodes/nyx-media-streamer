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
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.ParameterDoc;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.http.UserIdPrincipal;
import com.nyx.media.contracts.AudioListing;
import com.nyx.media.contracts.SortOrder;
import com.nyx.media.client.MediaClientContractAdapter;
import com.nyx.media.model.CreatePlaylistRequest;
import com.nyx.media.model.Playlist;
import com.nyx.media.model.ReorderTracksRequest;
import com.nyx.media.model.UpdatePlaylistRequest;
import com.nyx.playback.contracts.AudioDeliveryMode;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioNegotiationService;
import com.nyx.playback.contracts.AudioSession;
import com.nyx.playback.contracts.AudioSessionService;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackSessionLifecycle;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Consumer;

@SuppressWarnings("LongMethod")
public final class AudioRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_CREATED = HttpStatusCode.Companion.getCreated();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();
    private static final HttpStatusCode HTTP_BAD_REQUEST = HttpStatusCode.Companion.getBadRequest();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();
    private static final HttpStatusCode HTTP_SERVICE_UNAVAILABLE = HttpStatusCode.Companion.getServiceUnavailable();
    private static final MediaClientContractAdapter MEDIA_CLIENT_CONTRACT = MediaClientContractAdapter.DEFAULT;

    private AudioRoutes() {
    }

    public static void audioRoutes(
        Route route,
        MediaFileService mediaFileService,
        AudioTranscoder audioTranscoder,
        AudioNegotiationService audioNegotiationService,
        AudioSessionService audioSessionService,
        PlaylistService playlistService,
        PathSecurity pathSecurity
    ) {
        audioRoutes(
            route,
            mediaFileService,
            audioTranscoder,
            audioNegotiationService,
            audioSessionService,
            playlistService,
            pathSecurity,
            List.of(),
            null,
            null,
            null,
            null
        );
    }

    public static void audioRoutes(
        Route route,
        MediaFileService mediaFileService,
        AudioTranscoder audioTranscoder,
        AudioNegotiationService audioNegotiationService,
        AudioSessionService audioSessionService,
        PlaylistService playlistService,
        PathSecurity pathSecurity,
        List<String> authProviders,
        VirtualPathResolver virtualPathResolver,
        BrowseService browseService,
        QuotaService quotaService,
        MediaObjectResolver mediaObjectResolver
    ) {
        MediaObjectResolver activeMediaObjectResolver = mediaObjectResolver;

        route.get(
            "/api/v1/audio/file",
            doc(config -> {
                config.setDescription("Stream an audio file with content negotiation (serves original or transcodes on-the-fly)");
                config.request(requestDoc(request -> {
                    request.queryParameter("path", requiredParam("Virtual path to the audio file"));
                    request.headerParameter("Accept", optionalParam("Preferred MIME type for content negotiation"));
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, describe("Audio bytes (original or transcoded)"));
                    response.code(HTTP_BAD_REQUEST, describe("Missing path parameter or not an audio file"));
                    response.code(HTTP_NOT_FOUND, describe("Audio file not found"));
                    response.code(HTTP_SERVICE_UNAVAILABLE, describe("FFmpeg not available for transcoding"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                enforceUserRateLimit(call, quotaService);
                String pathParam = call.getQueryParameters().get("path");
                if (pathParam == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                String acceptHeader = call.getRequest().getHeaders().get(HttpHeaders.Accept);
                Path resolvedPath = resolveAudioPath(pathParam, pathSecurity, virtualPathResolver);
                var mediaObject = activeMediaObjectResolver == null
                    ? null
                    : activeMediaObjectResolver.resolveOrCreate(resolvedPath, MediaObjectResolveOptions.AUDIO_ONLY);
                String sourceMimeType = MediaTypes.detectMimeType(resolvedPath);
                Set<String> negotiatedMimeTypes = audioTranscoder.availableTargets().stream()
                    .map(AudioTranscoder.TranscodeTarget::mimeType)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
                AudioNegotiationRequest legacyRequest = LegacyAudioNegotiationRequestFactory.fromFileRequest(
                    resolvedPath.toString(),
                    acceptHeader,
                    0L,
                    null,
                    sourceMimeType,
                    negotiatedMimeTypes
                );
                AudioNegotiationRequest resolvedRequest = mediaFileService.resolveAudioNegotiationRequest(
                    withSource(
                        legacyRequest,
                        withResolvedPath(
                            legacyRequest.source(),
                            resolvedPath,
                            mediaObject == null ? null : mediaObject.objectId(),
                            mediaObject == null ? null : mediaObject.mediaKind()
                        )
                    )
                );
                AudioSession session = audioSessionService.openSession(resolvedRequest, null);
                try {
                    respondAudioSessionContent(call, session.sessionId(), null, audioSessionService, audioTranscoder);
                } finally {
                    audioSessionService.closeSession(session.sessionId(), null);
                }
            })
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/audio/sessions",
                doc(config -> {
                    config.setDescription("Open an audio session from an explicit audio negotiation request");
                    config.request(requestDoc(request -> request.body(AudioNegotiationRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(AudioSession.class));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid audio session request"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    enforceUserRateLimit(call, quotaService);
                    String userId = principalName(call);
                    AudioNegotiationRequest request = call.receive(AudioNegotiationRequest.class);
                    AudioNegotiationRequest resolvedRequest = resolveAudioNegotiationRequest(
                        request,
                        mediaFileService,
                        pathSecurity,
                        virtualPathResolver,
                        activeMediaObjectResolver
                    );
                    call.respond(audioSessionService.openSession(resolvedRequest, userId));
                })
            );

            authenticatedRoute.get(
                "/api/v1/audio/sessions/{sessionId}",
                doc(config -> {
                    config.setDescription("Get audio session state");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(AudioSession.class));
                        response.code(HTTP_NOT_FOUND, describe("Audio session not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String sessionId = call.getParameters().get("sessionId");
                    if (sessionId == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing sessionId");
                    }
                    AudioSession session = audioSessionService.getSession(sessionId, principalName(call));
                    if (session == null) {
                        throw nyxException(ErrorCode.JOB_NOT_FOUND, "Audio session not found: " + sessionId);
                    }
                    call.respond(session);
                })
            );

            authenticatedRoute.delete(
                "/api/v1/audio/sessions/{sessionId}",
                doc(config -> {
                    config.setDescription("Close an audio session");
                    config.response(responseDoc(response -> response.code(HTTP_NO_CONTENT, describe("Audio session closed"))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String sessionId = call.getParameters().get("sessionId");
                    if (sessionId == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing sessionId");
                    }
                    audioSessionService.closeSession(sessionId, principalName(call));
                    call.respond(HTTP_NO_CONTENT);
                })
            );

            authenticatedRoute.get(
                "/api/v1/audio/sessions/{sessionId}/content",
                doc(config -> {
                    config.setDescription("Serve content for an audio session");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Audio content"));
                        response.code(HTTP_NOT_FOUND, describe("Audio session not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String sessionId = call.getParameters().get("sessionId");
                    if (sessionId == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing sessionId");
                    }
                    respondAudioSessionContent(call, sessionId, principalName(call), audioSessionService, audioTranscoder);
                })
            );
        });

        route.get(
            "/api/v1/audio/browse",
            doc(config -> {
                config.setDescription("List audio files in a directory");
                config.request(requestDoc(request -> {
                    request.queryParameter("dir", requiredParam("Virtual path to the directory"));
                    request.queryParameter("page", optionalParam("Page number (0-based)"));
                    request.queryParameter("limit", optionalParam("Items per page"));
                    request.queryParameter(
                        "sort",
                        optionalParam("Sort order: name, date, size, artist, album, duration")
                    );
                }));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(MediaClientContractAdapter.AudioListingResponse.class));
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
                    case "artist" -> SortOrder.ARTIST;
                    case "album" -> SortOrder.ALBUM;
                    case "duration" -> SortOrder.DURATION;
                    default -> throw nyxException(ErrorCode.INVALID_REQUEST, "Invalid sort order: " + sortParam);
                };
                Path resolvedDir = virtualPathResolver == null
                    ? pathSecurity.validateDirectory(dir)
                    : resolveDirParam(dir, pathSecurity, virtualPathResolver);
                call.respond(MEDIA_CLIENT_CONTRACT.audio(mediaFileService.listAudio(resolvedDir, page, limit, sort), call));
            })
        );

        if (browseService != null) {
            route.get(
                "/api/v1/audio/search",
                doc(config -> {
                    config.setDescription("Search for audio files");
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
                        browseService.searchFiles(query, page, limit, MediaTypeFilter.MUSIC),
                        call
                    ));
                })
            );
        }

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/audio/playlists",
                doc(config -> {
                    config.setDescription("Create a new playlist");
                    config.request(requestDoc(request -> request.body(CreatePlaylistRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_CREATED, bodyDoc(Playlist.class));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid request body"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    CreatePlaylistRequest request = call.receive(CreatePlaylistRequest.class);
                    Playlist playlist = playlistService.createPlaylist(
                        request.name(),
                        request.description(),
                        request.tracks()
                    );
                    call.respond(HTTP_CREATED, playlist);
                })
            );
        });

        route.get(
            "/api/v1/audio/playlists",
            doc(config -> {
                config.setDescription("List all playlists");
                config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(List.class))));
            }),
            handler(scope -> scope.getCall().respond(playlistService.listPlaylists()))
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/audio/playlists/import",
                doc(config -> {
                    config.setDescription("Import a playlist from M3U format");
                    config.request(requestDoc(request -> {
                        request.queryParameter("name", optionalParam("Playlist name"));
                        request.queryParameter("base_dir", optionalParam("Base directory for resolving relative paths"));
                        request.body(String.class);
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_CREATED, bodyDoc(Playlist.class));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid M3U content or path"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String m3uContent = call.receiveText();
                    String name = call.getQueryParameters().get("name");
                    String baseDir = call.getQueryParameters().get("base_dir");
                    Playlist playlist = playlistService.importM3U(
                        m3uContent,
                        name == null ? "Imported Playlist" : name,
                        baseDir
                    );
                    call.respond(HTTP_CREATED, playlist);
                })
            );
        });

        route.get(
            "/api/v1/audio/playlists/{id}",
            doc(config -> {
                config.setDescription("Get a playlist by ID");
                config.request(requestDoc(request -> request.pathParameter("id", requiredParam("Playlist ID"))));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(Playlist.class));
                    response.code(HTTP_NOT_FOUND, describe("Playlist not found"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String id = call.getPathParameters().get("id");
                if (id == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing playlist ID");
                }
                Playlist playlist = playlistService.getPlaylist(id);
                if (playlist == null) {
                    throw nyxException(ErrorCode.PLAYLIST_NOT_FOUND, "Playlist not found: " + id);
                }
                call.respond(playlist);
            })
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.put(
                "/api/v1/audio/playlists/{id}",
                doc(config -> {
                    config.setDescription("Update a playlist");
                    config.request(requestDoc(request -> {
                        request.pathParameter("id", requiredParam("Playlist ID"));
                        request.body(UpdatePlaylistRequest.class);
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(Playlist.class));
                        response.code(HTTP_NOT_FOUND, describe("Playlist not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String id = call.getPathParameters().get("id");
                    if (id == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing playlist ID");
                    }
                    UpdatePlaylistRequest request = call.receive(UpdatePlaylistRequest.class);
                    call.respond(playlistService.updatePlaylist(id, request.name(), request.description(), request.tracks()));
                })
            );

            authenticatedRoute.delete(
                "/api/v1/audio/playlists/{id}",
                doc(config -> {
                    config.setDescription("Delete a playlist");
                    config.request(requestDoc(request -> request.pathParameter("id", requiredParam("Playlist ID"))));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_NO_CONTENT, describe("Playlist deleted"));
                        response.code(HTTP_NOT_FOUND, describe("Playlist not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String id = call.getPathParameters().get("id");
                    if (id == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing playlist ID");
                    }
                    playlistService.deletePlaylist(id);
                    call.respond(HTTP_NO_CONTENT);
                })
            );
        });

        route.get(
            "/api/v1/audio/playlists/{id}/export",
            doc(config -> {
                config.setDescription("Export a playlist to M3U format");
                config.request(requestDoc(request -> request.pathParameter("id", requiredParam("Playlist ID"))));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, describe("M3U playlist content (audio/mpegurl)"));
                    response.code(HTTP_NOT_FOUND, describe("Playlist not found"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String id = call.getPathParameters().get("id");
                if (id == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing playlist ID");
                }
                call.respondText(playlistService.exportM3U(id), new ContentType("audio", "mpegurl"));
            })
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/audio/playlists/{id}/reorder",
                doc(config -> {
                    config.setDescription("Reorder tracks in a playlist");
                    config.request(requestDoc(request -> {
                        request.pathParameter("id", requiredParam("Playlist ID"));
                        request.body(ReorderTracksRequest.class);
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(Playlist.class));
                        response.code(HTTP_NOT_FOUND, describe("Playlist not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String id = call.getPathParameters().get("id");
                    if (id == null) {
                        throw nyxException(ErrorCode.INVALID_REQUEST, "Missing playlist ID");
                    }
                    ReorderTracksRequest request = call.receive(ReorderTracksRequest.class);
                    call.respond(playlistService.reorderTracks(id, request.trackIds()));
                })
            );
        });
    }

    private static AudioNegotiationRequest resolveAudioNegotiationRequest(
        AudioNegotiationRequest request,
        MediaFileService mediaFileService,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver,
        MediaObjectResolver mediaObjectResolver
    ) {
        Path resolvedPath = resolveAudioPath(request.source().path(), pathSecurity, virtualPathResolver);
        var mediaObject = mediaObjectResolver == null
            ? null
            : mediaObjectResolver.resolveOrCreate(resolvedPath, MediaObjectResolveOptions.AUDIO_ONLY);
        AudioNegotiationRequest pathResolvedRequest = withSource(
            request,
            withResolvedPath(
                request.source(),
                resolvedPath,
                mediaObject == null ? request.source().objectId() : mediaObject.objectId(),
                mediaObject == null ? request.source().mediaKind() : mediaObject.mediaKind()
            )
        );
        return mediaFileService.resolveAudioNegotiationRequest(pathResolvedRequest);
    }

    private static Path resolveAudioPath(
        String path,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        Path resolvedPath = virtualPathResolver == null
            ? pathSecurity.validate(path)
            : resolvePathParam(path, pathSecurity, virtualPathResolver);
        String mimeType = MediaTypes.detectMimeType(resolvedPath);
        if (!MediaTypes.isAudio(mimeType)) {
            throw nyxException(ErrorCode.AUDIO_NOT_FOUND, "Not an audio file: " + path);
        }
        return resolvedPath;
    }

    private static void respondAudioSessionContent(
        RoutingCall call,
        String sessionId,
        String owner,
        AudioSessionService audioSessionService,
        AudioTranscoder audioTranscoder
    ) {
        AudioSession session = audioSessionService.getSession(sessionId, owner);
        if (session == null) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Audio session not found: " + sessionId);
        }
        PlaybackSessionLifecycle lifecycle = session.lifecycle();
        if (lifecycle == null) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Audio session lifecycle is missing");
        }
        PlaybackLifecyclePhase phase = lifecycle.phase();
        if (phase == PlaybackLifecyclePhase.STOPPED) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Audio session content is no longer available: " + sessionId);
        }
        if (phase != PlaybackLifecyclePhase.READY) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Audio session is not ready for content");
        }

        Path sourcePath = audioSessionService.getSourcePath(sessionId, owner);
        if (sourcePath == null) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Audio source not found for session: " + sessionId);
        }
        AudioNegotiationRequest request = audioSessionService.getSessionRequest(sessionId, owner);
        if (request == null) {
            throw nyxException(ErrorCode.JOB_NOT_FOUND, "Audio request not found for session: " + sessionId);
        }
        var decision = session.decision();
        if (decision == null) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Audio session is missing a negotiation decision");
        }

        if (decision.mode() == AudioDeliveryMode.DIRECT_PLAY) {
            RangeSupport.respondFile(call, sourcePath, MediaTypes.detectMimeType(sourcePath));
            return;
        }

        AudioTranscoder.TranscodeTarget target = audioTranscoder.resolveTranscodeTarget(decision.output());
        if (target == null) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Audio session cannot resolve a transcode target");
        }
        call.getResponse().header(HttpHeaders.ContentType, target.mimeType());
        call.respondBytesWriter(output -> {
            audioTranscoder.transcode(sourcePath, target, output, request.startPositionMillis());
        });
    }

    private static AudioNegotiationRequest withSource(AudioNegotiationRequest request, MediaSourceRef source) {
        return new AudioNegotiationRequest(
            source,
            request.startPositionMillis(),
            request.client(),
            request.capabilities(),
            request.constraints(),
            request.output()
        );
    }

    private static MediaSourceRef withResolvedPath(
        MediaSourceRef source,
        Path resolvedPath,
        String objectId,
        com.nyx.media.contracts.MediaKind mediaKind
    ) {
        return new MediaSourceRef(resolvedPath.toString(), source.characteristics(), objectId, mediaKind);
    }

    private static String principalName(RoutingCall call) {
        UserIdPrincipal principal = call.principal(UserIdPrincipal.class);
        return principal == null ? null : principal.getName();
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.OPTIONAL, authProviders));
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
