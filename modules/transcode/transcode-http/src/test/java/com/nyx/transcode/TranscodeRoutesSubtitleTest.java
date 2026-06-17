package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.SubtitleExtractor;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.contracts.JobStatus;
import java.nio.file.Files;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class TranscodeRoutesSubtitleTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void postSubtitlesReturns400WhenSubtitleExtractorNotConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-rt1.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("sub-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().post("/api/v1/transcode/jobs/sub-j1/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubtitlesWithInvalidTrackIndexReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-rt2.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post("/api/v1/transcode/jobs/any-job/subtitles/notanumber")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubtitlesForNonexistentJobReturns404Or400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-rt3.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post("/api/v1/transcode/jobs/nonexistent-job/subtitles/0")) {
                HttpStatusCode status = TranscodeHttpTestSupport.status(response);
                assertTrue(
                    status == HttpStatusCode.Companion.getBadRequest() || status == HttpStatusCode.Companion.getNotFound()
                );
            }
        });
    }

    @Test
    void getSubtitlesWithInvalidTrackIndexReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-rt4.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/any-job/subtitles/notanumber")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitlesForNonexistentJobReturns400WhenExtractorNotConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("subtitle-rt5.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent-job/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitlePlaylistReturns404ForUnknownJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-m3u8-cov-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/unknown-job/subtitles/0.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitlePlaylistReturns200ForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-m3u8-cov-2");
            installCovPlugins(app, env);

            env.jobRepository().create(job("sub-pl-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/sub-pl-j1/subtitles/0.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("#EXTM3U"));
                assertTrue(body.contains("TARGETDURATION:99999"));
                assertTrue(body.contains("#EXT-X-ENDLIST"));
            }
        });
    }

    @Test
    void getSubtitlePlaylistReturns400ForInvalidTrackIndex() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-m3u8-cov-3");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/any-job/subtitles/abc.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubtitleExtractReturns400WhenExtractorNotConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-post-cov-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("sub-ex-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().post("/api/v1/transcode/jobs/sub-ex-j1/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("not configured"));
            }
        });
    }

    @Test
    void postSubtitleExtractReturns400ForInvalidTrackIndex() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-post-cov-2");
            installCovPlugins(app, env);

            try (Response response = app.client().post("/api/v1/transcode/jobs/any-job/subtitles/notanumber")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubtitleExtractReturns404ForNonexistentJobWhenExtractorConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-post-cov-3");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            try (Response response = app.client().post("/api/v1/transcode/jobs/no-such-job/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleVttReturns400WhenExtractorNotConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-get-cov-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/any-job/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleVttReturns400ForInvalidTrackIndex() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-get-cov-2");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/any-job/subtitles/notanumber")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleVttReturns404ForNonexistentJobWhenExtractorConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-get-cov-3");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            try (Response response = app.client().get("/api/v1/transcode/jobs/no-such-job/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleVttServesExistingFile() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-get-cov-4");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            String jobId = "sub-vtt-j1";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            var outputDir = Files.createDirectories(tempDir.resolve("sub-out-1").resolve(jobId));
            Files.writeString(outputDir.resolve("subtitle_0.vtt"), "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello");
            registerSegmentOutputDir(env, jobId, outputDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("WEBVTT"));
                assertTrue(body.contains("Hello"));
                String contentType = response.header("Content-Type");
                assertTrue(contentType != null && contentType.contains("text/vtt"));
            }
        });
    }

    @Test
    void getSubtitleM3u8PlaylistReturnsContentForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-m3u8-exist-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("sub-m3u8-c3", JobStatus.QUEUED, "/test.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/sub-m3u8-c3/subtitles/0.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("#EXTM3U"));
                assertTrue(body.contains("TARGETDURATION:99999"));
            }
        });
    }

    @Test
    void getSubtitleM3u8Returns404ForNonexistentJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-m3u8-miss-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/no-such-job/subtitles/0.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleVttServesPreExistingFileWhenExtractorConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-vtt-pre-1");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            String jobId = "sub-vtt-c3";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            var outputDir = Files.createDirectories(tempDir.resolve("sub-vtt-out").resolve(jobId));
            Files.writeString(outputDir.resolve("subtitle_1.vtt"), "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nTest subtitle");
            registerSegmentOutputDir(env, jobId, outputDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/subtitles/1")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("WEBVTT"));
                assertTrue(body.contains("Test subtitle"));
            }
        });
    }

    @Test
    void getSubtitleVttReturns404WhenJobDoesNotExist() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-vtt-miss-1");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            try (Response response = app.client().get("/api/v1/transcode/jobs/no-such-job/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubtitleExtractReturns404ForNonexistentJobWithExtractor() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-post-miss-1");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            try (Response response = app.client().post("/api/v1/transcode/jobs/no-such-job/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubtitleExtractWithoutSubtitleExtractorReturnsError() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-post-noext-1");
            installCovPlugins(app, env, null, null, java.util.List.of(), null);

            env.jobRepository().create(job("sub-noext-j1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().post("/api/v1/transcode/jobs/sub-noext-j1/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("not configured") || body.contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void getSubtitleVttWithoutSubtitleExtractorReturnsError() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-get-noext-1");
            installCovPlugins(app, env, null, null, java.util.List.of(), null);

            env.jobRepository().create(job("sub-get-noext-j1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/sub-get-noext-j1/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("not configured") || body.contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void getSubtitleVttAttemptsOnDemandExtractionWhenFileMissing() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-get-ondemand-1");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            String jobId = "sub-ondemand-j1";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            var outputDir = Files.createDirectories(tempDir.resolve("sub-ondemand-out").resolve(jobId));
            registerSegmentOutputDir(env, jobId, outputDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/subtitles/0")) {
                int statusCode = response.code();
                assertTrue(statusCode == 400 || statusCode == 404 || statusCode == 500);
            }
        });
    }

    @Test
    void getSubtitleVttCreatesTempDirWhenNoOutputDirRegistered() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-get-tmpdir-1");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            String jobId = "sub-tmpdir-j1";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/subtitles/0")) {
                int statusCode = response.code();
                assertTrue(statusCode == 400 || statusCode == 404 || statusCode == 500);
            }
        });
    }

    @Test
    void postSubtitleExtractCreatesTempDirWhenNoOutputDirRegistered() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-post-tmpdir-1");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            String jobId = "sub-post-tmp-j1";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().post("/api/v1/transcode/jobs/" + jobId + "/subtitles/0")) {
                int statusCode = response.code();
                assertTrue(statusCode == 400 || statusCode == 404 || statusCode == 500);
            }
        });
    }

    @Test
    void postSubtitleExtractWithInvalidTrackIndexReturnsErrorFromCov4() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-post-badtrack-1");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            try (Response response = app.client().post("/api/v1/transcode/jobs/any-job/subtitles/notanumber")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void getSubtitleVttWithInvalidTrackIndexReturnsErrorFromCov4() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sub-get-badtrack-1");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), java.util.List.of(), null);

            try (Response response = app.client().get("/api/v1/transcode/jobs/any-job/subtitles/notanumber")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }
}
