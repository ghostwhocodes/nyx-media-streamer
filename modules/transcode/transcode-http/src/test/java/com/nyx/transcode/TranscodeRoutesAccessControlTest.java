package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.SubtitleExtractor;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeJob;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class TranscodeRoutesAccessControlTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void deleteJobByWrongOwnerReturns403() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("cancel-owner1.db");
            env.jobRepository().create(job("alice-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", null, "alice"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "bob");

            try (Response response = app.client().delete(
                "/api/v1/transcode/jobs/alice-j1",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getForbidden(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void deleteJobByCorrectOwnerSucceeds() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("cancel-owner2.db");
            env.jobRepository().create(job("alice-j2", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", null, "alice"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().delete(
                "/api/v1/transcode/jobs/alice-j2",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void deleteJobWithoutAuthSucceedsForBackwardCompat() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("cancel-owner3.db");
            env.jobRepository().create(job("noauth-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", null, "alice"));
            installEnvPlugins(app, env);

            try (Response response = app.client().delete("/api/v1/transcode/jobs/noauth-j1")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getJobsOnlyReturnsJobsOwnedByAuthenticatedUser() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("list-owner1.db");
            env.jobRepository().create(job("alice-list1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", null, "alice"));
            env.jobRepository().create(job("bob-list1", JobStatus.QUEUED, "/test2.mkv", "h264_fast", "dash", null, "bob"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("alice-list1"));
                assertFalse(body.contains("bob-list1"));
            }
        });
    }

    @Test
    void getJobsReturnsAllJobsWhenAuthDisabled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("list-noauth1.db");
            env.jobRepository().create(job("a-list2", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", null, "alice"));
            env.jobRepository().create(job("b-list2", JobStatus.QUEUED, "/test2.mkv", "h264_fast", "dash", null, "bob"));
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("a-list2"));
                assertTrue(body.contains("b-list2"));
            }
        });
    }

    @Test
    void getJobByIdReturns404ForWrongOwner() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("get-owner1.db");
            env.jobRepository().create(job("alice-get1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", null, "alice"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "bob");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/alice-get1",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void batchCancelWithOwnerMismatchAddsToNotFoundList() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-cancel-owner-1");
            env.jobRepository().create(job("bc-own-j1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash", null, "alice"));
            env.jobRepository().updateStatus("bc-own-j1", JobStatus.PROBING);
            env.jobRepository().updateStatus("bc-own-j1", JobStatus.TRANSCODING);
            installCovPlugins(app, env, null, null, List.of("api-token"), "bob");

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\":[\"bc-own-j1\"]}");
                    request.header(HttpHeaders.Authorization, "Bearer any");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("not_found"));
                assertTrue(body.contains("bc-own-j1"));
            }
        });
    }

    @Test
    void getBatchStatusRequiresAuthWhenAuthIsEnabled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-batch-1");
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get("/api/v1/transcode/batches/some-batch")) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getBatchStatusFiltersByOwner() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-batch-2");
            env.jobRepository().create(job("ab1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash", "batch-own-1", "alice"));
            env.jobRepository().create(job("ab2", JobStatus.COMPLETED, "/test2.mkv", "h264_fast", "dash", "batch-own-1", "bob"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/batches/batch-own-1",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("\"total\":1"));
            }
        });
    }

    @Test
    void getLogsRequiresAuthWhenAuthIsEnabled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-logs-1");
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get("/api/v1/transcode/jobs/some-job/logs")) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getLogsEnforcesOwnership() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-logs-2");
            env.jobRepository().create(job("logs-own-1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash", null, "bob"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/logs-own-1/logs",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProgressRequiresAuthWhenAuthIsEnabled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-progress-1");
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/some-job/progress",
                request -> request.header(HttpHeaders.Accept, "text/event-stream")
            )) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProbeRequiresAuthWhenAuthIsEnabled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-probe-1");
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get("/api/v1/media/probe?path=/some/file.mkv")) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProbeSucceedsWithAuthToken() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-probe-2");
            Files.createFile(mediaRoot.resolve("auth-probe.mkv"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/media/probe?path=" + mediaRoot.resolve("auth-probe.mkv").toAbsolutePath(),
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertNotEquals(HttpStatusCode.Companion.getUnauthorized(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentRequiresAuthWhenAuthIsEnabled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-segment-1");
            env.jobRepository().create(job("seg-auth-1", JobStatus.TRANSCODING, "/test.mkv", "h264_fast", "hls", null, "alice"));
            Path outputDir = Files.createDirectories(tempDir.resolve("segments/seg-auth-1"));
            Files.writeString(outputDir.resolve("part-000.ts"), "segment-data");
            registerSegmentOutputDir(env, "seg-auth-1", outputDir);
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get("/api/v1/transcode/jobs/seg-auth-1/segments/part-000.ts")) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentSucceedsWithBearerAuth() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-segment-2");
            env.jobRepository().create(job("seg-auth-2", JobStatus.TRANSCODING, "/test.mkv", "h264_fast", "hls", null, "alice"));
            Path outputDir = Files.createDirectories(tempDir.resolve("segments/seg-auth-2"));
            Files.writeString(outputDir.resolve("part-001.ts"), "authorized-segment");
            registerSegmentOutputDir(env, "seg-auth-2", outputDir);
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/seg-auth-2/segments/part-001.ts",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertEquals("authorized-segment", TranscodeHttpTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getSegmentReturns404ForWrongOwner() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-segment-3");
            env.jobRepository().create(job("seg-auth-3", JobStatus.TRANSCODING, "/test.mkv", "h264_fast", "hls", null, "bob"));
            Path outputDir = Files.createDirectories(tempDir.resolve("segments/seg-auth-3"));
            Files.writeString(outputDir.resolve("part-002.ts"), "hidden-segment");
            registerSegmentOutputDir(env, "seg-auth-3", outputDir);
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/seg-auth-3/segments/part-002.ts",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubtitleExtractEnforcesOwnership() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-sub-1");
            env.jobRepository().create(job("sub-own-1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash", null, "bob"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/sub-own-1/subtitles/0",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitlePlaylistRequiresAuthWhenAuthIsEnabled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-sub-playlist-1");
            env.jobRepository().create(job("sub-pl-auth-1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "hls", null, "alice"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get("/api/v1/transcode/jobs/sub-pl-auth-1/subtitles/0.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitlePlaylistReturns404ForWrongOwner() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-sub-playlist-2");
            env.jobRepository().create(job("sub-pl-auth-2", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "hls", null, "bob"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/sub-pl-auth-2/subtitles/0.m3u8",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleVttRequiresAuthWhenAuthIsEnabled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-sub-vtt-1");
            String jobId = "sub-vtt-auth-1";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "hls", null, "alice"));
            Path outputDir = Files.createDirectories(tempDir.resolve("sub-vtt-auth-1").resolve(jobId));
            Files.writeString(outputDir.resolve("subtitle_0.vtt"), "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhidden");
            registerSegmentOutputDir(env, jobId, outputDir);
            installCovPlugins(app, env, null, new SubtitleExtractor("/nonexistent/ffmpeg"), List.of("api-token"), "alice");

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/subtitles/0")) {
                assertEquals(HttpStatusCode.Companion.getUnauthorized(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSubtitleVttReturns404ForWrongOwner() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-sub-vtt-2");
            String jobId = "sub-vtt-auth-2";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "hls", null, "bob"));
            Path outputDir = Files.createDirectories(tempDir.resolve("sub-vtt-auth-2").resolve(jobId));
            Files.writeString(outputDir.resolve("subtitle_0.vtt"), "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nhidden");
            registerSegmentOutputDir(env, jobId, outputDir);
            installCovPlugins(app, env, null, new SubtitleExtractor("/nonexistent/ffmpeg"), List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/" + jobId + "/subtitles/0",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProgressEnforcesOwnership() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-prog-3");
            env.jobRepository().create(job("prog-own-1", JobStatus.TRANSCODING, "/test.mkv", "h264_fast", "dash", null, "bob"));
            installCovPlugins(app, env, null, null, List.of("api-token"), "alice");

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/prog-own-1/progress",
                request -> {
                    request.header(HttpHeaders.Authorization, "Bearer any");
                    request.header(HttpHeaders.Accept, "text/event-stream");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubtitleExtractReturns404ForNonexistentJobWithExtractor() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("auth-sub-missing");
            installCovPlugins(app, env, null, new SubtitleExtractor("ffmpeg"), List.of("api-token"), "alice");

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/no-such-job/subtitles/0",
                request -> request.header(HttpHeaders.Authorization, "Bearer any")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }
}
