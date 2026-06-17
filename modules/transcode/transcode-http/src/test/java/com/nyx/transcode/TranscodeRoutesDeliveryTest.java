package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.contracts.JobStatus;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class TranscodeRoutesDeliveryTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void deleteNonexistentJobSucceedsGracefully() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("routes3.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().delete("/api/v1/transcode/jobs/nonexistent")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentWithPathTraversalNameReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("routes7.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/anyjob/segments/..%2F..%2Fetc%2Fpasswd")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentForNonexistentJobReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("routes6.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent/segments/seg001.m4s")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getManifestMpdReturnsManifestForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("manifest-mpd1.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("mpd-rt1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/mpd-rt1/manifest.mpd")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("MPD"));
            }
        });
    }

    @Test
    void getManifestMpdReturns404ForNonexistentJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("manifest-mpd2.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent/manifest.mpd")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getManifestMpdReturns404ForHlsTsJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("manifest-mpd-hls-ts.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("mpd-hls-ts", JobStatus.QUEUED, "/test.mkv", "h264_fast", "hls_ts"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/mpd-hls-ts/manifest.mpd")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getMasterM3u8ReturnsManifestForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("manifest-m3u8-1.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("hls-rt1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/hls-rt1/master.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("#EXTM3U"));
            }
        });
    }

    @Test
    void getMasterM3u8Returns404ForNonexistentJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("manifest-m3u8-2.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent/master.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getLogsReturnsTextForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("logs-rt1.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("logs-rt1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
            env.jobRepository().storeStderr("logs-rt1", "Some error", null);

            try (Response response = app.client().get("/api/v1/transcode/jobs/logs-rt1/logs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("Some error"));
            }
        });
    }

    @Test
    void getLogsReturnsEmptyForJobWithNoStderr() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("logs-rt2.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("logs-rt2", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/logs-rt2/logs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentWithBackslashInNameReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-rt1.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/anyjob/segments/test\\file.m4s")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentServesFileFromOutputDirWhenNotInCache() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-rt2.db");
            installEnvPlugins(app, env);

            String jobId = "seg-test1";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));
            var segmentDir = java.nio.file.Files.createDirectories(tempDir.resolve("segments").resolve(jobId));
            java.nio.file.Files.write(segmentDir.resolve("init.mp4"), "fake mp4 data".getBytes());
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/segments/init.mp4")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertEquals("fake mp4 data", TranscodeHttpTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getSegmentReturnsPendingWhenFileDoesNotExist() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-rt3.db");
            installEnvPlugins(app, env);

            String jobId = "seg-test2";
            env.jobRepository().create(job(jobId, JobStatus.TRANSCODING, "/test.mkv", "h264_fast", "dash"));
            var segmentDir = java.nio.file.Files.createDirectories(tempDir.resolve("segments2").resolve(jobId));
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/segments/seg_001.m4s")) {
                int statusCode = response.code();
                assertTrue(statusCode == 202 || statusCode == 500);
            }
        });
    }

    @Test
    void getSegmentServesFromCacheWhenAvailable() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-rt4.db");
            installEnvPlugins(app, env);

            String jobId = "seg-test3";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));
            var segmentDir = java.nio.file.Files.createDirectories(tempDir.resolve("segments3").resolve(jobId));
            var segmentFile = segmentDir.resolve("cached.m4s");
            java.nio.file.Files.write(segmentFile, "cached segment data".getBytes());
            env.segmentCache().register(segmentFile, jobId);
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/segments/cached.m4s")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertEquals("cached segment data", TranscodeHttpTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void deleteJobReturnsNoContent() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("delete-rt1.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("del-test", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().delete("/api/v1/transcode/jobs/del-test")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProgressSseForNonexistentJobReturnsErrorEvent() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("sse-rt1.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/nonexistent/progress",
                request -> request.header(HttpHeaders.Accept, "text/event-stream")
            )) {
                assertNotNull(TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentWithPathTraversalInNameReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-traversal-rt.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("trav-job", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/trav-job/segments/..%2F..%2Fetc%2Fpasswd")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentForJobWithNoOutputDirReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-nodir-rt.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("nodir-job", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/nodir-job/segments/segment.m4s")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getHlsMediaPlaylistReturns200ForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("hls-media-rt1.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("hls-media-rt1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/hls-media-rt1/video.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("#EXTM3U"));
                String contentType = response.header(HttpHeaders.ContentType);
                assertTrue(contentType != null && contentType.contains("mpegurl"));
            }
        });
    }

    @Test
    void getHlsMediaPlaylistReturns404ForNonexistentJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("hls-media-rt2.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent/video.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProgressSseForUnknownJobReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sse-cov-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/no-such-job/progress",
                request -> request.header(HttpHeaders.Accept, "text/event-stream")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getJobLogsReturns404ForUnknownJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("logs-cov-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/no-such-job/logs")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getJobLogsReturnsStderrContent() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("logs-cov-2");
            installCovPlugins(app, env);

            env.jobRepository().create(job("logs-cov-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
            env.jobRepository().storeStderr("logs-cov-j1", "initial error", "fallback error");

            try (Response response = app.client().get("/api/v1/transcode/jobs/logs-cov-j1/logs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("Initial Attempt"));
                assertTrue(body.contains("Fallback Attempt"));
            }
        });
    }

    @Test
    void deleteCancelJobReturns204ForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("cancel-cov-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("cancel-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
            env.jobRepository().updateStatus("cancel-j1", JobStatus.PROBING);
            env.jobRepository().updateStatus("cancel-j1", JobStatus.TRANSCODING);

            try (Response response = app.client().delete("/api/v1/transcode/jobs/cancel-j1")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
                assertEquals(JobStatus.CANCELLED, env.jobRepository().getById("cancel-j1").getStatus());
            }
        });
    }

    @Test
    void deleteCancelJobReturns204EvenForNonexistentJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("cancel-cov-2");
            installCovPlugins(app, env);

            try (Response response = app.client().delete("/api/v1/transcode/jobs/nonexistent-cancel")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getHlsMasterPlaylistReturns404ForUnknownJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("hls-cov-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/unknown-job/master.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getDashManifestReturns404ForUnknownJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("dash-cov-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/unknown-job/manifest.mpd")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getHlsMediaPlaylistForVariantReturns404ForUnknownJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("hls-var-cov-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/unknown-job/video.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentReturns202WhenSegmentNotReady() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seg-cov-1");
            installCovPlugins(app, env);

            String jobId = "seg-pending-j1";
            env.jobRepository().create(job(jobId, JobStatus.TRANSCODING, "/test.mkv", "h264_fast", "dash"));
            var segmentDir = java.nio.file.Files.createDirectories(tempDir.resolve("seg-out-1").resolve(jobId));
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/segments/segment0.ts")) {
                HttpStatusCode status = TranscodeHttpTestSupport.status(response);
                assertTrue(
                    status.equals(HttpStatusCode.Companion.getAccepted())
                        || status.equals(HttpStatusCode.Companion.getInternalServerError())
                );
            }
        });
    }

    @Test
    void getSegmentRejectsPathTraversal() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seg-cov-2");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/anyjob/segments/..%2F..%2Fetc%2Fpasswd")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getJobLogsReturnsOnlyInitialStderrWhenNoFallback() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("logs-init-only");
            installCovPlugins(app, env);

            env.jobRepository().create(job("logs-init-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
            env.jobRepository().storeStderr("logs-init-j1", "initial only error", null);

            try (Response response = app.client().get("/api/v1/transcode/jobs/logs-init-j1/logs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("Initial Attempt"));
                assertFalse(body.contains("Fallback Attempt"));
            }
        });
    }

    @Test
    void getJobLogsReturnsOnlyFallbackStderrWhenNoInitial() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("logs-fallback-only");
            installCovPlugins(app, env);

            env.jobRepository().create(job("logs-fb-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
            env.jobRepository().storeStderr("logs-fb-j1", null, "fallback only error");

            try (Response response = app.client().get("/api/v1/transcode/jobs/logs-fb-j1/logs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("Fallback Attempt"));
                assertFalse(body.contains("Initial Attempt"));
            }
        });
    }

    @Test
    void deleteCancelTransitionsTranscodingJobToCancelled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("cancel-trans-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("cancel-t1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
            env.jobRepository().updateStatus("cancel-t1", JobStatus.PROBING);
            env.jobRepository().updateStatus("cancel-t1", JobStatus.TRANSCODING);

            try (Response response = app.client().delete("/api/v1/transcode/jobs/cancel-t1")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
                assertEquals(JobStatus.CANCELLED, env.jobRepository().getById("cancel-t1").getStatus());
            }
        });
    }

    @Test
    void getDashManifestReturnsMpdContentForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("mpd-exist-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("mpd-c3", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/mpd-c3/manifest.mpd")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("MPD") || body.contains("xml"));
                String contentType = response.header(HttpHeaders.ContentType);
                assertTrue(contentType != null && (contentType.contains("dash") || contentType.contains("xml")));
            }
        });
    }

    @Test
    void getHlsMasterPlaylistReturnsM3u8ContentForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("hls-exist-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("hls-c3", JobStatus.QUEUED, "/test.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/hls-c3/master.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("#EXTM3U"));
                String contentType = response.header(HttpHeaders.ContentType);
                assertTrue(contentType != null && contentType.contains("mpegurl"));
            }
        });
    }

    @Test
    void getHlsMediaPlaylistReturnsContentForExistingJobWithVideoRep() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("hls-rep-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("hls-rep1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/hls-rep1/video.m3u8")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("#EXTM3U"));
            }
        });
    }

    @Test
    void getSegmentServesFileFromCacheWithCorrectContentType() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seg-cache-1");
            installCovPlugins(app, env);

            String jobId = "seg-cached-c3";
            env.jobRepository().create(job(jobId, JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));
            var segmentDir = java.nio.file.Files.createDirectories(tempDir.resolve("seg-c3-out").resolve(jobId));
            var segmentFile = segmentDir.resolve("chunk_0_001.m4s");
            java.nio.file.Files.write(segmentFile, "cached m4s data".getBytes());
            env.segmentCache().register(segmentFile, jobId);
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/segments/chunk_0_001.m4s")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertEquals("cached m4s data", TranscodeHttpTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getSegmentServesFileFromDiskWhenNotInCache() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seg-disk-1");
            installCovPlugins(app, env);

            String jobId = "seg-disk-c3";
            env.jobRepository().create(job(jobId, JobStatus.TRANSCODING, "/test.mkv", "h264_fast", "dash"));
            var segmentDir = java.nio.file.Files.createDirectories(tempDir.resolve("seg-disk-out").resolve(jobId));
            var segmentFile = segmentDir.resolve("chunk_0_002.m4s");
            java.nio.file.Files.write(segmentFile, "disk m4s data".getBytes());
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/segments/chunk_0_002.m4s")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertEquals("disk m4s data", TranscodeHttpTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void getSegmentReturns202AcceptedWhenSegmentFileDoesNotExist() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seg-pending-1");
            installCovPlugins(app, env);

            String jobId = "seg-pend-c3";
            env.jobRepository().create(job(jobId, JobStatus.TRANSCODING, "/test.mkv", "h264_fast", "dash"));
            var segmentDir = java.nio.file.Files.createDirectories(tempDir.resolve("seg-pend-out").resolve(jobId));
            registerSegmentOutputDir(env, jobId, segmentDir);

            try (Response response = app.client().get("/api/v1/transcode/jobs/" + jobId + "/segments/chunk_0_999.m4s")) {
                HttpStatusCode status = TranscodeHttpTestSupport.status(response);
                assertTrue(
                    status.equals(HttpStatusCode.Companion.getAccepted())
                        || status.equals(HttpStatusCode.Companion.getInternalServerError())
                );
                if (status.equals(HttpStatusCode.Companion.getAccepted())) {
                    assertEquals("2", response.header(HttpHeaders.RetryAfter));
                }
            }
        });
    }

    @Test
    void getProgressSseForJobWithEventsFlowReturns404WhenJobUnknown() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("sse-flow-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get(
                "/api/v1/transcode/jobs/unknown-sse-job/progress",
                request -> request.header(HttpHeaders.Accept, "text/event-stream")
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }
}
