package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.SubtitleExtractor;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.contracts.JobStatus;
import java.nio.file.Files;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class SubtitleRoutesTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void getSubtitleWithInvalidTrackIndexReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-invalid-track");
            installSubtitleRoutes(app, env, null);

            try (Response response = app.client().get("/api/v1/transcode/jobs/any-job/subtitles/notanumber")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleReturns400WhenExtractorNotConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-no-extractor");
            installSubtitleRoutes(app, env, null);

            try (Response response = app.client().get("/api/v1/transcode/jobs/any-job/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleReturns404ForUnknownJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-missing-job");
            installSubtitleRoutes(app, env, new SubtitleExtractor("ffmpeg"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent-job/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleServesCachedVttWithTextVttContentType() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-cached-vtt");
            installSubtitleRoutes(app, env, new SubtitleExtractor("ffmpeg"));

            String jobId = "sub-vtt-1";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            var segmentDir = Files.createDirectories(tempDir.resolve("segments").resolve(jobId));
            Files.writeString(
                segmentDir.resolve("subtitle_0.vtt"),
                "WEBVTT\n\n00:00:01.000 --> 00:00:04.000\nHello World\n"
            );
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String contentType = response.header(HttpHeaders.ContentType);
                assertTrue(contentType != null && contentType.contains("text/vtt"));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("WEBVTT"));
            }
        });
    }

    @Test
    void getSubtitleServesCachedVttWithoutReextracting() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-cached-vtt-noextract");
            installSubtitleRoutes(app, env, new SubtitleExtractor("/nonexistent/ffmpeg"));

            String jobId = "sub-vtt-2";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            var segmentDir = Files.createDirectories(tempDir.resolve("segments2").resolve(jobId));
            Files.writeString(
                segmentDir.resolve("subtitle_0.vtt"),
                "WEBVTT\n\n00:00:02.000 --> 00:00:05.000\nCached subtitle\n"
            );
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("Cached subtitle"));
            }
        });
    }

    @Test
    void postSubtitleReturns400WhenExtractorNotConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-post-noextract");
            installSubtitleRoutes(app, env, null);

            env.jobRepository().create(job("sub-post-1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().post("/api/v1/transcode/jobs/sub-post-1/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleM3u8Returns404ForUnknownJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-m3u8-missing");
            installSubtitleRoutes(app, env, null);

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent-job/subtitles/0.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleM3u8ReturnsHlsPlaylistForKnownJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-m3u8-known");
            installSubtitleRoutes(app, env, null);

            String jobId = "sub-m3u8-1";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/subtitles/0.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String contentType = response.header(HttpHeaders.ContentType);
                assertTrue(contentType != null && contentType.contains("application/vnd.apple.mpegurl"));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("#EXTM3U"));
                assertTrue(body.contains("#EXT-X-TARGETDURATION:99999"));
                assertTrue(body.contains("/api/v1/transcode/jobs/" + jobId + "/subtitles/0"));
                assertTrue(body.contains("#EXT-X-ENDLIST"));
            }
        });
    }

    private void installSubtitleRoutes(
        TranscodeHttpTestSupport.ApplicationHarness app,
        TestEnv env,
        SubtitleExtractor subtitleExtractor
    ) {
        installRoutes(
            app,
            env.transcodeService(),
            env.segmentCache(),
            env.probeService(),
            env.pathSecurity(),
            java.util.List.of(),
            null,
            subtitleExtractor,
            null,
            null,
            testPlaybackDecisionService(),
            null,
            null
        );
    }
}
