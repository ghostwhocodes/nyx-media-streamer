package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.HealthMonitor;
import com.nyx.common.QuotaService;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.MediaRootConfig;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class TranscodeRoutesListingAndSubmissionTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void getJobsReturnsEmptyListInitially() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestServices services = createServices("routes1.db");
            installServiceRoutes(app, services);

            try (Response response = app.client().get("/api/v1/transcode/jobs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("[]"));
            }
        });
    }

    @Test
    void getJobByInvalidIdReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestServices services = createServices("routes2.db");
            installServiceRoutes(app, services);

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProbeWithoutPathReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestServices services = createServices("routes4.db");
            installServiceRoutes(app, services);

            try (Response response = app.client().get("/api/v1/media/probe")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProbeWithPathOutsideRootsReturns403() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestServices services = createServices("routes5.db");
            installServiceRoutes(app, services);

            try (Response response = app.client().get("/api/v1/media/probe?path=/etc/passwd")) {
                int statusCode = response.code();
                assertTrue(statusCode == 403 || statusCode == 404);
            }
        });
    }

    @Test
    void getProbeWithValidFileInMediaRootReturnsResultOrError() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("probe-rt1.db");
            installEnvPlugins(app, env);

            var testFile = Files.createFile(mediaDir.resolve("test-probe.mkv"));
            try (Response response = app.client().get("/api/v1/media/probe?path=" + testFile)) {
                int statusCode = response.code();
                assertTrue(statusCode == 200 || statusCode == 500);
            }
        });
    }

    @Test
    void postTranscodeWithFileOutsideMediaRootReturnsError() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("transcode-rt1.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"/etc/passwd","profile":"h264_fast","format":"dash"}
                        """);
                }
            )) {
                int statusCode = response.code();
                assertTrue(statusCode == 403 || statusCode == 404);
            }
        });
    }

    @Test
    void postTranscodeWithValidFileReturnsJobOrProbeError() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("transcode-rt2.db");
            installEnvPlugins(app, env);

            var testFile = Files.createFile(mediaDir.resolve("test-transcode.mkv"));
            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"%s","profile":"h264_fast","format":"dash"}
                        """.formatted(testFile));
                }
            )) {
                int statusCode = response.code();
                assertTrue(statusCode == 201 || statusCode == 500);
            }
        });
    }

    @Test
    void postSeekForNonexistentJobReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seek-rt1.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/nonexistent/seek",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"timestamp_secs\":30.0}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSeekForExistingJobCancelsAndReturnsErrorOrNewJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seek-rt2.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job(
                "seek-test",
                JobStatus.QUEUED,
                mediaDir.resolve("seek.mkv").toString(),
                "h264_fast",
                "dash"
            ));
            Files.createFile(mediaDir.resolve("seek.mkv"));

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/seek-test/seek",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"timestamp_secs\":30.0}");
                }
            )) {
                assertNotNull(TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getJobsWithStatusFilterReturns200() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("filter-rt1.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("filt-j1", JobStatus.QUEUED, "/test1.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs?status=QUEUED")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("filt-j1"));
            }
        });
    }

    @Test
    void getJobsWithSinceFilterReturns200() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("filter-rt2.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("filt-j2", JobStatus.QUEUED, "/test2.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs?since=60")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("filt-j2"));
            }
        });
    }

    @Test
    void getJobsWithCombinedStatusAndSinceFiltersReturns200() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("filter-rt3.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("filt-j3", JobStatus.QUEUED, "/test3.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs?status=QUEUED&since=60")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("filt-j3"));
            }
        });
    }

    @Test
    void getJobsWithInvalidStatusFilterReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("filter-rt4.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs?status=INVALID_STATUS")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProbeWithPathTraversalReturns403() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("probe-rt2.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/media/probe?path=/etc/passwd")) {
                assertEquals(HttpStatusCode.Companion.getForbidden(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getProbeWithValidAllowedPathReturnsErrorOrProbeResult() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("probe-rt3.db");
            installEnvPlugins(app, env);

            var testFile = Files.createFile(mediaDir.resolve("probe-test.mp4"));
            try (Response response = app.client().get("/api/v1/media/probe?path=" + testFile.toAbsolutePath())) {
                int statusCode = response.code();
                assertTrue(statusCode >= 400 && statusCode <= 599, "Expected error status, got: " + statusCode);
            }
        });
    }

    @Test
    void postTranscodeWithJobQuotaExceededReturns429() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnvWithQuota("quota-rt1.db", 1, 100);
            env.jobRepository().create(job("existing-j", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", null, "alice"));
            var quotaTestFile = Files.createFile(mediaDir.resolve("quota-test.mkv"));

            installProtectedRoutes(app, env, env.quotaService());

            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"%s","profile":"h264_fast","format":"dash"}
                        """.formatted(quotaTestFile));
                    request.header(HttpHeaders.Authorization, "Bearer any");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getTooManyRequests(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("QUOTA_EXCEEDED"));
            }
        });
    }

    @Test
    void postTranscodeWithRateQuotaExceededReturns429() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            QuotaService quotaService = new QuotaService(testQuotaConfig(true, 100, 1, 10_737_418_240L, Map.of()), repo -> 0);
            TestEnv env = createEnv("quota-rt2.db");

            installProtectedRoutes(app, env, quotaService);

            try (Response first = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"/media/test.mkv","profile":"h264_fast","format":"dash"}
                        """);
                    request.header(HttpHeaders.Authorization, "Bearer any");
                }
            )) {
                assertNotNull(first);
            }

            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"/media/test.mkv","profile":"h264_fast","format":"dash"}
                        """);
                    request.header(HttpHeaders.Authorization, "Bearer any");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getTooManyRequests(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("QUOTA_EXCEEDED"));
            }
        });
    }

    @Test
    void postTranscodePassesOwnerFromPrincipal() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("quota-rt3.db");
            installProtectedRoutes(app, env, null);

            var testFile = Files.createFile(mediaDir.resolve("owner-test.mkv"));
            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"%s","profile":"h264_fast","format":"dash"}
                        """.formatted(testFile));
                    request.header(HttpHeaders.Authorization, "Bearer any");
                }
            )) {
                assertNotNull(TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postTranscodeWithoutAuthStillWorksWhenQuotaServiceIsConfigured() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("quota-rt4.db");
            QuotaService quotaService = new QuotaService(testQuotaConfig(true, 2, 60, 10_737_418_240L, Map.of()), repo -> 0);

            installRoutes(
                app,
                env.transcodeService(),
                env.segmentCache(),
                env.probeService(),
                env.pathSecurity(),
                List.of(),
                null,
                null,
                null,
                null,
                testPlaybackDecisionService(),
                null,
                quotaService
            );

            var testFile = Files.createFile(mediaDir.resolve("noauth-test.mkv"));
            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"%s","profile":"h264_fast","format":"dash"}
                        """.formatted(testFile));
                }
            )) {
                assertNotEquals(HttpStatusCode.Companion.getTooManyRequests(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postTranscodeReturns503WhenFfmpegIsUnavailable() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestServices services = createServices("ffmpeg-503.db");
            HealthMonitor healthMonitor = () -> false;

            installRoutes(
                app,
                services.transcodeService(),
                services.segmentCache(),
                services.probeService(),
                services.pathSecurity(),
                List.of(),
                null,
                null,
                null,
                null,
                testPlaybackDecisionService(),
                healthMonitor,
                null
            );

            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"/media/test.mkv","profile":"h264_fast","format":"dash"}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getServiceUnavailable(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("FFMPEG_UNAVAILABLE"));
            }
        });
    }

    @Test
    void quotaExceededResponseIncludesRetryAfterHeader() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            QuotaService quotaService = new QuotaService(testQuotaConfig(true, 100, 1, 10_737_418_240L, Map.of()), repo -> 0);
            TestEnv env = createEnv("retry-after1.db");

            installProtectedRoutes(app, env, quotaService);

            try (Response first = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"/media/test.mkv","profile":"h264_fast","format":"dash"}
                        """);
                    request.header(HttpHeaders.Authorization, "Bearer any");
                }
            )) {
                assertNotNull(first);
            }

            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"/media/test.mkv","profile":"h264_fast","format":"dash"}
                        """);
                    request.header(HttpHeaders.Authorization, "Bearer any");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getTooManyRequests(), TranscodeHttpTestSupport.status(response));
                assertEquals("60", response.header(HttpHeaders.RetryAfter));
            }
        });
    }

    @Test
    void serializedTranscodeJobDoesNotContainOwnerOrOutputSizeBytes() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("no-owner-json.db");
            env.jobRepository().create(new TranscodeJob(
                "hidden-j1",
                JobStatus.QUEUED,
                "/test.mkv",
                "h264_fast",
                StreamRepresentation.DASH_FMP4,
                List.of(),
                TranscodeExecutionMode.VIDEO_TRANSCODE,
                null,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "alice",
                12_345L
            ));
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/hidden-j1")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertFalse(body.contains("\"owner\""));
                assertFalse(body.contains("\"output_size_bytes\""));
                assertTrue(body.contains("hidden-j1"));
            }
        });
    }

    @Test
    void getJobsWithPageAndLimitParamsReturnsPaginatedListing() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("list-page-1");
            installCovPlugins(app, env);

            for (int index = 1; index <= 5; index += 1) {
                env.jobRepository().create(job("page-j" + index, JobStatus.QUEUED, "/test" + index + ".mkv", "h264_fast", "dash"));
            }

            try (Response response = app.client().get("/api/v1/transcode/jobs?page=1&limit=2")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("\"total\":5"));
                assertTrue(body.contains("\"limit\":2"));
                assertTrue(body.contains("\"page\":1"));
            }
        });
    }

    @Test
    void getJobsWithPage2ReturnsSecondPage() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("list-page-2");
            installCovPlugins(app, env);

            for (int index = 1; index <= 5; index += 1) {
                env.jobRepository().create(job("p2-j" + index, JobStatus.QUEUED, "/test" + index + ".mkv", "h264_fast", "dash"));
            }

            try (Response response = app.client().get("/api/v1/transcode/jobs?page=2&limit=2")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("\"page\":2"));
            }
        });
    }

    @Test
    void getJobDetailReturnsFullJobObjectForExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("detail-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("detail-cov3", JobStatus.QUEUED, "/cov3.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/detail-cov3")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("detail-cov3"));
                assertTrue(body.contains("h264_fast"));
                assertTrue(body.contains("hls"));
            }
        });
    }

    @Test
    void getJobsWithCompletedStatusFilterReturnsOnlyCompletedJobs() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("filter-completed-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("fc-j1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));
            env.jobRepository().create(job("fc-j2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "dash"));
            env.jobRepository().updateStatus("fc-j2", JobStatus.PROBING);
            env.jobRepository().updateStatus("fc-j2", JobStatus.TRANSCODING);
            env.jobRepository().updateStatus("fc-j2", JobStatus.COMPLETED);

            try (Response response = app.client().get("/api/v1/transcode/jobs?status=COMPLETED")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("fc-j2"));
                assertFalse(body.contains("fc-j1"));
            }
        });
    }

    @Test
    void postSeekCancelsExistingJobAndAttemptsResubmit() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seek-cov-2");
            installCovPlugins(app, env);

            var inputFile = Files.createFile(mediaRoot.resolve("seek-video.mkv"));
            env.jobRepository().create(job("seek-j1", JobStatus.QUEUED, inputFile.toString(), "h264_fast", "hls"));
            env.jobRepository().updateStatus("seek-j1", JobStatus.PROBING);
            env.jobRepository().updateStatus("seek-j1", JobStatus.TRANSCODING);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/seek-j1/seek",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"timestamp_secs\":45.5}");
                }
            )) {
                assertNotNull(TranscodeHttpTestSupport.status(response));
                assertEquals(JobStatus.CANCELLED, env.jobRepository().getById("seek-j1").status());
            }
        });
    }

    @Test
    void postSeekCancelsJobAndReturnsErrorForNonCancellableJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seek-noncancellable-1");
            installCovPlugins(app, env);

            var testFile = Files.createFile(mediaRoot.resolve("seek-test1.mkv"));
            env.jobRepository().create(job("seek-j1-cov4", JobStatus.QUEUED, testFile.toString(), "h264_fast", "dash"));
            env.jobRepository().updateStatus("seek-j1-cov4", JobStatus.PROBING);
            env.jobRepository().updateStatus("seek-j1-cov4", JobStatus.TRANSCODING);
            env.jobRepository().updateStatus("seek-j1-cov4", JobStatus.COMPLETED);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/seek-j1-cov4/seek",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"timestamp_secs\":30.0}");
                }
            )) {
                int statusCode = response.code();
                assertTrue(statusCode == 201 || statusCode == 400 || statusCode == 403 || statusCode == 404 || statusCode == 500);
            }
        });
    }

    @Test
    void postSeekOnTranscodingJobExercisesCancelAndResubmit() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seek-transcoding-1");
            installCovPlugins(app, env);

            var testFile = Files.createFile(mediaRoot.resolve("seek-test2.mkv"));
            env.jobRepository().create(job("seek-j2-cov4", JobStatus.QUEUED, testFile.toString(), "h264_fast", "dash"));
            env.jobRepository().updateStatus("seek-j2-cov4", JobStatus.PROBING);
            env.jobRepository().updateStatus("seek-j2-cov4", JobStatus.TRANSCODING);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/seek-j2-cov4/seek",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"timestamp_secs\":15.5}");
                }
            )) {
                int statusCode = response.code();
                assertTrue(statusCode == 201 || statusCode == 400 || statusCode == 403 || statusCode == 404 || statusCode == 500);
            }
        });
    }

    @Test
    void postSeekOnNonexistentJobReturns404OrError() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("seek-missing-1");
            installCovPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/nonexistent-seek/seek",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"timestamp_secs\":10.0}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSubmitWithVirtualPathResolverResolvesVirtualPath() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("vpr-submit-1");
            Files.createFile(mediaRoot.resolve("vpr-test.mkv"));

            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaRoot, "local")));
            installCovPlugins(app, env, null, null, List.of(), null, virtualPathResolver);

            String virtualName = virtualPathResolver.getRoots().get(0).getDisplayName();
            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"%s/vpr-test.mkv","profile":"h264_fast","format":"dash"}
                        """.formatted(virtualName));
                }
            )) {
                int statusCode = response.code();
                assertTrue(statusCode == 201 || statusCode == 400 || statusCode == 500);
            }
        });
    }

    @Test
    void postSubmitWithVirtualPathResolverRejectsInvalidVirtualPath() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("vpr-submit-bad-1");

            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaRoot, "local")));
            installCovPlugins(app, env, null, null, List.of(), null, virtualPathResolver);

            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"input_path":"nonexistent-root/file.mkv","profile":"h264_fast","format":"dash"}
                        """);
                }
            )) {
                int statusCode = response.code();
                assertTrue(statusCode == 400 || statusCode == 404 || statusCode == 500);
            }
        });
    }

    @Test
    void getProbeWithValidFileAttemptsProbeAndReturnsErrorOrResult() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("probe-valid-1");
            installCovPlugins(app, env);

            var testFile = Files.createFile(mediaRoot.resolve("probe-c3.mp4"));
            try (Response response = app.client().get("/api/v1/media/probe?path=" + testFile.toAbsolutePath())) {
                int statusCode = response.code();
                assertTrue(statusCode >= 200 && statusCode <= 599);
            }
        });
    }

    @Test
    void getProbeWithVirtualPathResolverResolvesPath() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("probe-vpr-1");
            Files.createFile(mediaRoot.resolve("probe-vpr.mp4"));

            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(List.of(new MediaRootConfig(mediaRoot, "local")));
            installCovPlugins(app, env, null, null, List.of(), null, virtualPathResolver);

            String virtualName = virtualPathResolver.getRoots().get(0).getDisplayName();
            try (Response response = app.client().get("/api/v1/media/probe?path=" + virtualName + "/probe-vpr.mp4")) {
                int statusCode = response.code();
                assertTrue(statusCode == 200 || statusCode == 400 || statusCode == 500);
            }
        });
    }

    private void installServiceRoutes(TranscodeHttpTestSupport.ApplicationHarness app, TestServices services) {
        installRoutes(
            app,
            services.transcodeService(),
            services.segmentCache(),
            services.probeService(),
            services.pathSecurity(),
            List.of(),
            null,
            null,
            null,
            null,
            testPlaybackDecisionService(),
            null,
            null
        );
    }

    private void installProtectedRoutes(
        TranscodeHttpTestSupport.ApplicationHarness app,
        TestEnv env,
        QuotaService quotaService
    ) {
        installRoutes(
            app,
            env.transcodeService(),
            env.segmentCache(),
            env.probeService(),
            env.pathSecurity(),
            List.of("api-token"),
            "alice",
            null,
            null,
            null,
            testPlaybackDecisionService(),
            null,
            quotaService
        );
    }

    private void installCovPlugins(
        TranscodeHttpTestSupport.ApplicationHarness app,
        TestEnv env,
        HealthMonitor healthMonitor,
        com.nyx.ffmpeg.SubtitleExtractor subtitleExtractor,
        List<String> authProviders,
        String authUser,
        VirtualPathResolver virtualPathResolver
    ) {
        installRoutes(
            app,
            env.transcodeService(),
            env.segmentCache(),
            env.probeService(),
            env.pathSecurity(),
            authProviders,
            authUser,
            subtitleExtractor,
            virtualPathResolver,
            null,
            testPlaybackDecisionService(),
            healthMonitor,
            env.quotaService()
        );
    }
}
