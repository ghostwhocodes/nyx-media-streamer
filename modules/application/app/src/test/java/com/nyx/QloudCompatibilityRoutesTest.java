package com.nyx;

import static com.nyx.AppTestSupport.bodyAsText;
import static com.nyx.AppTestSupport.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.browse.BrowseService;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.common.storage.LocalStorageBackend;
import com.nyx.config.AuthConfig;
import com.nyx.config.CompatibilityConfig;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.QloudCompatibilityConfig;
import com.nyx.config.ServerConfig;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.VideoPreviewGenerator;
import com.nyx.ffmpeg.VideoPreviewPlan;
import com.nyx.ffmpeg.VideoPreviewRequest;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.json.NyxJson;
import com.nyx.media.ThumbnailService;
import com.nyx.media.VideoPreviewService;
import com.nyx.media.contracts.MediaKind;
import com.nyx.playback.LocalPlaybackDeliveryService;
import com.nyx.playback.contracts.AudioTrackSelectionMode;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackDeliveryFailed;
import com.nyx.playback.contracts.PlaybackDeliveryOutcome;
import com.nyx.playback.contracts.PlaybackDeliveryRequest;
import com.nyx.playback.contracts.PlaybackDeliveryService;
import com.nyx.playback.contracts.PlaybackDeliverySessionRequest;
import com.nyx.playback.contracts.PlaybackLifecyclePhase;
import com.nyx.playback.contracts.PlaybackOutputPreferences;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionArtifacts;
import com.nyx.playback.contracts.PlaybackSessionLifecycle;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import com.nyx.qloud.QloudCompatibilityRoutes;
import com.nyx.transcode.contracts.BatchCancelResponse;
import com.nyx.transcode.contracts.BatchStatusResponse;
import com.nyx.transcode.contracts.BatchSubmitResponse;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import com.nyx.transcode.contracts.TranscodeRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Delayed;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QloudCompatibilityRoutesTest {
    private static final ObjectMapper JSON = NyxJson.newMapper();
    private static final BigInteger QLOUD_PROC_MODULUS = new BigInteger(
        """
        90949c4c328f5c8ec3faf55152d73500fc198ebf119fea2aea6d961422e0896a2fa44371818cda0c
        1738e06227b4037562c929fe75c6606c29232ae4e81c0232b9331deece6637c33c798dd10c0a06de
        b1b04da9d4f2d2c19d31291867679cbd8e3421d27bfae66bfd1ed372c03edbe3db85c5a94b9c5519
        806d2a40ddec5a9f
        """.replaceAll("\\s+", ""),
        16
    );
    private static final BigInteger QLOUD_PROC_PUBLIC_EXPONENT = new BigInteger("10001", 16);

    @TempDir
    Path tempDir;

    @Test
    void helloEndpointMatchesCapturedQloudHandshakeShape() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response response = harness.client().post("/proc/hello", request -> {
                request.header("Accept-Encoding", "gzip, identity");
                request.setBody("""
                    {
                      "action": "hello",
                      "version": 11,
                      "protocol-version": 35,
                      "ignore-protocol-version": true
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertNull(response.header("Content-Encoding"));
                Map<String, Object> body = jsonBody(response);
                assertEquals("hello", body.get("action"));
                assertEquals(11, ((Number) body.get("version")).intValue());
                assertEquals(35, ((Number) body.get("protocol-version")).intValue());
                assertEquals(35, ((Number) body.get("server-protocol-version")).intValue());
                assertEquals("public", body.get("server-access"));
                assertEquals("4.1.1", body.get("server-version"));
                assertQloudSignedToken(body.get("token").toString());
            }
        }
    }

    @Test
    void helloAndAuthCanBridgeHandshakeTokensFromConfiguredQloudUpstream() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/proc/hello", exchange ->
            respondUpstreamJson(exchange, "{\"token\":\"hello-from-upstream\"}")
        );
        upstream.createContext("/proc/auth", exchange ->
            respondUpstreamJson(exchange, "{\"token\":\"auth-from-upstream\"}")
        );
        upstream.start();

        String previous = System.setProperty(
            "nyx.qloud.handshake.upstream",
            "http://127.0.0.1:" + upstream.getAddress().getPort()
        );
        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response hello = harness.client().post("/proc/hello", request -> request.setBody("""
                {
                  "action": "hello",
                  "version": 11,
                  "protocol-version": 35,
                  "ignore-protocol-version": true,
                  "magic": "native-client-magic"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(hello));
                assertEquals("hello-from-upstream", jsonBody(hello).get("token"));
            }

            try (Response auth = harness.client().post("/proc/auth", request -> request.setBody("""
                {
                  "action": "login",
                  "auth": "native-client-auth",
                  "client": "normal",
                  "protocol-version": 35,
                  "magic": "native-client-magic"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(auth));
                assertEquals("auth-from-upstream", jsonBody(auth).get("token"));
            }
        } finally {
            restoreProperty("nyx.qloud.handshake.upstream", previous);
            upstream.stop(0);
        }
    }

    @Test
    void listEndpointPresentsVirtualRootsAndMediaItemsInQloudShape() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response rootResponse = harness.client().post("/proc/list", request -> {
                request.setBody("{\"action\":\"browse\",\"path\":\"/\",\"media\":\"video\"}");
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(rootResponse));
                Map<String, Object> body = jsonBody(rootResponse);
                List<Map<String, Object>> items = items(body);
                assertEquals("/", body.get("path"));
                assertEquals("/library/movie.mp4", items.getFirst().get("path"));
                assertEquals("video", items.getFirst().get("type"));
            }

            try (Response libraryResponse = harness.client().post("/proc/list", request -> {
                request.setBody("{\"action\":\"browse\",\"path\":\"/library\",\"media\":\"video\"}");
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(libraryResponse));
                Map<String, Object> body = jsonBody(libraryResponse);
                List<Map<String, Object>> items = items(body);
                assertEquals("/library/movie.mp4", items.getFirst().get("path"));
                assertEquals("video", items.getFirst().get("type"));
            }
        }
    }

    @Test
    void recentBrowseListsVideosByModifiedTime() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path older = mediaRoot.resolve("older.mp4");
        Path newer = mediaRoot.resolve("newer.mp4");
        Files.writeString(older, "old movie");
        Files.writeString(newer, "new movie");
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_700_000_000_000L));
        Files.setLastModifiedTime(newer, FileTime.fromMillis(1_800_000_000_000L));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response response = harness.client().post("/proc/list", request -> {
                request.setBody("{\"action\":\"browse-recent\",\"media\":\"video\",\"limit\":1}");
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                Map<String, Object> body = jsonBody(response);
                List<Map<String, Object>> items = items(body);
                assertEquals(1, items.size());
                assertEquals("/library/newer.mp4", items.getFirst().get("path"));
                assertEquals("video", items.getFirst().get("type"));
                assertEquals(1, ((Number) body.get("limit")).intValue());
            }
        }
    }

    @Test
    void browseEndpointSynthesizesFolderCountsAndModifiedTimes() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path album = Files.createDirectories(mediaRoot.resolve("Album"));
        Files.writeString(album.resolve("track-1.mp3"), "track one");
        Files.writeString(album.resolve("track-2.mp3"), "track two");
        Files.setLastModifiedTime(album, FileTime.fromMillis(1_900_000_000_000L));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response response = harness.client().post("/proc/list", request ->
                request.setBody("{\"action\":\"browse\",\"path\":\"/library\"}")
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                Map<String, Object> folder = items(jsonBody(response)).stream()
                    .filter(item -> "/library/Album".equals(item.get("path")))
                    .findFirst()
                    .orElseThrow();
                assertEquals(2, ((Number) folder.get("size")).intValue());
                assertEquals(1_900_000_000_000L, ((Number) folder.get("last-modified")).longValue());
            }
        }
    }

    @Test
    void listEndpointTreatsLegacyFilePathBrowseAsContainingFolderBrowse() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response response = harness.client().post("/proc/list", request -> request.setBody("""
                {
                  "action": "browse",
                  "path": "/library/movie.mp4",
                  "media": "video",
                  "sort": "new",
                  "cache-folders": 30,
                  "cache": 30,
                  "samples": 0,
                  "session": "legacy-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                Map<String, Object> body = jsonBody(response);
                assertEquals("/library", body.get("path"));
                assertEquals("legacy-session", body.get("session"));
                assertEquals("new", body.get("sort"));
                List<Map<String, Object>> items = items(body);
                assertEquals("/library/movie.mp4", items.getFirst().get("path"));
                assertEquals("video", items.getFirst().get("type"));
            }
        }
    }

    @Test
    void legacyQloudClientContractBridgesTokensBrowsesFilePathAndStreamsTsThroughProxyHost() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("legacy-client-segments"));
        Files.writeString(segmentDir.resolve("manifest0.ts"), "first ts segment");

        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/proc/hello", exchange ->
            respondUpstreamJson(exchange, "{\"token\":\"hello-from-native-qloud\"}")
        );
        upstream.createContext("/proc/auth", exchange ->
            respondUpstreamJson(exchange, "{\"token\":\"auth-from-native-qloud\"}")
        );
        upstream.start();

        String previousHandshake = System.setProperty(
            "nyx.qloud.handshake.upstream",
            "http://127.0.0.1:" + upstream.getAddress().getPort()
        );
        String previousLegacyTs = System.setProperty("nyx.qloud.legacy.ts.hls", "true");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        FakeTranscodeService transcode = new FakeTranscodeService(segmentDir, List.of("""
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:6.0,
            segments/manifest0.ts
            #EXT-X-ENDLIST
            """));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            transcode,
            new FakeSegmentCache()
        )) {
            try (Response hello = harness.client().post("/proc/hello", request -> {
                request.header("Host", "compat.example.test:8091");
                request.setBody("""
                    {
                      "action": "hello",
                      "version": 11,
                      "protocol-version": 35,
                      "ignore-protocol-version": true,
                      "magic": "native-client-magic"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(hello));
                assertEquals("hello-from-native-qloud", jsonBody(hello).get("token"));
            }

            try (Response auth = harness.client().post("/proc/auth", request -> {
                request.header("Host", "compat.example.test:8091");
                request.setBody("""
                    {
                      "action": "login",
                      "auth": "native-client-auth",
                      "client": "normal",
                      "protocol-version": 35,
                      "platform": "android",
                      "platform-version": 27,
                      "magic": "native-client-magic"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(auth));
                Map<String, Object> body = jsonBody(auth);
                assertEquals("auth-from-native-qloud", body.get("token"));
                assertEquals("compat.example.test", body.get("server-external-address"));
                assertEquals(8091, ((Number) body.get("server-external-port")).intValue());
                assertEquals("compat.example.test", body.get("server-local-address"));
                assertEquals(8091, ((Number) body.get("server-local-port")).intValue());
            }

            try (Response browse = harness.client().post("/proc/list", request -> {
                request.header("Host", "compat.example.test:8091");
                request.setBody("""
                    {
                      "action": "browse",
                      "media": "video",
                      "path": "/library/movie.mp4",
                      "cache-folders": 30,
                      "cache": 30,
                      "samples": 0,
                      "session": "legacy-client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(browse));
                Map<String, Object> body = jsonBody(browse);
                assertEquals("/library", body.get("path"));
                List<Map<String, Object>> items = items(body);
                assertEquals("/library/movie.mp4", items.getFirst().get("path"));
                assertEquals("video", items.getFirst().get("type"));
            }

            String masterUrl;
            try (Response play = harness.client().post("/proc/video", request -> {
                request.header("Host", "compat.example.test:8091");
                request.setBody("""
                    {
                      "action": "play",
                      "path": "/library/movie.mp4",
                      "seek": 0,
                      "session": "legacy-client-session",
                      "protocol-scheme": "http",
                      "bandwidth": 1280000
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(play));
                Map<String, Object> body = jsonBody(play);
                masterUrl = body.get("url").toString();
                assertTrue(masterUrl.startsWith("http://compat.example.test:8091/"), masterUrl);
                assertEquals("httplive", body.get("protocol"));
                assertEquals(0, ((Number) body.get("precache")).intValue());
                assertEquals(1, ((Number) body.get("audio-boost")).intValue());
            }

            assertNotNull(playback.lastRequest());
            assertEquals(StreamRepresentation.HLS_MPEG_TS, playback.lastRequest().output().preferredRepresentation());

            try (Response master = harness.client().get(URI.create(masterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(master));
                String variantPath = firstUriPath(bodyAsText(master), "video.m3u8");
                try (Response variant = harness.client().get(variantPath)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(variant));
                    String variantBody = bodyAsText(variant);
                    assertTrue(variantBody.contains("manifest0.ts"), variantBody);
                    assertFalse(variantBody.contains("#EXT-X-MAP"), variantBody);
                    String segmentPath = firstUriPath(variantBody, "manifest0.ts");

                    try (Response segment = harness.client().get(segmentPath)) {
                        assertEquals(HttpStatusCode.Companion.getOK(), status(segment));
                        assertEquals("first ts segment", bodyAsText(segment));
                    }
                }
            }
        } finally {
            restoreProperty("nyx.qloud.handshake.upstream", previousHandshake);
            restoreProperty("nyx.qloud.legacy.ts.hls", previousLegacyTs);
            upstream.stop(0);
        }
    }

    @Test
    void videoPlayEndpointBridgesQloudHlsUrlsToNyxPlaybackArtifacts() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        FakeTranscodeService transcode = new FakeTranscodeService(segmentDir);
        FakeSegmentCache segmentCache = new FakeSegmentCache();
        segmentCache.setAcquiredPath(segmentDir.resolve("chunk_0_001.m4s"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, playback, transcode, segmentCache)) {
            String playRequest = """
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "session": "client-session",
                  "protocol": "hls",
                  "protocol-scheme": "http",
                  "bandwidth": 1280000
                }
                """;
            String masterUrl;
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody(playRequest))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                Map<String, Object> body = jsonBody(playResponse);
                masterUrl = body.get("url").toString();
                assertEquals(harness.app().port(), URI.create(masterUrl).getPort());
                assertTrue(masterUrl.contains(".%28_n_y_x_%29.m3u8"), masterUrl);
                assertEquals("/library/movie.mp4", body.get("path"));
                assertEquals("hls", body.get("protocol"));
                assertEquals(false, body.get("precache"));
                assertFalse(body.containsKey("audio-boost"));
            }
            assertNotNull(playback.lastRequest());
            assertEquals(StreamRepresentation.HLS_FMP4, playback.lastRequest().output().preferredRepresentation());

            try (Response masterResponse = harness.client().get(URI.create(masterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(masterResponse));
                String master = bodyAsText(masterResponse);
                assertTrue(master.contains("#EXT-X-STREAM-INF"), master);
                String variantPath = firstUriPath(master, "video.m3u8");

                try (Response variantResponse = harness.client().get(variantPath)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(variantResponse));
                    String variant = bodyAsText(variantResponse);
                    assertTrue(variant.contains("#EXT-X-MAP:URI=\"http://"), variant);
                    assertTrue(variant.contains("chunk_0_001.m4s"), variant);
                    String segmentPath = firstUriPath(variant, "chunk_0_001.m4s");

                    try (Response segmentResponse = harness.client().get(segmentPath)) {
                        assertEquals(HttpStatusCode.Companion.getOK(), status(segmentResponse));
                        assertEquals("segment", bodyAsText(segmentResponse));
                    }
                }
            }

            assertEquals(List.of(segmentDir.resolve("chunk_0_001.m4s")), segmentCache.releasedPaths());
        }
    }

    @Test
    void videoPlayFallsBackToH264ProfileForHighBandwidthQloudRequests() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        FakePlaybackSessionService playback = new FakePlaybackSessionService();

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, playback, null, null)) {
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "bandwidth": 4000000
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
            }
        }

        assertNotNull(playback.lastRequest());
        assertEquals("h264_balanced", playback.lastRequest().transcode().profileHint());
    }

    @Test
    void videoPlayUsesDefaultQualityFallbacksAndSelectionAliasesForQloudRequests() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path movie = Files.write(mediaRoot.resolve("movie.mkv"), new byte[2_048]);
        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        MediaProber mediaProber = mediaProber(Map.of(
            movie, new ProbeResult(
                movie.toString(),
                "matroska,webm",
                -1.0,
                2_048L,
                new ProbeStreams(
                    List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                    List.of(new AudioStream(1, "aac", 2, 192, 48_000, "eng", "English")),
                    List.of(new SubtitleStream(3, "subrip", "eng", "English"))
                ),
                Map.of()
            )
        ));
        FfmpegConfig emptyQualityPresets = AppTestData.testFfmpegConfig(
            "ffmpeg",
            "ffprobe",
            "6.0",
            2,
            4,
            8,
            Map.of(),
            "polling",
            500L
        );

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            null,
            null,
            List.of(),
            AppTestData.testAuthConfig(),
            new ConcurrentHashMap<>(),
            mediaProber,
            null,
            null,
            emptyQualityPresets
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mkv",
                  "bandwidth": "4000000",
                  "seek": "2.5",
                  "audio-lang": "all",
                  "subtitle-lang": "off"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                Map<String, Object> body = jsonBody(playResponse);
                assertEquals(0.0, ((Number) body.get("duration")).doubleValue());
                assertFalse(body.containsKey("bps"));
            }

            assertNotNull(playback.lastRequest());
            assertEquals("h264_balanced", playback.lastRequest().transcode().profileHint());
            assertEquals(2_500L, playback.lastRequest().startPositionMillis());
            assertEquals(AudioTrackSelectionMode.ALL, playback.lastRequest().selection().audio().mode());
            assertEquals(SubtitleSelectionMode.DISABLE, playback.lastRequest().selection().subtitles().mode());

            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mkv",
                  "bandwidth": 1000000,
                  "audio-lang": "default"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
            }

            assertNotNull(playback.lastRequest());
            assertEquals("h264_fast", playback.lastRequest().transcode().profileHint());
            assertEquals(AudioTrackSelectionMode.DEFAULT, playback.lastRequest().selection().audio().mode());
        }
    }

    @Test
    void videoPlayMapsQloudAudioAndSubtitleHintsIntoPlaybackSelection() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path movie = Files.write(mediaRoot.resolve("movie.mkv"), new byte[2_048]);
        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        MediaProber mediaProber = mediaProber(Map.of(
            movie, new ProbeResult(
                movie.toString(),
                "matroska,webm",
                720.0,
                2_048L,
                new ProbeStreams(
                    List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                    List.of(
                        new AudioStream(1, "aac", 2, 192, 48_000, "eng", "English"),
                        new AudioStream(2, "aac", 2, 192, 48_000, "spa", "Spanish")
                    ),
                    List.of(
                        new SubtitleStream(3, "subrip", "eng", "English"),
                        new SubtitleStream(4, "subrip", "fra", "French")
                    )
                ),
                Map.of()
            )
        ));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            null,
            null,
            List.of(),
            AppTestData.testAuthConfig(),
            new ConcurrentHashMap<>(),
            mediaProber,
            null,
            null
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mkv",
                  "audio-lang": "es",
                  "subtitle-lang": "en",
                  "uniform-subtitle": true
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
            }
        }

        assertNotNull(playback.lastRequest());
        assertEquals(AudioTrackSelectionMode.SPECIFIC, playback.lastRequest().selection().audio().mode());
        assertEquals(List.of(2), playback.lastRequest().selection().audio().trackIndices());
        assertEquals(SubtitleSelectionMode.BURN_IN, playback.lastRequest().selection().subtitles().mode());
        assertEquals(3, playback.lastRequest().selection().subtitles().trackIndex());
    }

    @Test
    void videoPlayReturnsProbedMetadataAndPendingSegmentResponses() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path movie = Files.write(mediaRoot.resolve("movie.mp4"), new byte[2_048]);
        Path segmentDir = Files.createDirectories(tempDir.resolve("pending-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");

        MediaProber mediaProber = mediaProber(Map.of(
            movie, new ProbeResult(
                movie.toString(),
                "mov,mp4,m4a,3gp,3g2,mj2",
                480.0,
                2_048L,
                new ProbeStreams(
                    List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                    List.of(new AudioStream(1, "aac", 2, 192, 48_000, "eng", "Stereo")),
                    List.of()
                ),
                Map.of()
            )
        ));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            new FakePlaybackSessionService(),
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache(),
            List.of(),
            AppTestData.testAuthConfig(),
            new ConcurrentHashMap<>(),
            mediaProber,
            null,
            null
        )) {
            String masterUrl;
            try (Response playResponse = harness.client().post("/proc/video", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "[2001:db8::10]:9443");
                request.setBody("""
                    {
                      "action": "play",
                      "path": "/library/movie.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                Map<String, Object> body = jsonBody(playResponse);
                masterUrl = body.get("url").toString();
                assertTrue(masterUrl.startsWith("https://[2001:db8::10]:9443/"), masterUrl);
                assertEquals(480.0, ((Number) body.get("duration")).doubleValue());
                assertEquals(34L, ((Number) body.get("bps")).longValue());
                assertEquals(1920, ((Number) body.get("width")).intValue());
                assertEquals(1080, ((Number) body.get("height")).intValue());
                assertEquals(1, ((Number) body.get("aspect")).intValue());
            }

            try (Response masterResponse = harness.client().get(URI.create(masterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(masterResponse));
                String variantPath = firstUriPath(bodyAsText(masterResponse), "video.m3u8");
                try (Response variantResponse = harness.client().get(variantPath)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(variantResponse));
                    String segmentPath = firstUriPath(bodyAsText(variantResponse), "chunk_0_001.m4s");
                    try (Response segmentResponse = harness.client().get(segmentPath)) {
                        assertEquals(HttpStatusCode.Companion.getAccepted(), status(segmentResponse));
                        assertEquals("2", segmentResponse.header(HttpHeaders.RetryAfter));
                        Map<String, Object> body = jsonBody(segmentResponse);
                        assertEquals("pending", body.get("status"));
                        assertEquals(2, ((Number) body.get("retry_after")).intValue());
                    }
                }
            }
        }
    }

    @Test
    void compatibilityHelperMethodsHandlePortAndTokenCases() {
        assertEquals(
            443,
            ((Number) invokePrivateStatic(QloudCompatibilityRoutes.class, "defaultPort", new Class<?>[] {String.class}, "https")).intValue()
        );

        Object hostPort = invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "parseHostPort",
            new Class<?>[] {String.class},
            "[2001:db8::10]"
        );
        assertEquals("2001:db8::10", invokeRecordAccessor(hostPort, "host"));
        assertNull(invokeRecordAccessor(hostPort, "port"));

        assertNull(invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "tokenValue",
            new Class<?>[] {Map.class},
            Map.of("token", "   ")
        ));
    }

    @Test
    void compatibilityHelperMethodsHandleParsingFallbackCases() {
        assertEquals(
            "",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "titleFromName", new Class<?>[] {String.class}, (Object) null)
        );
        assertEquals(
            "image",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "qloudType", new Class<?>[] {String.class}, "image/png")
        );
        assertEquals(
            "file",
            invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "qloudType",
                new Class<?>[] {String.class},
                "application/octet-stream"
            )
        );
        assertEquals(
            "",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "toNyxPath", new Class<?>[] {String.class}, "/")
        );
        assertEquals(
            "/",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "toQloudPath", new Class<?>[] {String.class}, (Object) null)
        );
        assertEquals(
            "",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "parentNyxPath", new Class<?>[] {String.class}, "movie.mp4")
        );
        assertEquals(
            "/",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "titleFromPath", new Class<?>[] {Path.class}, Path.of("/"))
        );
        assertEquals(
            "DATE",
            invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "sortOrder",
                new Class<?>[] {Map.class},
                Map.of("sort", "date")
            ).toString()
        );
        assertEquals(
            "NAME",
            invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "sortOrder",
                new Class<?>[] {Map.class},
                Map.of("sort", "unexpected")
            ).toString()
        );
        assertNull(invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "mediaFilter",
            new Class<?>[] {Map.class},
            Map.of()
        ));
        assertEquals(
            "IMAGE",
            invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "mediaFilter",
                new Class<?>[] {Map.class},
                Map.of("media", "photos")
            ).toString()
        );
        assertNull(invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "mediaFilter",
            new Class<?>[] {Map.class},
            Map.of("media", "documents")
        ));
        assertEquals(
            "movie",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "normalizeSearchPattern", new Class<?>[] {String.class}, "**movie**")
        );
        assertEquals(
            0L,
            ((Number) invokePrivateStatic(QloudCompatibilityRoutes.class, "fileSize", new Class<?>[] {Path.class}, (Object) null)).longValue()
        );
        assertEquals(
            0L,
            ((Number) invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "safeLastModified",
                new Class<?>[] {Path.class},
                (Object) null
            )).longValue()
        );
        assertEquals(
            "application/octet-stream",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "defaultMimeType", new Class<?>[] {String.class}, "segment.unknownext")
        );
        assertTrue((Boolean) invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "booleanValue",
            new Class<?>[] {Object.class, boolean.class},
            "true",
            false
        ));
        assertEquals(
            1,
            ((Number) invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "intValue",
                new Class<?>[] {Object.class, int.class, int.class, int.class},
                "-5",
                10,
                1,
                1000
            )).intValue()
        );
        assertEquals(
            1000,
            ((Number) invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "intValue",
                new Class<?>[] {Object.class, int.class, int.class, int.class},
                "2000",
                10,
                1,
                1000
            )).intValue()
        );
        assertEquals(
            7L,
            ((Number) invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "longValue",
                new Class<?>[] {Object.class, long.class},
                "bad",
                7L
            )).longValue()
        );
        assertEquals(
            1.5,
            ((Number) invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "doubleValue",
                new Class<?>[] {Object.class, double.class},
                "bad",
                1.5
            )).doubleValue()
        );
        assertEquals(
            8080,
            ((Number) invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "parsePort",
                new Class<?>[] {String.class, int.class},
                "bad",
                8080
            )).intValue()
        );
        assertNull(invokePrivateStatic(QloudCompatibilityRoutes.class, "explicitPort", new Class<?>[] {String.class}, "-1"));
        assertEquals(
            8443,
            ((Number) invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "explicitPort",
                new Class<?>[] {String.class},
                "8443"
            )).intValue()
        );
        assertEquals(
            "https",
            invokePrivateStatic(QloudCompatibilityRoutes.class, "firstForwardedHeader", new Class<?>[] {String.class}, "https, http")
        );
        assertFalse((Boolean) invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "sameHost",
            new Class<?>[] {String.class, String.class},
            null,
            "compat.example.test"
        ));
        assertTrue((Boolean) invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "isLoopbackHost",
            new Class<?>[] {String.class},
            "127.0.0.1"
        ));
        Object forwardedHost = invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "parseHostPort",
            new Class<?>[] {String.class},
            "compat.example.test"
        );
        Object listenerHost = invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "parseHostPort",
            new Class<?>[] {String.class},
            "internal-listener:8443"
        );
        assertNull(invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "forwardedHostFallbackPort",
            new Class<?>[] {forwardedHost.getClass(), forwardedHost.getClass()},
            forwardedHost,
            null
        ));
        assertEquals(
            8443,
            ((Number) invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "forwardedHostFallbackPort",
                new Class<?>[] {forwardedHost.getClass(), listenerHost.getClass()},
                forwardedHost,
                listenerHost
            )).intValue()
        );
        assertThrows(IllegalStateException.class, () -> invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "useManifestPollingForTesting",
            new Class<?>[] {int.class, long.class},
            0,
            0L
        ));
        assertThrows(IllegalStateException.class, () -> invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "useManifestPollingForTesting",
            new Class<?>[] {int.class, long.class},
            1,
            -1L
        ));
    }

    @Test
    void authEndpointRequiresAuthorizationWhenCompatibilityAuthIsEnabled() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        AuthConfig auth = AppTestData.testAuthConfig(true, "", Map.of(), Map.of("token-alice", "alice"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            null,
            null,
            null,
            List.of("api-token"),
            auth
        )) {
            try (Response response = harness.client().post("/proc/auth", request -> request.setBody("""
                {
                  "action": "login",
                  "protocol-version": 35,
                  "session": "client-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), status(response));
            }
        }
    }

    @Test
    void videoPlaybackUsesAuthenticatedOwnerForPlaybackBridge() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("owned.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("owned-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        AuthConfig auth = AppTestData.testAuthConfig(true, "", Map.of(), Map.of("token-alice", "alice"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache(),
            List.of("api-token"),
            auth
        )) {
            String masterUrl;
            try (Response playResponse = harness.client().post("/proc/video", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-alice");
                request.setBody("""
                    {
                      "action": "play",
                      "path": "/library/owned.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                masterUrl = jsonBody(playResponse).get("url").toString();
            }

            assertEquals("alice", playback.lastOpenOwner());
            assertEquals("alice", playback.lastJobOwner());
            assertEquals("alice", playback.lastManifestOwner());

            try (Response masterResponse = harness.client().get(URI.create(masterUrl).getRawPath(), request ->
                request.header(HttpHeaders.Authorization, "Bearer token-alice")
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(masterResponse));
                assertEquals("alice", playback.lastManifestOwner());
            }

            try (Response closeResponse = harness.client().post("/proc/video", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-alice");
                request.setBody("""
                    {
                      "action": "close",
                      "path": "/library/owned.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(closeResponse));
            }
            assertEquals(1, playback.closeRequests().size());
            assertEquals("alice", playback.closeRequests().getFirst().owner());
        }
    }

    @Test
    void authenticatedVideoPlayKeepsSameClientSessionIsolatedPerOwner() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("owned.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("owner-isolated-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        AuthConfig auth = AppTestData.testAuthConfig(true, "", Map.of(), Map.of(
            "token-alice", "alice",
            "token-bob", "bob"
        ));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache(),
            List.of("api-token"),
            auth
        )) {
            String firstPlayRequest = """
                {
                  "action": "play",
                  "path": "/library/owned.mp4",
                  "session": "client-session"
                }
                """;
            try (Response firstPlayResponse = harness.client().post("/proc/video", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-alice");
                request.setBody(firstPlayRequest);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(firstPlayResponse));
            }
            assertTrue(playback.closeRequests().isEmpty());

            try (Response secondPlayResponse = harness.client().post("/proc/video", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-bob");
                request.setBody(firstPlayRequest);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(secondPlayResponse));
            }
            assertTrue(playback.closeRequests().isEmpty());

            try (Response aliceCloseResponse = harness.client().post("/proc/video", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-alice");
                request.setBody("""
                    {
                      "action": "close",
                      "path": "/library/owned.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(aliceCloseResponse));
            }
            assertEquals(1, playback.closeRequests().size());
            assertEquals("playback-session-1", playback.closeRequests().getFirst().sessionId());
            assertEquals("alice", playback.closeRequests().getFirst().owner());

            try (Response bobCloseResponse = harness.client().post("/proc/video", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-bob");
                request.setBody("""
                    {
                      "action": "close",
                      "path": "/library/owned.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(bobCloseResponse));
            }
            assertEquals(2, playback.closeRequests().size());
            assertEquals("playback-session-2", playback.closeRequests().get(1).sessionId());
            assertEquals("bob", playback.closeRequests().get(1).owner());
        }
    }

    @Test
    void authenticatedHeaderOnlyVideoPlayCanCloseWithoutQloudSession() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("owned.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("header-only-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        AuthConfig auth = AppTestData.testAuthConfig(true, "", Map.of(), Map.of("token-alice", "alice"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache(),
            List.of("api-token"),
            auth
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-alice");
                request.setBody("""
                    {
                      "action": "play",
                      "path": "/library/owned.mp4"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
            }
            assertTrue(playback.closeRequests().isEmpty());

            try (Response closeResponse = harness.client().post("/proc/video", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-alice");
                request.setBody("""
                    {
                      "action": "close",
                      "path": "/library/owned.mp4"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(closeResponse));
            }
            assertEquals(1, playback.closeRequests().size());
            assertEquals("playback-session-1", playback.closeRequests().getFirst().sessionId());
            assertEquals("alice", playback.closeRequests().getFirst().owner());
        }
    }

    @Test
    void compatibilityEndpointsHandleInvalidRequestsAndBlankSearches() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response response = harness.client().post("/proc/list", request -> request.setBody("not-json"))) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
            }

            try (Response response = harness.client().post("/proc/list", request -> request.setBody("null"))) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
            }

            try (Response response = harness.client().post("/proc/video", request ->
                request.setBody("{\"action\":\"play\"}")
            )) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), status(response));
            }

            try (Response response = harness.client().post("/proc/video", request ->
                request.setBody("{\"action\":\"clear-progress\"}")
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("clear-progress", jsonBody(response).get("action"));
            }

            try (Response response = harness.client().post("/proc/video", request ->
                request.setBody("{\"action\":\"unexpected\"}")
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("unexpected", jsonBody(response).get("action"));
            }

            try (Response response = harness.client().post("/proc/search", request ->
                request.setBody("{\"action\":\"search\",\"pattern\":\"   \",\"limit\":3}")
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                Map<String, Object> body = jsonBody(response);
                assertTrue(items(body).isEmpty());
                assertEquals("done", body.get("result"));
            }
        }
    }

    @Test
    void segmentRequestsFailWhenTranscodeOutputDirectoryIsUnavailable() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("missing-output-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");

        FakeTranscodeService transcode = new FakeTranscodeService(segmentDir);

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            new FakePlaybackSessionService(),
            transcode,
            new FakeSegmentCache()
        )) {
            String masterUrl;
            try (Response playResponse = harness.client().post("/proc/video", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "compat.example.test");
                request.setBody("""
                    {
                      "action": "play",
                      "path": "/library/movie.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                String url = jsonBody(playResponse).get("url").toString();
                assertTrue(url.startsWith("https://compat.example.test/"), url);
                masterUrl = url;
            }

            transcode.setOutputDirAvailable(false);

            try (Response masterResponse = harness.client().get(URI.create(masterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(masterResponse));
                String variantPath = firstUriPath(bodyAsText(masterResponse), "video.m3u8");
                try (Response variantResponse = harness.client().get(variantPath)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(variantResponse));
                    String segmentPath = firstUriPath(bodyAsText(variantResponse), "chunk_0_001.m4s");
                    try (Response segmentResponse = harness.client().get(segmentPath)) {
                        assertEquals(HttpStatusCode.Companion.getNotFound(), status(segmentResponse));
                    }
                }
            }
        }
    }

    @Test
    void videoPlayClosesOpenedSessionWhenPlaybackStopsBeforeManifestReady() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("stopped-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        playback.setSessionPhase(PlaybackLifecyclePhase.STOPPED, null);

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "session": "client-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), status(playResponse));
            }

            assertEquals(1, playback.closeRequests().size());
            assertEquals("playback-session-1", playback.closeRequests().getFirst().sessionId());
        }
    }

    @Test
    void videoPlayClosesOpenedSessionWhenBackingJobNeverMaterializes() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("missing-job-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        playback.setSessionJobId(null);

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            Request playRequest = new Request.Builder()
                .url("http://127.0.0.1:" + harness.app().port() + "/proc/video")
                .header(HttpHeaders.AcceptEncoding, "identity")
                .post(RequestBody.create(
                    """
                        {
                          "action": "play",
                          "path": "/library/movie.mp4",
                          "session": "client-session"
                        }
                        """,
                    MediaType.get("application/json")
                ))
                .build();
            OkHttpClient slowClient = new OkHttpClient.Builder()
                .callTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

            try (Response playResponse = slowClient.newCall(playRequest).execute()) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), status(playResponse));
            }

            assertEquals(1, playback.closeRequests().size());
            assertEquals("playback-session-1", playback.closeRequests().getFirst().sessionId());
        }
    }

    @Test
    void videoPlayKeepsSeparateBackingJobAndManifestStartupBudgets() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("staged-startup-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        playback.setSessionJobIdAvailableAfterLookup(2);
        playback.setHlsManifestAvailableAfterLookup(2);

        try (
            AutoCloseable ignored = invokePrivateStatic(
                QloudCompatibilityRoutes.class,
                "useManifestPollingForTesting",
                new Class<?>[] {int.class, long.class},
                2,
                0L
            );
            AppTestSupport.ApplicationHarness harness = routeHarness(
                mediaRoot,
                playback,
                new FakeTranscodeService(segmentDir),
                new FakeSegmentCache()
            )
        ) {
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "session": "client-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                assertTrue(jsonBody(playResponse).get("url").toString().contains(".%28_n_y_x_%29.m3u8"));
            }

            assertTrue(playback.jobLookupCount() >= 2);
            assertEquals(2, playback.manifestLookupCount());
            assertTrue(playback.closeRequests().isEmpty());
        }
    }

    @Test
    void videoPlayClosesOpenedSessionWhenManifestNeverBecomesReady() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("manifest-timeout-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        playback.setHlsManifest(null);

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            Request playRequest = new Request.Builder()
                .url("http://127.0.0.1:" + harness.app().port() + "/proc/video")
                .header(HttpHeaders.AcceptEncoding, "identity")
                .post(RequestBody.create(
                    """
                        {
                          "action": "play",
                          "path": "/library/movie.mp4",
                          "session": "client-session"
                        }
                        """,
                    MediaType.get("application/json")
                ))
                .build();
            OkHttpClient slowClient = new OkHttpClient.Builder()
                .callTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

            try (Response playResponse = slowClient.newCall(playRequest).execute()) {
                assertEquals(HttpStatusCode.Companion.getInternalServerError(), status(playResponse));
            }

            assertEquals(1, playback.closeRequests().size());
            assertEquals("playback-session-1", playback.closeRequests().getFirst().sessionId());
        }
    }

    @Test
    void authenticatedQloudSessionBridgeMintsServerOwnedSessionAndSupportsSessionBasedBrowsePlayAndClose() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("owned.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("owned-session-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        AuthConfig auth = AppTestData.testAuthConfig(true, "", Map.of(), Map.of("token-alice", "alice"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache(),
            List.of("api-token"),
            auth
        )) {
            String serverSession;
            try (Response authResponse = harness.client().post("/proc/auth", request -> {
                request.header(HttpHeaders.Authorization, "Bearer token-alice");
                request.setBody("""
                    {
                      "action": "login",
                      "protocol-version": 35,
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(authResponse));
                serverSession = jsonBody(authResponse).get("session").toString();
                assertNotEquals("client-session", serverSession);
            }

            try (Response rejectedReplay = harness.client().post("/proc/list", request -> request.setBody("""
                {
                  "action": "browse",
                  "path": "/",
                  "media": "video",
                  "session": "client-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), status(rejectedReplay));
            }

            try (Response browseResponse = harness.client().post("/proc/list", request -> request.setBody("""
                {
                  "action": "browse",
                  "path": "/",
                  "media": "video",
                  "session": "%s"
                }
                """.formatted(serverSession)))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(browseResponse));
                List<Map<String, Object>> items = items(jsonBody(browseResponse));
                assertEquals("/library/owned.mp4", items.getFirst().get("path"));
            }

            String masterUrl;
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/owned.mp4",
                  "session": "%s"
                }
                """.formatted(serverSession)))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                masterUrl = jsonBody(playResponse).get("url").toString();
            }

            assertEquals("alice", playback.lastOpenOwner());
            assertEquals("alice", playback.lastJobOwner());
            assertEquals("alice", playback.lastManifestOwner());

            try (Response masterResponse = harness.client().get(URI.create(masterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(masterResponse));
            }

            try (Response closeResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "close",
                  "path": "/library/owned.mp4",
                  "session": "%s"
                }
                """.formatted(serverSession)))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(closeResponse));
            }
            assertEquals(1, playback.closeRequests().size());
            assertEquals("alice", playback.closeRequests().getFirst().owner());
        }
    }

    @Test
    void compatibilitySessionBridgeExpiresRememberedSessions() {
        QloudCompatibilityRoutes routes = newRoutesForSessionBridgeTest(
            AppTestData.testAuthConfig(true, "", Map.of(), Map.of("token-alice", "alice"))
        );

        Object sessionPolicy = readPrivateField(routes, "compatibilitySessionPolicy");
        @SuppressWarnings("unchecked")
        Map<String, Object> sessions = readPrivateField(sessionPolicy, "sessions");
        sessions.put("stale-session", newCompatibilitySession("stale-session", "alice", Instant.now().minusSeconds(172_800)));
        writePrivateField(sessionPolicy, "lastCleanupAt", Instant.EPOCH);

        Object freshSession = invokePrivate(routes, "issueCompatibilitySession", new Class<?>[] {String.class}, "alice");
        String freshSessionId = invokeRecordAccessor(freshSession, "rpcSession").toString();

        assertFalse(sessions.containsKey("stale-session"));
        assertEquals("alice", invokePrivate(routes, "compatibilitySessionOwner", new Class<?>[] {String.class}, freshSessionId));

        sessions.put(freshSessionId, newCompatibilitySession(freshSessionId, "alice", Instant.now().minusSeconds(172_800)));

        assertNull(invokePrivate(routes, "compatibilitySessionOwner", new Class<?>[] {String.class}, freshSessionId));
        assertFalse(sessions.containsKey(freshSessionId));
    }

    @Test
    void authenticatedQloudRequestsWithoutAuthorizationOrSessionBridgeAreUnauthorized() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        AuthConfig auth = AppTestData.testAuthConfig(true, "", Map.of(), Map.of("token-alice", "alice"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            null,
            null,
            null,
            List.of("api-token"),
            auth
        )) {
            try (Response response = harness.client().post("/proc/list", request -> request.setBody("""
                {
                  "action": "browse",
                  "path": "/",
                  "media": "video"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), status(response));
            }
        }
    }

    @Test
    void repeatedVideoPlayClosesReplacedBridgeForSameClientPath() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("replacement-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            String playRequest = """
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "session": "client-session"
                }
                """;

            try (Response firstPlayResponse = harness.client().post("/proc/video", request -> request.setBody(playRequest))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(firstPlayResponse));
            }
            assertTrue(playback.closeRequests().isEmpty());

            try (Response secondPlayResponse = harness.client().post("/proc/video", request -> request.setBody(playRequest))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(secondPlayResponse));
            }
            assertEquals(1, playback.closeRequests().size());
            assertEquals("playback-session-1", playback.closeRequests().getFirst().sessionId());

            try (Response closeResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "close",
                  "path": "/library/movie.mp4",
                  "session": "client-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(closeResponse));
            }
            assertEquals(2, playback.closeRequests().size());
            assertEquals("playback-session-2", playback.closeRequests().get(1).sessionId());
        }
    }

    @Test
    void anonymousVideoPlayWithoutSessionKeepsIndependentBridgesForSamePath() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("anonymous-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            String playRequest = """
                {
                  "action": "play",
                  "path": "/library/movie.mp4"
                }
                """;

            String firstMasterUrl;
            try (Response firstPlayResponse = harness.client().post("/proc/video", request -> request.setBody(playRequest))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(firstPlayResponse));
                firstMasterUrl = jsonBody(firstPlayResponse).get("url").toString();
            }
            assertTrue(playback.closeRequests().isEmpty());

            String secondMasterUrl;
            try (Response secondPlayResponse = harness.client().post("/proc/video", request -> request.setBody(playRequest))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(secondPlayResponse));
                secondMasterUrl = jsonBody(secondPlayResponse).get("url").toString();
            }
            assertTrue(playback.closeRequests().isEmpty());

            try (Response firstMasterResponse = harness.client().get(URI.create(firstMasterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(firstMasterResponse));
            }
            try (Response secondMasterResponse = harness.client().get(URI.create(secondMasterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(secondMasterResponse));
            }
        }
    }

    @Test
    void anonymousVideoCloseWithoutSessionClosesMostRecentBridgeForSamePath() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("anonymous-close-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            String playRequest = """
                {
                  "action": "play",
                  "path": "/library/movie.mp4"
                }
                """;

            String firstMasterUrl;
            try (Response firstPlayResponse = harness.client().post("/proc/video", request -> request.setBody(playRequest))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(firstPlayResponse));
                firstMasterUrl = jsonBody(firstPlayResponse).get("url").toString();
            }

            String secondMasterUrl;
            try (Response secondPlayResponse = harness.client().post("/proc/video", request -> request.setBody(playRequest))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(secondPlayResponse));
                secondMasterUrl = jsonBody(secondPlayResponse).get("url").toString();
            }

            try (Response closeResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "close",
                  "path": "/library/movie.mp4"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(closeResponse));
            }

            assertEquals(1, playback.closeRequests().size());
            assertEquals("playback-session-2", playback.closeRequests().getFirst().sessionId());
            assertNull(playback.closeRequests().getFirst().owner());

            try (Response firstMasterResponse = harness.client().get(URI.create(firstMasterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(firstMasterResponse));
            }
            try (Response secondMasterResponse = harness.client().get(URI.create(secondMasterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), status(secondMasterResponse));
            }

            try (Response secondCloseResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "close",
                  "path": "/library/movie.mp4"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(secondCloseResponse));
            }

            assertEquals(2, playback.closeRequests().size());
            assertEquals("playback-session-1", playback.closeRequests().get(1).sessionId());
        }
    }

    @Test
    void scheduledQloudHlsBridgeCleanupExpiresIdleBridgesWithoutAnotherRequest() {
        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        TestScheduledExecutorService cleanupScheduler = new TestScheduledExecutorService();
        QloudCompatibilityRoutes routes = newRoutesForSessionBridgeTest(
            AppTestData.testAuthConfig(),
            playback,
            cleanupScheduler
        );

        String clientLookupKey = invokePrivateStatic(
            QloudCompatibilityRoutes.class,
            "clientSessionKey",
            new Class<?>[] {String.class, String.class, String.class},
            null,
            "client-session",
            "/library/movie.mp4"
        );
        Object bridge = newHlsBridgeSession(
            "bridge-token",
            "client-session",
            null,
            "playback-session-1",
            "job-1",
            "/library/movie.mp4",
            "library/movie.mp4",
            "movie.m3u8",
            Instant.now().minusSeconds(7_200),
            clientLookupKey
        );
        invokePrivate(routes, "registerHlsBridge", new Class<?>[] {bridge.getClass()}, bridge);
        Object hlsBridgePolicy = readPrivateField(routes, "hlsBridgePolicy");
        writePrivateField(hlsBridgePolicy, "lastCleanupAt", Instant.EPOCH);
        cleanupScheduler.runScheduledTask();

        @SuppressWarnings("unchecked")
        Map<String, Object> hlsSessions = readPrivateField(hlsBridgePolicy, "sessions");
        @SuppressWarnings("unchecked")
        Map<String, Object> hlsSessionsByClientKey = readPrivateField(hlsBridgePolicy, "sessionsByClientKey");
        assertTrue(hlsSessions.isEmpty());
        assertTrue(hlsSessionsByClientKey.isEmpty());
        assertEquals(1, playback.closeRequests().size());
        assertEquals("playback-session-1", playback.closeRequests().getFirst().sessionId());
    }

    @Test
    void videoPlayClosesOpenedSessionWhenManifestStartupFails() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("failed-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        playback.setSessionPhase(PlaybackLifecyclePhase.FAILED, "transcode failed");

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "session": "client-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getInternalServerError(), status(playResponse));
            }

            assertEquals(1, playback.closeRequests().size());
            assertEquals("playback-session-1", playback.closeRequests().getFirst().sessionId());
        }
    }

    @Test
    void videoPlayMapsDeliveryFailureOutcomeToQloudTranscodeError() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("delivery-failed-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        StubPlaybackDeliveryService delivery = new StubPlaybackDeliveryService(new PlaybackDeliveryFailed(
            FakePlaybackSessionService.session("playback-session-1", PlaybackLifecyclePhase.FAILED, "encoder failed"),
            "ENCODER_FAILED",
            "encoder failed"
        ));

        try (AppTestSupport.ApplicationHarness harness = routeHarnessWithDelivery(
            mediaRoot,
            playback,
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache(),
            delivery
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "session": "client-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getInternalServerError(), status(playResponse));
            }

            assertNotNull(delivery.lastOpenRequest());
            assertNull(delivery.lastOpenRequest().owner());
            assertTrue(delivery.closeRequests().isEmpty());
        }
    }

    @Test
    void legacyTsHlsCompatibilitySelectsCorePackagingStrategy() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("ts-segments"));
        Files.writeString(segmentDir.resolve("chunk_0_001.ts"), "ts segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        FakeTranscodeService transcode = new FakeTranscodeService(segmentDir, List.of("""
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:6.0,
            segments/chunk_0_001.ts
            #EXT-X-ENDLIST
            """));
        String previous = System.setProperty("nyx.qloud.legacy.ts.hls", "true");
        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            transcode,
            new FakeSegmentCache()
        )) {
            String masterUrl;
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "session": "client-session"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                Map<String, Object> body = jsonBody(playResponse);
                masterUrl = body.get("url").toString();
                assertEquals("httplive", body.get("protocol"));
                assertEquals(0, ((Number) body.get("precache")).intValue());
                assertEquals(1, ((Number) body.get("audio-boost")).intValue());
            }

            assertNotNull(playback.lastRequest());
            assertEquals(StreamRepresentation.HLS_MPEG_TS, playback.lastRequest().output().preferredRepresentation());

            try (Response masterResponse = harness.client().get(URI.create(masterUrl).getRawPath())) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(masterResponse));
                String variantPath = firstUriPath(bodyAsText(masterResponse), "video.m3u8");
                try (Response variantResponse = harness.client().get(variantPath)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(variantResponse));
                    String variant = bodyAsText(variantResponse);
                    assertTrue(variant.contains("chunk_0_001.ts"), variant);
                    String segmentPath = firstUriPath(variant, "chunk_0_001.ts");

                    try (Response segmentResponse = harness.client().get(segmentPath)) {
                        assertEquals(HttpStatusCode.Companion.getOK(), status(segmentResponse));
                        assertEquals("ts segment", bodyAsText(segmentResponse));
                    }
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty("nyx.qloud.legacy.ts.hls");
            } else {
                System.setProperty("nyx.qloud.legacy.ts.hls", previous);
            }
        }
    }

    @Test
    void mediaPlaylistWaitsForFirstSegmentBeforeResponding() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        FakePlaybackSessionService playback = new FakePlaybackSessionService();
        FakeTranscodeService transcode = new FakeTranscodeService(segmentDir, List.of(
            """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="segments/init.mp4"
                """,
            """
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="segments/init.mp4"
                #EXTINF:6.0,
                segments/chunk_0_001.m4s
                """
        ));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            playback,
            transcode,
            new FakeSegmentCache()
        )) {
            String masterUrl;
            try (Response playResponse = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "play",
                  "path": "/library/movie.mp4",
                  "session": "client-session",
                  "protocol": "httplive",
                  "protocol-scheme": "http"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                masterUrl = jsonBody(playResponse).get("url").toString();
            }

            try (Response masterResponse = harness.client().get(URI.create(masterUrl).getRawPath())) {
                String variantPath = firstUriPath(bodyAsText(masterResponse), "video.m3u8");
                try (Response variantResponse = harness.client().get(variantPath)) {
                    assertEquals(HttpStatusCode.Companion.getOK(), status(variantResponse));
                    assertTrue(bodyAsText(variantResponse).contains("chunk_0_001.m4s"));
                    assertTrue(transcode.playlistCalls() >= 2);
                }
            }
        }
    }

    @Test
    void videoPlayHonorsForwardedOriginHeadersForReturnedQloudUrl() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("forwarded-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            new FakePlaybackSessionService(),
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "compat.example.test");
                request.header("X-Forwarded-Port", "8443");
                request.setBody("""
                    {
                      "action": "play",
                      "path": "/library/movie.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                String url = jsonBody(playResponse).get("url").toString();
                assertTrue(url.startsWith("https://compat.example.test:8443/"), url);
            }
        }
    }

    @Test
    void videoPlayFallsBackToHostPortWhenForwardedHostOmitsOne() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("forwarded-host-port-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            new FakePlaybackSessionService(),
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "compat.example.test");
                request.header("Host", "compat.example.test:8443");
                request.setBody("""
                    {
                      "action": "play",
                      "path": "/library/movie.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                String url = jsonBody(playResponse).get("url").toString();
                assertTrue(url.startsWith("https://compat.example.test:8443/"), url);
            }
        }
    }

    @Test
    void videoPlayPreservesHostPortWhenForwardedHostRewritesHostnameOnly() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Files.writeString(mediaRoot.resolve("movie.mp4"), "not a real movie");
        Path segmentDir = Files.createDirectories(tempDir.resolve("rewritten-forwarded-host-port-segments"));
        Files.writeString(segmentDir.resolve("init.mp4"), "init");
        Files.writeString(segmentDir.resolve("chunk_0_001.m4s"), "segment");

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            new FakePlaybackSessionService(),
            new FakeTranscodeService(segmentDir),
            new FakeSegmentCache()
        )) {
            try (Response playResponse = harness.client().post("/proc/video", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "compat.example.test");
                request.header("Host", "internal-listener:8443");
                request.setBody("""
                    {
                      "action": "play",
                      "path": "/library/movie.mp4",
                      "session": "client-session"
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(playResponse));
                String url = jsonBody(playResponse).get("url").toString();
                assertTrue(url.startsWith("https://compat.example.test:8443/"), url);
            }
        }
    }

    @Test
    void authEndpointHonorsForwardedOriginHeadersForAdvertisedServerAddress() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response authResponse = harness.client().post("/proc/auth", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "compat.example.test");
                request.header("X-Forwarded-Port", "8443");
                request.setBody("""
                    {
                      "action": "login",
                      "protocol-version": 35
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(authResponse));
                Map<String, Object> body = jsonBody(authResponse);
                assertEquals("compat.example.test", body.get("server-external-address"));
                assertEquals(8443, ((Number) body.get("server-external-port")).intValue());
                assertEquals("compat.example.test", body.get("server-local-address"));
                assertEquals(8443, ((Number) body.get("server-local-port")).intValue());
            }
        }
    }

    @Test
    void authEndpointFallsBackToHostPortWhenForwardedHostOmitsOne() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response authResponse = harness.client().post("/proc/auth", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "compat.example.test");
                request.header("Host", "compat.example.test:8443");
                request.setBody("""
                    {
                      "action": "login",
                      "protocol-version": 35
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(authResponse));
                Map<String, Object> body = jsonBody(authResponse);
                assertEquals("compat.example.test", body.get("server-external-address"));
                assertEquals(8443, ((Number) body.get("server-external-port")).intValue());
                assertEquals("compat.example.test", body.get("server-local-address"));
                assertEquals(8443, ((Number) body.get("server-local-port")).intValue());
            }
        }
    }

    @Test
    void authEndpointPreservesHostPortWhenForwardedHostRewritesHostnameOnly() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(mediaRoot, null, null, null)) {
            try (Response authResponse = harness.client().post("/proc/auth", request -> {
                request.header("X-Forwarded-Proto", "https");
                request.header("X-Forwarded-Host", "compat.example.test");
                request.header("Host", "internal-listener:8443");
                request.setBody("""
                    {
                      "action": "login",
                      "protocol-version": 35
                    }
                    """);
            })) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(authResponse));
                Map<String, Object> body = jsonBody(authResponse);
                assertEquals("compat.example.test", body.get("server-external-address"));
                assertEquals(8443, ((Number) body.get("server-external-port")).intValue());
                assertEquals("compat.example.test", body.get("server-local-address"));
                assertEquals(8443, ((Number) body.get("server-local-port")).intValue());
            }
        }
    }

    @Test
    void compatibilityMetadataSearchImageAndSeekEndpointsUseInjectedMediaServices() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path movieOne = Files.write(mediaRoot.resolve("movie-one.mp4"), new byte[2_048]);
        Path movieTwo = Files.write(mediaRoot.resolve("movie-two.mp4"), new byte[2_048]);
        Path song = Files.write(mediaRoot.resolve("song.mp3"), new byte[1_024]);
        Path albumDir = Files.createDirectories(mediaRoot.resolve("Album"));
        Path track = Files.write(albumDir.resolve("track.mp3"), new byte[1_024]);
        Path poster = writeImageFile(mediaRoot.resolve("poster.jpg"), 320, 180);

        MediaProber mediaProber = mediaProber(Map.of(
            movieOne, new ProbeResult(
                movieOne.toString(),
                "mov,mp4,m4a,3gp,3g2,mj2",
                480.0,
                2_048L,
                new ProbeStreams(
                    List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                    List.of(new AudioStream(1, "aac", 2, 192, 48_000, "eng", "Stereo")),
                    List.of()
                ),
                Map.of("title", "Movie One", "genre", "Sci-Fi")
            ),
            song, new ProbeResult(
                song.toString(),
                "mp3",
                215.0,
                1_024L,
                new ProbeStreams(
                    List.of(),
                    List.of(new AudioStream(0, "mp3", 2, 192, 44_100, "spa", "Track")),
                    List.of()
                ),
                Map.of("artist", "Synth Unit")
            ),
            track, new ProbeResult(
                track.toString(),
                "mp3",
                180.0,
                1_024L,
                new ProbeStreams(
                    List.of(),
                    List.of(new AudioStream(0, "mp3", 2, 192, 44_100, "eng", "Album Track")),
                    List.of()
                ),
                Map.of("artist", "Album Artist")
            )
        ));

        try (AppTestSupport.ApplicationHarness harness = routeHarness(
            mediaRoot,
            null,
            null,
            null,
            List.of(),
            AppTestData.testAuthConfig(),
            new ConcurrentHashMap<>(),
            mediaProber,
            new ThumbnailService(new LocalStorageBackend(tempDir.resolve("thumb-cache"))),
            createVideoPreviewService()
        )) {
            try (Response response = harness.client().post("/proc/info", request -> request.setBody("""
                {
                  "action": "info-items",
                  "path-list": ["/library", "/library/movie-one.mp4", "/library/poster.jpg", "/library/song.mp3"]
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                List<Map<String, Object>> items = items(jsonBody(response));
                assertEquals("video-folder", items.get(0).get("type"));
                assertEquals("video", items.get(1).get("type"));
                assertEquals("Movie One", ((Map<?, ?>) items.get(1).get("metadata")).get("TITLE"));
                assertEquals("1920", ((Map<?, ?>) items.get(1).get("video")).get("width").toString());
                assertEquals("eng", ((Map<?, ?>) items.get(1).get("video")).get("audio-langs"));
                assertNotNull(items.get(2).get("image"));
                assertEquals("music", items.get(3).get("type"));
                assertEquals("Synth Unit", ((Map<?, ?>) items.get(3).get("metadata")).get("ARTIST"));
            }

            try (Response response = harness.client().post("/proc/info", request -> request.setBody("""
                {
                  "action": "info",
                  "path": "/library/movie-one.mp4",
                  "seek": 12.5
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                Map<String, Object> body = jsonBody(response);
                Map<String, Object> video = castMap(body.get("video"));
                assertEquals("12.5", video.get("time").toString());
            }

            try (Response response = harness.client().post("/proc/image", request -> request.setBody("""
                {
                  "action": "prepare-items",
                  "path-list": ["/library/poster.jpg", "/library/missing.jpg"]
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                List<Map<String, Object>> items = items(jsonBody(response));
                assertNotNull(items.get(0).get("image"));
                assertEquals("err.media", items.get(1).get("error"));
            }

            try (Response response = harness.client().post("/proc/search", request -> request.setBody("""
                {
                  "action": "search",
                  "pattern": "*movie*",
                  "limit": 1,
                  "media": "video"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                Map<String, Object> body = jsonBody(response);
                assertEquals("more", body.get("result"));
                assertEquals(1, items(body).size());
                assertTrue(items(body).getFirst().get("path").toString().contains("movie-"));
            }

            try (Response response = harness.client().post("/proc/list", request -> request.setBody("""
                {
                  "action": "browse-recent",
                  "media": "music",
                  "limit": 5
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("music", items(jsonBody(response)).getFirst().get("type"));
            }

            try (Response response = harness.client().post("/proc/list", request -> request.setBody("""
                {
                  "action": "browse-siblings",
                  "path": "/library/Album/track.mp3",
                  "sort": "size"
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                assertEquals("/library/Album", jsonBody(response).get("path"));
            }

            try (Response response = harness.client().post("/proc/video", request -> request.setBody("""
                {
                  "action": "seek",
                  "path": "/library/movie-one.mp4",
                  "seek": 12.5
                }
                """))) {
                assertEquals(HttpStatusCode.Companion.getOK(), status(response));
                Map<String, Object> body = jsonBody(response);
                assertEquals("12.5", body.get("time").toString());
                assertNotNull(body.get("image"));
            }
        }
    }

    private AppTestSupport.ApplicationHarness routeHarness(
        Path mediaRoot,
        FakePlaybackSessionService playback,
        FakeTranscodeService transcode,
        FakeSegmentCache segmentCache
    ) throws Exception {
        return routeHarness(
            mediaRoot,
            playback,
            transcode,
            segmentCache,
            List.of(),
            AppTestData.testAuthConfig()
        );
    }

    private AppTestSupport.ApplicationHarness routeHarnessWithDelivery(
        Path mediaRoot,
        FakePlaybackSessionService playback,
        FakeTranscodeService transcode,
        FakeSegmentCache segmentCache,
        PlaybackDeliveryService playbackDeliveryService
    ) throws Exception {
        AuthConfig authConfig = AppTestData.testAuthConfig();
        return routeHarness(
            mediaRoot,
            playback,
            transcode,
            segmentCache,
            List.of(),
            authConfig,
            new ConcurrentHashMap<>(authConfig.getUsers()),
            null,
            null,
            null,
            AppTestData.testFfmpegConfig(),
            playbackDeliveryService
        );
    }

    private AppTestSupport.ApplicationHarness routeHarness(
        Path mediaRoot,
        FakePlaybackSessionService playback,
        FakeTranscodeService transcode,
        FakeSegmentCache segmentCache,
        List<String> authProviders,
        AuthConfig authConfig
    ) throws Exception {
        return routeHarness(
            mediaRoot,
            playback,
            transcode,
            segmentCache,
            authProviders,
            authConfig,
            new ConcurrentHashMap<>(authConfig.getUsers())
        );
    }

    private AppTestSupport.ApplicationHarness routeHarness(
        Path mediaRoot,
        FakePlaybackSessionService playback,
        FakeTranscodeService transcode,
        FakeSegmentCache segmentCache,
        List<String> authProviders,
        AuthConfig authConfig,
        ConcurrentHashMap<String, String> runtimeUsers
    ) throws Exception {
        return routeHarness(
            mediaRoot,
            playback,
            transcode,
            segmentCache,
            authProviders,
            authConfig,
            runtimeUsers,
            null,
            null,
            null
        );
    }

    private AppTestSupport.ApplicationHarness routeHarness(
        Path mediaRoot,
        FakePlaybackSessionService playback,
        FakeTranscodeService transcode,
        FakeSegmentCache segmentCache,
        List<String> authProviders,
        AuthConfig authConfig,
        ConcurrentHashMap<String, String> runtimeUsers,
        MediaProber mediaProber,
        ThumbnailService thumbnailService,
        VideoPreviewService videoPreviewService
    ) throws Exception {
        return routeHarness(
            mediaRoot,
            playback,
            transcode,
            segmentCache,
            authProviders,
            authConfig,
            runtimeUsers,
            mediaProber,
            thumbnailService,
            videoPreviewService,
            AppTestData.testFfmpegConfig()
        );
    }

    private AppTestSupport.ApplicationHarness routeHarness(
        Path mediaRoot,
        FakePlaybackSessionService playback,
        FakeTranscodeService transcode,
        FakeSegmentCache segmentCache,
        List<String> authProviders,
        AuthConfig authConfig,
        ConcurrentHashMap<String, String> runtimeUsers,
        MediaProber mediaProber,
        ThumbnailService thumbnailService,
        VideoPreviewService videoPreviewService,
        FfmpegConfig ffmpegConfig
    ) throws Exception {
        return routeHarness(
            mediaRoot,
            playback,
            transcode,
            segmentCache,
            authProviders,
            authConfig,
            runtimeUsers,
            mediaProber,
            thumbnailService,
            videoPreviewService,
            ffmpegConfig,
            null
        );
    }

    private AppTestSupport.ApplicationHarness routeHarness(
        Path mediaRoot,
        FakePlaybackSessionService playback,
        FakeTranscodeService transcode,
        FakeSegmentCache segmentCache,
        List<String> authProviders,
        AuthConfig authConfig,
        ConcurrentHashMap<String, String> runtimeUsers,
        MediaProber mediaProber,
        ThumbnailService thumbnailService,
        VideoPreviewService videoPreviewService,
        FfmpegConfig ffmpegConfig,
        PlaybackDeliveryService playbackDeliveryServiceOverride
    ) throws Exception {
        AppTestSupport.ApplicationHarness harness = new AppTestSupport.ApplicationHarness();
        VirtualPathResolver resolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaRoot, "local", "library")));
        PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
        BrowseService browseService = new BrowseService(resolver, pathSecurity, List.of(150, 300), null);
        TestScheduledExecutorService cleanupScheduler = new TestScheduledExecutorService();
        ServerConfig serverConfig = AppTestData.testServerConfig(
            "0.0.0.0",
            8080,
            List.of(),
            List.of(new MediaRootConfig(mediaRoot, "local", "library")),
            ffmpegConfig,
            AppTestData.testTranscodeConfig(),
            new DatabaseConfig(tempDir.resolve("db")),
            new com.nyx.config.ThumbnailConfig(),
            new com.nyx.config.AudioConfig(),
            authConfig,
            new com.nyx.config.RateLimitConfig(),
            new com.nyx.config.CsrfConfig(),
            new com.nyx.config.TlsConfig(),
            new com.nyx.config.WebhookConfig(),
            new com.nyx.config.QuotaConfig(),
            new com.nyx.config.BackupConfig(),
            new com.nyx.config.StorageConfig(),
            new CompatibilityConfig(new QloudCompatibilityConfig(true, "0.0.0.0", 8080))
        );
        harness.configurePlugins(serverConfig, authConfig, null, runtimeUsers);
        FakePlaybackSessionService playbackService = playback == null ? new FakePlaybackSessionService() : playback;
        PlaybackDeliveryService playbackDeliveryService = playbackDeliveryServiceOverride == null
            ? new LocalPlaybackDeliveryService(playbackService, cleanupScheduler)
            : playbackDeliveryServiceOverride;
        harness.routing(route -> QloudCompatibilityRoutes.qloudRoutes(
            route.raw(),
            browseService,
            playbackService,
            playbackDeliveryService,
            pathSecurity,
            resolver,
            serverConfig,
            runtimeUsers,
            serverConfig.getCompatibility().getQloud(),
            authProviders,
            null,
            transcode == null ? new FakeTranscodeService(tempDir) : transcode,
            segmentCache == null ? new FakeSegmentCache() : segmentCache,
            cleanupScheduler,
            mediaProber,
            thumbnailService,
            videoPreviewService
        ));
        return harness;
    }

    private Path writeImageFile(Path path, int width, int height) throws Exception {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        String format = path.getFileName().toString().substring(path.getFileName().toString().lastIndexOf('.') + 1);
        ImageIO.write(image, format, path.toFile());
        return path;
    }

    private static MediaProber mediaProber(Map<Path, ProbeResult> probes) {
        Map<Path, ProbeResult> normalized = probes.entrySet().stream()
            .collect(java.util.stream.Collectors.toMap(entry -> entry.getKey().toAbsolutePath().normalize(), Map.Entry::getValue));
        return new MediaProber() {
            @Override
            public ProbeResult probe(Path path) {
                return normalized.get(path.toAbsolutePath().normalize());
            }

            @Override
            public ProbeResult probeCached(Path path) {
                return probe(path);
            }

            @Override
            public void clearCache() {
            }
        };
    }

    private VideoPreviewService createVideoPreviewService() {
        VideoPreviewGenerator generator = new VideoPreviewGenerator() {
            @Override
            public VideoPreviewPlan plan(Path sourcePath, VideoPreviewRequest request) {
                return new VideoPreviewPlan(
                    1920,
                    1080,
                    request.positionMillis() == null ? 10_000L : request.positionMillis(),
                    request.width() == null ? 320 : request.width(),
                    request.height() == null ? 180 : request.height()
                );
            }

            @Override
            public byte[] generate(Path sourcePath, VideoPreviewPlan plan) {
                return new byte[] {1, 2, 3, 4};
            }
        };
        return new VideoPreviewService(generator, new LocalStorageBackend(tempDir.resolve("preview-cache")));
    }

    private static Map<String, Object> jsonBody(Response response) throws Exception {
        return JSON.readValue(bodyAsText(response), new TypeReference<>() { });
    }

    private static void respondUpstreamJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var response = exchange.getResponseBody()) {
            response.write(bytes);
        }
    }

    private static void restoreProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private static String firstUriPath(String playlist, String suffix) {
        return playlist.lines()
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .filter(line -> line.contains(suffix))
            .map(line -> URI.create(line).getRawPath())
            .findFirst()
            .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokePrivate(
        Object owner,
        String methodName,
        Class<?>[] parameterTypes,
        Object... arguments
    ) {
        try {
            Method method = owner.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(owner, arguments);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokePrivateStatic(
        Class<?> owner,
        String methodName,
        Class<?>[] parameterTypes,
        Object... arguments
    ) {
        try {
            Method method = owner.getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return (T) method.invoke(null, arguments);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T readPrivateField(Object owner, String fieldName) {
        try {
            Field field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return (T) field.get(owner);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void writePrivateField(Object owner, String fieldName, Object value) {
        try {
            Field field = owner.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(owner, value);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Object invokeRecordAccessor(Object recordValue, String accessorName) {
        try {
            Method method = recordValue.getClass().getDeclaredMethod(accessorName);
            method.setAccessible(true);
            return method.invoke(recordValue);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void assertQloudSignedToken(String token) {
        byte[] signature = Base64.getDecoder().decode(token);
        assertEquals(128, signature.length);
        byte[] verified = leftPad(
            new BigInteger(1, signature).modPow(QLOUD_PROC_PUBLIC_EXPONENT, QLOUD_PROC_MODULUS).toByteArray(),
            128
        );
        assertEquals(0, verified[0]);
        assertEquals(1, verified[1]);
        int separatorIndex = verified.length - 65;
        for (int index = 2; index < separatorIndex; index++) {
            assertEquals((byte) 0xff, verified[index]);
        }
        assertEquals(0, verified[separatorIndex]);
    }

    private static byte[] leftPad(byte[] value, int length) {
        byte[] normalized = new byte[length];
        int sourceOffset = Math.max(0, value.length - length);
        int copyLength = Math.min(value.length, length);
        System.arraycopy(value, sourceOffset, normalized, length - copyLength, copyLength);
        return normalized;
    }

    private static QloudCompatibilityRoutes newRoutesForSessionBridgeTest(AuthConfig authConfig) {
        return newRoutesForSessionBridgeTest(
            authConfig,
            new FakePlaybackSessionService(),
            new TestScheduledExecutorService()
        );
    }

    private static QloudCompatibilityRoutes newRoutesForSessionBridgeTest(
        AuthConfig authConfig,
        FakePlaybackSessionService playback
    ) {
        return newRoutesForSessionBridgeTest(authConfig, playback, new TestScheduledExecutorService());
    }

    private static QloudCompatibilityRoutes newRoutesForSessionBridgeTest(
        AuthConfig authConfig,
        FakePlaybackSessionService playback,
        ScheduledExecutorService cleanupScheduler
    ) {
        try {
            Constructor<QloudCompatibilityRoutes> constructor = QloudCompatibilityRoutes.class.getDeclaredConstructor(
                BrowseService.class,
                PlaybackSessionService.class,
                PlaybackDeliveryService.class,
                PathSecurity.class,
                VirtualPathResolver.class,
                ServerConfig.class,
                ConcurrentHashMap.class,
                QloudCompatibilityConfig.class,
                List.class,
                com.nyx.media.MediaObjectResolver.class,
                TranscodeApplicationService.class,
                SegmentCacheService.class,
                ScheduledExecutorService.class,
                MediaProber.class,
                ThumbnailService.class,
                VideoPreviewService.class
            );
            constructor.setAccessible(true);
            ServerConfig serverConfig = AppTestData.testServerConfig(
                "127.0.0.1",
                8080,
                List.of(),
                List.of(),
                AppTestData.testFfmpegConfig(),
                AppTestData.testTranscodeConfig(),
                new DatabaseConfig(Path.of("/tmp/qloud-session-test-db")),
                new com.nyx.config.ThumbnailConfig(),
                new com.nyx.config.AudioConfig(),
                authConfig,
                new com.nyx.config.RateLimitConfig(),
                new com.nyx.config.CsrfConfig(),
                new com.nyx.config.TlsConfig(),
                new com.nyx.config.WebhookConfig(),
                new com.nyx.config.QuotaConfig(),
                new com.nyx.config.BackupConfig(),
                new com.nyx.config.StorageConfig(),
                new CompatibilityConfig(new QloudCompatibilityConfig(true, "127.0.0.1", 8081))
            );
            return constructor.newInstance(
                null,
                playback,
                new LocalPlaybackDeliveryService(playback, cleanupScheduler),
                null,
                null,
                serverConfig,
                new ConcurrentHashMap<>(),
                serverConfig.getCompatibility().getQloud(),
                List.of("api-token"),
                null,
                null,
                null,
                cleanupScheduler,
                null,
                null,
                null
            );
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Object newHlsBridgeSession(
        String token,
        String rpcSession,
        String owner,
        String playbackSessionId,
        String jobId,
        String qloudPath,
        String nyxPath,
        String masterResource,
        Instant createdAt,
        String clientLookupKey
    ) {
        try {
            Class<?> bridgeClass = Class.forName("com.nyx.qloud.QloudCompatibilityRoutes$HlsBridgeSession");
            Constructor<?> constructor = bridgeClass.getDeclaredConstructor(
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                Instant.class,
                String.class
            );
            constructor.setAccessible(true);
            return constructor.newInstance(
                token,
                rpcSession,
                owner,
                playbackSessionId,
                jobId,
                qloudPath,
                nyxPath,
                masterResource,
                createdAt,
                clientLookupKey
            );
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static Object newCompatibilitySession(String rpcSession, String owner, Instant authenticatedAt) {
        try {
            Class<?> sessionClass = Class.forName("com.nyx.qloud.QloudCompatibilityRoutes$CompatibilitySession");
            Constructor<?> constructor = sessionClass.getDeclaredConstructor(String.class, String.class, Instant.class);
            constructor.setAccessible(true);
            return constructor.newInstance(rpcSession, owner, authenticatedAt);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static final class TestScheduledExecutorService extends AbstractExecutorService implements ScheduledExecutorService {
        private final TestScheduledFuture future = new TestScheduledFuture();
        private boolean shutdown;
        private Runnable scheduledCommand;

        private void runScheduledTask() {
            Objects.requireNonNull(scheduledCommand, "No scheduled cleanup task was registered").run();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            scheduledCommand = command;
            return future;
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException("Callable scheduling is not required for these tests");
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            scheduledCommand = command;
            return future;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            scheduledCommand = command;
            return future;
        }

        private static final class TestScheduledFuture implements ScheduledFuture<Object> {
            private boolean cancelled;

            @Override
            public long getDelay(TimeUnit unit) {
                return 0L;
            }

            @Override
            public int compareTo(Delayed other) {
                return 0;
            }

            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                cancelled = true;
                return true;
            }

            @Override
            public boolean isCancelled() {
                return cancelled;
            }

            @Override
            public boolean isDone() {
                return cancelled;
            }

            @Override
            public Object get() {
                return null;
            }

            @Override
            public Object get(long timeout, TimeUnit unit) {
                return null;
            }
        }
    }

    private static final class FakePlaybackSessionService implements PlaybackSessionService {
        private PlaybackRequest lastRequest;
        private String lastOpenOwner;
        private String lastGetSessionOwner;
        private String lastJobOwner;
        private String lastManifestOwner;
        private String sessionJobId = "job-1";
        private int sessionJobIdAvailableAfterLookup = 1;
        private PlaybackLifecyclePhase sessionPhase = PlaybackLifecyclePhase.READY;
        private String sessionFailureMessage;
        private String hlsManifest = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=1280000
            video.m3u8
            """;
        private int hlsManifestAvailableAfterLookup = 1;
        private int jobLookupCount;
        private int manifestLookupCount;
        private int openCount;
        private final List<CloseRequest> closeRequests = new ArrayList<>();

        @Override
        public PlaybackSession openSession(PlaybackRequest request, String owner) {
            lastRequest = request;
            lastOpenOwner = owner;
            openCount++;
            return session("playback-session-" + openCount, sessionPhase, sessionFailureMessage);
        }

        @Override
        public PlaybackSession getSession(String sessionId, String owner) {
            lastGetSessionOwner = owner;
            return session(sessionId, sessionPhase, sessionFailureMessage);
        }

        @Override
        public PlaybackSession reportPlayback(String sessionId, MediaSessionPlaybackReport report, String owner) {
            return session(sessionId);
        }

        @Override
        public String getSessionJobId(String sessionId, String owner) {
            lastJobOwner = owner;
            jobLookupCount++;
            if (jobLookupCount < sessionJobIdAvailableAfterLookup) {
                return null;
            }
            return sessionJobId;
        }

        @Override
        public void closeSession(String sessionId, String owner) {
            closeRequests.add(new CloseRequest(sessionId, owner));
        }

        @Override
        public String getHlsManifest(String sessionId, String owner) {
            lastManifestOwner = owner;
            manifestLookupCount++;
            if (manifestLookupCount < hlsManifestAvailableAfterLookup) {
                return null;
            }
            return hlsManifest;
        }

        @Override
        public String getDashManifest(String sessionId, String owner) {
            return null;
        }

        @Override
        public Path getDirectContentPath(String sessionId, String owner) {
            return null;
        }

        @SuppressWarnings("unused")
        PlaybackRequest lastRequest() {
            return lastRequest;
        }

        String lastOpenOwner() {
            return lastOpenOwner;
        }

        String lastGetSessionOwner() {
            return lastGetSessionOwner;
        }

        String lastJobOwner() {
            return lastJobOwner;
        }

        String lastManifestOwner() {
            return lastManifestOwner;
        }

        List<CloseRequest> closeRequests() {
            return closeRequests;
        }

        int jobLookupCount() {
            return jobLookupCount;
        }

        int manifestLookupCount() {
            return manifestLookupCount;
        }

        void setSessionJobId(String sessionJobId) {
            this.sessionJobId = sessionJobId;
        }

        void setSessionJobIdAvailableAfterLookup(int sessionJobIdAvailableAfterLookup) {
            this.sessionJobIdAvailableAfterLookup = sessionJobIdAvailableAfterLookup;
        }

        void setSessionPhase(PlaybackLifecyclePhase sessionPhase, String sessionFailureMessage) {
            this.sessionPhase = sessionPhase;
            this.sessionFailureMessage = sessionFailureMessage;
        }

        void setHlsManifest(String hlsManifest) {
            this.hlsManifest = hlsManifest;
        }

        void setHlsManifestAvailableAfterLookup(int hlsManifestAvailableAfterLookup) {
            this.hlsManifestAvailableAfterLookup = hlsManifestAvailableAfterLookup;
        }

        private static PlaybackSession session(String sessionId) {
            return session(sessionId, PlaybackLifecyclePhase.READY, null);
        }

        private static PlaybackSession session(String sessionId, PlaybackLifecyclePhase phase, String failureMessage) {
            PlaybackSessionState state = switch (phase) {
                case READY -> PlaybackSessionState.READY;
                case STARTING -> PlaybackSessionState.PENDING;
                case FAILED -> PlaybackSessionState.FAILED;
                case STOPPED, ABANDONED -> PlaybackSessionState.CLOSED;
            };
            return new PlaybackSession(
                sessionId,
                null,
                MediaKind.VIDEO,
                state,
                null,
                new PlaybackSessionArtifacts(StreamingProtocol.HLS, "/compat"),
                null,
                null,
                null,
                "2026-05-27T00:00:00Z",
                new PlaybackSessionLifecycle(phase, null, null, failureMessage, 0.0, true, null)
            );
        }
    }

    private record CloseRequest(
        String sessionId,
        String owner
    ) {
    }

    private static final class StubPlaybackDeliveryService implements PlaybackDeliveryService {
        private final PlaybackDeliveryOutcome outcome;
        private final List<CloseRequest> closeRequests = new ArrayList<>();
        private PlaybackDeliveryRequest lastOpenRequest;

        private StubPlaybackDeliveryService(PlaybackDeliveryOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public PlaybackDeliveryOutcome open(PlaybackDeliveryRequest request) {
            lastOpenRequest = request;
            return outcome;
        }

        @Override
        public PlaybackDeliveryOutcome observe(PlaybackDeliverySessionRequest request) {
            return outcome;
        }

        @Override
        public void close(String sessionId, String owner) {
            closeRequests.add(new CloseRequest(sessionId, owner));
        }

        private PlaybackDeliveryRequest lastOpenRequest() {
            return lastOpenRequest;
        }

        private List<CloseRequest> closeRequests() {
            return closeRequests;
        }
    }

    private static final class FakeTranscodeService implements TranscodeApplicationService {
        private final Path segmentDir;
        private final List<String> mediaPlaylists;
        private boolean outputDirAvailable = true;
        private int playlistCalls;

        private FakeTranscodeService(Path segmentDir) {
            this(segmentDir, List.of("""
                #EXTM3U
                #EXT-X-VERSION:7
                #EXT-X-MAP:URI="segments/init.mp4"
                #EXTINF:6.0,
                segments/chunk_0_001.m4s
                #EXT-X-ENDLIST
                """));
        }

        private FakeTranscodeService(Path segmentDir, List<String> mediaPlaylists) {
            this.segmentDir = segmentDir;
            this.mediaPlaylists = List.copyOf(mediaPlaylists);
        }

        @Override
        public boolean getCircuitBreakerOpen() {
            return false;
        }

        @Override
        public Consumer<JobEvent> getOnJobEvent() {
            return null;
        }

        @Override
        public void setOnJobEvent(Consumer<? super JobEvent> onJobEvent) {
        }

        @Override
        public Flow.Publisher<JobEvent> eventFlow(String jobId) {
            return subscriber -> { };
        }

        @Override
        public TranscodeJob submit(TranscodeRequest request, String batchId, String owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cancel(String jobId, String owner) {
        }

        @Override
        public BatchCancelResponse cancelBatch(String batchId, String owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BatchStatusResponse getBatchStatus(String batchId, String owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TranscodeJob getJob(String jobId) {
            return new TranscodeJob(
                jobId,
                JobStatus.COMPLETED,
                "/library/movie.mp4",
                "h264_balanced",
                StreamRepresentation.HLS_FMP4
            );
        }

        @Override
        public TranscodeJobListing listJobs(int page, int limit, String owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes, int page, int limit, String owner) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getLogs(String jobId) {
            return "";
        }

        @Override
        public String getManifestMpd(String jobId) {
            return null;
        }

        @Override
        public String getManifestM3u8(String jobId) {
            return null;
        }

        @Override
        public String getSubtitlePlaylist(String jobId, int trackIndex) {
            return null;
        }

        @Override
        public String getHlsMediaPlaylist(String jobId, String representationId) {
            assertEquals("video", representationId);
            int index = Math.min(playlistCalls, mediaPlaylists.size() - 1);
            playlistCalls++;
            return mediaPlaylists.get(index);
        }

        int playlistCalls() {
            return playlistCalls;
        }

        void setOutputDirAvailable(boolean outputDirAvailable) {
            this.outputDirAvailable = outputDirAvailable;
        }

        @Override
        public Path getSegmentOutputDir(String jobId) {
            return outputDirAvailable ? segmentDir : null;
        }
    }

    private static final class FakeSegmentCache implements SegmentCacheService {
        private final List<Path> releasedPaths = new ArrayList<>();
        private Path acquiredPath;

        @Override
        public void register(Path segmentPath, String jobId) {
        }

        @Override
        public Path acquire(Path segmentPath) {
            return acquiredPath;
        }

        @Override
        public void release(Path segmentPath) {
            releasedPaths.add(segmentPath);
        }

        @Override
        public void startGracePeriod(String jobId) {
        }

        @Override
        public void purgeAll() {
        }

        @Override
        public int entryCount() {
            return 0;
        }

        void setAcquiredPath(Path acquiredPath) {
            this.acquiredPath = acquiredPath;
        }

        List<Path> releasedPaths() {
            return releasedPaths;
        }
    }
}
