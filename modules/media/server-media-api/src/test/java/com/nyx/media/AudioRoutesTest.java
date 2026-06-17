package com.nyx.media;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.browse.BrowseService;
import com.nyx.common.DatabaseResources;
import com.nyx.common.HealthMonitor;
import com.nyx.common.ManagedService;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.AudioConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QuotaConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.UserIdPrincipal;
import com.nyx.json.NyxJson;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.model.CreatePlaylistRequest;
import com.nyx.playback.LocalAudioSessionService;
import com.nyx.playback.contracts.AudioCapabilitySet;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.MediaSourceRef;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioRoutesTest {
    @TempDir
    Path tempDir;

    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<ManagedService> managedServices = new ArrayList<>();
    private final ObjectMapper json = NyxJson.newMapper();

    private Path mediaDir;
    private int dbCounter;

    @BeforeEach
    void setUp() throws Exception {
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
        dbCounter = 0;
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int index = managedServices.size() - 1; index >= 0; index--) {
            managedServices.get(index).shutdown();
        }
        managedServices.clear();
        MediaApiTestSupport.closeDataSources(dataSources);
    }

    private record RouteServices(
        MediaFileService mediaFileService,
        AudioTranscoder audioTranscoder,
        LocalAudioNegotiationService audioNegotiationService,
        LocalAudioSessionService audioSessionService,
        PlaylistService playlistService,
        PathSecurity pathSecurity,
        MediaObjectService mediaObjectService,
        MediaObjectResolver mediaObjectResolver,
        UserMediaStateService userMediaStateService,
        VirtualPathResolver virtualPathResolver,
        BrowseService browseService
    ) {
    }

    private RouteServices createServices(AudioTranscoder audioTranscoder, boolean withMediaObjects, boolean withVirtualPaths) throws Exception {
        ProbeService probeService = new ProbeService();
        AudioMetadataService audioMetadataService = new AudioMetadataService(probeService);
        PathSecurity pathSecurity = new PathSecurity(List.of(tempDir));
        LocalAudioNegotiationService audioNegotiationService = new LocalAudioNegotiationService(audioTranscoder);
        LocalAudioSessionService audioSessionService = new LocalAudioSessionService(audioNegotiationService);
        managedServices.add(audioSessionService);

        DatabaseResources playlistResources = PlaylistService.createDatabase(tempDir.resolve("playlists-db-" + (++dbCounter)));
        dataSources.add(playlistResources.getDataSource());
        PlaylistService playlistService = new PlaylistService(playlistResources.getJdbi());

        MediaObjectService mediaObjectService = null;
        MediaObjectResolver mediaObjectResolver = null;
        UserMediaStateService userMediaStateService = null;
        if (withMediaObjects) {
            DatabaseResources mediaResources = MediaObjectService.createDatabase(tempDir.resolve("media-objects-db-" + dbCounter));
            dataSources.add(mediaResources.getDataSource());
            mediaObjectService = new MediaObjectService(mediaResources.getJdbi());
            mediaObjectResolver = new MediaObjectResolver(mediaObjectService, probeService, audioMetadataService);
            userMediaStateService = new UserMediaStateService(mediaResources.getJdbi());
        }

        VirtualPathResolver virtualPathResolver = null;
        BrowseService browseService = null;
        MediaFileService mediaFileService;
        if (withVirtualPaths) {
            virtualPathResolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaDir, "local", "media")));
            mediaFileService = new MediaFileService(
                List.of(150, 300, 600),
                List.of(tempDir),
                probeService,
                null,
                virtualPathResolver,
                audioMetadataService,
                mediaObjectResolver,
                null
            );
            browseService = new BrowseService(
                virtualPathResolver,
                pathSecurity,
                List.of(150, 300, 600),
                probeService,
                audioMetadataService,
                mediaObjectResolver
            );
        } else {
            mediaFileService = new MediaFileService(List.of(tempDir), probeService, audioMetadataService, mediaObjectResolver);
        }

        return new RouteServices(
            mediaFileService,
            audioTranscoder,
            audioNegotiationService,
            audioSessionService,
            playlistService,
            pathSecurity,
            mediaObjectService,
            mediaObjectResolver,
            userMediaStateService,
            virtualPathResolver,
            browseService
        );
    }

    private void installAuth(MediaApiTestSupport.ApplicationHarness app) {
        app.installBearerAuth(
            "api-token",
            credential -> "alice-token".equals(credential.token()) ? new UserIdPrincipal("alice") : null
        );
    }

    private void registerRoutes(
        MediaApiTestSupport.ApplicationHarness app,
        RouteServices services,
        List<String> authProviders,
        QuotaService quotaService,
        boolean requireOuterAuth
    ) {
        app.routing(route -> {
            var target = requireOuterAuth ? route.withAuth(AuthMode.REQUIRED, List.of("api-token")) : route;
            AudioRoutes.audioRoutes(
                target,
                services.mediaFileService(),
                services.audioTranscoder(),
                services.audioNegotiationService(),
                services.audioSessionService(),
                services.playlistService(),
                services.pathSecurity(),
                authProviders,
                services.virtualPathResolver(),
                services.browseService(),
                quotaService,
                services.mediaObjectResolver()
            );
        });
    }

    private JsonNode readBody(Response response) throws Exception {
        return json.readTree(MediaApiTestSupport.bodyAsText(response));
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize JSON", exception);
        }
    }

    private Path createFakeAudioFile(String name) throws Exception {
        return createFakeAudioFile(mediaDir, name);
    }

    private Path createFakeAudioFile(Path dir, String name) throws Exception {
        Path file = dir.resolve(name);
        Files.write(file, sequentialBytes(1024));
        return file;
    }

    private byte[] sequentialBytes(int length) {
        byte[] bytes = new byte[length];
        for (int index = 0; index < length; index++) {
            bytes[index] = (byte) index;
        }
        return bytes;
    }

    private Path createScript(String name, String body) throws Exception {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/bash\n" + body + "\n");
        Files.setPosixFilePermissions(
            script,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        );
        return script;
    }

    @Test
    void browseAndSearchSupportPagingMetadataSortsValidationAndObjectIdentity() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(new AudioTranscoder(), true, true);
            createFakeAudioFile("song-a.mp3");
            createFakeAudioFile("song-b.flac");
            createFakeAudioFile("album-track.mp3");
            Files.writeString(mediaDir.resolve("readme.txt"), "hello");

            registerRoutes(app, services, List.of(), null, false);

            try (Response browse = app.client().get("/api/v1/audio/browse?dir=media&page=2&limit=2&sort=artist")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(browse));
                JsonNode body = readBody(browse);
                assertEquals(3, body.path("total").asInt());
                assertEquals(2, body.path("page").asInt());
                assertEquals(2, body.path("limit").asInt());
                assertEquals(1, body.path("tracks").size());
                JsonNode track = body.path("tracks").get(0);
                assertEquals("AUDIO", track.path("mediaKind").asText());
                assertNotNull(track.path("objectId").asText(null));
            }

            try (Response search = app.client().get("/api/v1/audio/search?query=song&page=1&limit=10")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(search));
                JsonNode body = readBody(search);
                assertEquals("song", body.path("query").asText());
                assertEquals(2, body.path("total").asInt());
            }

            try (Response missingDir = app.client().get("/api/v1/audio/browse")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(missingDir));
            }

            try (Response invalidSort = app.client().get("/api/v1/audio/browse?dir=media&sort=invalid")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(invalidSort));
            }

            try (Response invalidPage = app.client().get("/api/v1/audio/browse?dir=media&page=0")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(invalidPage));
            }

            try (Response invalidLimit = app.client().get("/api/v1/audio/browse?dir=media&limit=201")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(invalidLimit));
            }
        });
    }

    @Test
    void audioFileServesDirectBytesTranscodesAndRejectsNonAudio() throws Exception {
        Path ffmpegScript = createScript("fake-ffmpeg.sh", "printf 'transcoded-aac'");
        AudioTranscoder audioTranscoder = new AudioTranscoder(ffmpegScript.toString(), null, null, new AudioConfig());

        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(audioTranscoder, true, false);
            Path audioFile = createFakeAudioFile("song.mp3");
            Path textFile = mediaDir.resolve("readme.txt");
            Files.writeString(textFile, "hello");

            registerRoutes(app, services, List.of(), null, false);

            try (Response direct = app.client().get("/api/v1/audio/file?path=" + audioFile)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(direct));
                assertEquals(ContentType.parse("audio/mpeg"), MediaApiTestSupport.contentType(direct));
                assertArrayEquals(sequentialBytes(1024), MediaApiTestSupport.readRawBytes(direct));
            }
            assertNotNull(services.mediaObjectService().getByPath(audioFile.toString()));

            try (Response transcoded = app.client().get(
                "/api/v1/audio/file?path=" + audioFile,
                request -> request.header(HttpHeaders.Accept, "audio/aac")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(transcoded));
                assertEquals(ContentType.parse("audio/aac"), MediaApiTestSupport.contentType(transcoded));
                assertEquals("transcoded-aac", new String(MediaApiTestSupport.readRawBytes(transcoded), StandardCharsets.UTF_8));
            }

            try (Response missingPath = app.client().get("/api/v1/audio/file")) {
                assertEquals(HttpStatusCode.BadRequest, MediaApiTestSupport.status(missingPath));
            }

            try (Response rejected = app.client().get("/api/v1/audio/file?path=" + textFile)) {
                assertEquals(HttpStatusCode.NotFound, MediaApiTestSupport.status(rejected));
            }
            assertNull(services.mediaObjectService().getByPath(textFile.toString()));
        });
    }

    @Test
    void audioSessionLifecycleServesContentAndMakesClosedSessionsUnavailable() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(new AudioTranscoder(), false, false);
            Path audioFile = createFakeAudioFile("session.mp3");

            registerRoutes(app, services, List.of(), null, false);

            String requestBody = json.writeValueAsString(
                new AudioNegotiationRequest(
                    new MediaSourceRef(audioFile.toString()),
                    0L,
                    null,
                    new AudioCapabilitySet(),
                    null,
                    null
                )
            );

            String sessionId;
            try (Response created = app.client().post("/api/v1/audio/sessions", request -> {
                request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                request.setBody(requestBody);
            })) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(created));
                sessionId = readBody(created).path("sessionId").asText();
            }

            try (Response session = app.client().get("/api/v1/audio/sessions/" + sessionId)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(session));
                assertEquals(sessionId, readBody(session).path("sessionId").asText());
            }

            try (Response content = app.client().get("/api/v1/audio/sessions/" + sessionId + "/content")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(content));
                assertEquals(ContentType.parse("audio/mpeg"), MediaApiTestSupport.contentType(content));
                assertArrayEquals(sequentialBytes(1024), MediaApiTestSupport.readRawBytes(content));
            }

            try (Response deleted = app.client().delete("/api/v1/audio/sessions/" + sessionId)) {
                assertEquals(HttpStatusCode.NoContent, MediaApiTestSupport.status(deleted));
            }

            try (Response missing = app.client().get("/api/v1/audio/sessions/" + sessionId + "/content")) {
                assertEquals(HttpStatusCode.NotFound, MediaApiTestSupport.status(missing));
            }
        });
    }

    @Test
    void playlistRoutesSupportCrudReorderImportExportAndAuthProtection() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(new AudioTranscoder(), false, false);
            Path firstTrack = createFakeAudioFile("first.mp3");
            Path secondTrack = createFakeAudioFile("second.mp3");

            installAuth(app);
            registerRoutes(app, services, List.of("api-token"), null, false);

            try (Response unauthorizedCreate = app.client().post("/api/v1/audio/playlists", request -> {
                request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                request.setBody(writeJson(new CreatePlaylistRequest("Road Trip", "Mix", List.of(
                    firstTrack.toString(),
                    secondTrack.toString()
                ))));
            })) {
                assertEquals(HttpStatusCode.Unauthorized, MediaApiTestSupport.status(unauthorizedCreate));
            }

            String playlistId;
            try (Response created = app.client().post("/api/v1/audio/playlists", request -> {
                request.header(HttpHeaders.Authorization, "Bearer alice-token");
                request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                request.setBody(writeJson(new CreatePlaylistRequest("Road Trip", "Mix", List.of(
                    firstTrack.toString(),
                    secondTrack.toString()
                ))));
            })) {
                assertEquals(HttpStatusCode.Created, MediaApiTestSupport.status(created));
                playlistId = readBody(created).path("id").asText();
            }

            JsonNode playlist;
            try (Response fetched = app.client().get("/api/v1/audio/playlists/" + playlistId)) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(fetched));
                playlist = readBody(fetched);
                assertEquals("Road Trip", playlist.path("name").asText());
                assertEquals(2, playlist.path("tracks").size());
            }

            try (Response updated = app.client().put("/api/v1/audio/playlists/" + playlistId, request -> {
                request.header(HttpHeaders.Authorization, "Bearer alice-token");
                request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                request.setBody("""
                    {
                      "name": "Late Night",
                      "description": "Updated",
                      "tracks": ["%s", "%s"]
                    }
                    """.formatted(secondTrack, firstTrack).trim());
            })) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(updated));
                assertEquals("Late Night", readBody(updated).path("name").asText());
            }

            try (Response beforeReorder = app.client().get("/api/v1/audio/playlists/" + playlistId)) {
                JsonNode tracks = readBody(beforeReorder).path("tracks");
                String firstId = tracks.get(0).path("id").asText();
                String secondId = tracks.get(1).path("id").asText();

                try (Response reordered = app.client().post("/api/v1/audio/playlists/" + playlistId + "/reorder", request -> {
                    request.header(HttpHeaders.Authorization, "Bearer alice-token");
                    request.header(HttpHeaders.ContentType, ContentType.Application.Json.getValue());
                    request.setBody("""
                        {
                          "trackIds": ["%s", "%s"]
                        }
                        """.formatted(secondId, firstId).trim());
                })) {
                    assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(reordered));
                    JsonNode reorderedTracks = readBody(reordered).path("tracks");
                    assertEquals(secondId, reorderedTracks.get(0).path("id").asText());
                    assertEquals(firstId, reorderedTracks.get(1).path("id").asText());
                }
            }

            String exported;
            try (Response exportedResponse = app.client().get("/api/v1/audio/playlists/" + playlistId + "/export")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(exportedResponse));
                exported = MediaApiTestSupport.bodyAsText(exportedResponse);
                assertTrue(exported.contains("#EXTM3U"));
                assertTrue(exported.contains(firstTrack.getFileName().toString()));
            }

            try (Response imported = app.client().post("/api/v1/audio/playlists/import?name=Imported", request -> {
                request.header(HttpHeaders.Authorization, "Bearer alice-token");
                request.header(HttpHeaders.ContentType, ContentType.Text.Plain.getValue());
                request.setBody(exported);
            })) {
                assertEquals(HttpStatusCode.Created, MediaApiTestSupport.status(imported));
                assertEquals("Imported", readBody(imported).path("name").asText());
            }

            try (Response deleted = app.client().delete("/api/v1/audio/playlists/" + playlistId, request ->
                request.header(HttpHeaders.Authorization, "Bearer alice-token"))) {
                assertEquals(HttpStatusCode.NoContent, MediaApiTestSupport.status(deleted));
            }

            try (Response missing = app.client().get("/api/v1/audio/playlists/" + playlistId)) {
                assertEquals(HttpStatusCode.NotFound, MediaApiTestSupport.status(missing));
            }
        });
    }

    @Test
    void rateLimitedBrowseReturns429AfterTheWindowIsExhausted() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(new AudioTranscoder(), false, false);
            createFakeAudioFile("quota.mp3");
            installAuth(app);

            QuotaService quotaService = new QuotaService(
                new QuotaConfig(true, 4, 1, 10_737_418_240L, Map.of()),
                userId -> 0
            );
            registerRoutes(app, services, List.of(), quotaService, true);

            try (Response first = app.client().get(
                "/api/v1/audio/browse?dir=" + mediaDir,
                request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
            )) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(first));
            }

            try (Response second = app.client().get(
                "/api/v1/audio/browse?dir=" + mediaDir,
                request -> request.header(HttpHeaders.Authorization, "Bearer alice-token")
            )) {
                assertEquals(HttpStatusCode.TooManyRequests, MediaApiTestSupport.status(second));
            }
        });
    }

    @Test
    void virtualPathRoutesServeBrowseFileAndSearch() throws Exception {
        MediaApiTestSupport.testApplication(app -> {
            RouteServices services = createServices(new AudioTranscoder(), false, true);
            createFakeAudioFile("virtual-song.mp3");

            registerRoutes(app, services, List.of(), null, false);

            try (Response browse = app.client().get("/api/v1/audio/browse?dir=media")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(browse));
                assertEquals(1, readBody(browse).path("total").asInt());
            }

            try (Response file = app.client().get("/api/v1/audio/file?path=media/virtual-song.mp3")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(file));
                assertArrayEquals(sequentialBytes(1024), MediaApiTestSupport.readRawBytes(file));
            }

            try (Response search = app.client().get("/api/v1/audio/search?query=virtual")) {
                assertEquals(HttpStatusCode.OK, MediaApiTestSupport.status(search));
                assertEquals(1, readBody(search).path("total").asInt());
            }
        });
    }

    @Test
    void audioTranscoderThrowsFfmpegUnavailableWhenHealthCheckFails() throws Exception {
        AudioTranscoder transcoder = new AudioTranscoder(
            "ffmpeg",
            null,
            new HealthMonitor() {
                @Override
                public boolean isFfmpegAvailable() {
                    return false;
                }
            },
            new AudioConfig()
        );

        NyxException exception = assertThrows(
            NyxException.class,
            () -> transcoder.transcode(createFakeAudioFile("health.mp3"), transcoder.availableTargets().getFirst(), new java.io.ByteArrayOutputStream())
        );
        assertEquals("FFMPEG_UNAVAILABLE", exception.getErrorCode().name());
    }
}
