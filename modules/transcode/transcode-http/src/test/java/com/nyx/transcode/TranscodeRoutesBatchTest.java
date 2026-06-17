package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.HealthMonitor;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.contracts.JobStatus;
import java.util.List;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class TranscodeRoutesBatchTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void getBatchStatusReturns404ForUnknownBatch() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-status-1");
            installCovPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/batches/unknown-batch-id")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("Batch not found"));
            }
        });
    }

    @Test
    void getBatchStatusReturns200ForExistingBatch() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-status-2");
            installCovPlugins(app, env);

            env.jobRepository().create(job("bs-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", "test-batch-1", null));
            env.jobRepository().create(job("bs-j2", JobStatus.COMPLETED, "/test2.mkv", "h264_fast", "dash", "test-batch-1", null));

            try (Response response = app.client().get("/api/v1/transcode/batches/test-batch-1")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("test-batch-1"));
                assertTrue(body.contains("\"total\":2"));
            }
        });
    }

    @Test
    void deleteBatchCancelReturns404ForUnknownBatch() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-cancel-1");
            installCovPlugins(app, env);

            try (Response response = app.client().delete("/api/v1/transcode/batches/unknown-batch")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("Batch not found"));
            }
        });
    }

    @Test
    void deleteBatchCancelCancelsActiveJobsInBatch() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-cancel-2");
            installCovPlugins(app, env);

            env.jobRepository().create(job("bc-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash", "cancel-batch-1", null));
            env.jobRepository().create(job("bc-j2", JobStatus.COMPLETED, "/test2.mkv", "h264_fast", "dash", "cancel-batch-1", null));

            try (Response response = app.client().delete("/api/v1/transcode/batches/cancel-batch-1")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("bc-j1") || body.contains("cancelled"));
            }
        });
    }

    @Test
    void postBatchCancelWithNonexistentJobsReturnsThemInNotFound() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-post-cancel-1");
            installCovPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\":[\"nope-1\",\"nope-2\"]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("nope-1"));
                assertTrue(body.contains("nope-2"));
                assertTrue(body.contains("not_found"));
            }
        });
    }

    @Test
    void postBatchCancelCancelsExistingJobs() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-post-cancel-2");
            installCovPlugins(app, env);

            env.jobRepository().create(job("bpc-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
            env.jobRepository().updateStatus("bpc-j1", JobStatus.PROBING);
            env.jobRepository().updateStatus("bpc-j1", JobStatus.TRANSCODING);
            env.jobRepository().create(job("bpc-j2", JobStatus.QUEUED, "/test2.mkv", "h264_fast", "dash"));
            env.jobRepository().updateStatus("bpc-j2", JobStatus.PROBING);
            env.jobRepository().updateStatus("bpc-j2", JobStatus.TRANSCODING);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\":[\"bpc-j1\",\"bpc-j2\",\"nonexistent\"]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("cancelled"));
                assertEquals(JobStatus.CANCELLED, env.jobRepository().getById("bpc-j1").getStatus());
                assertEquals(JobStatus.CANCELLED, env.jobRepository().getById("bpc-j2").getStatus());
            }
        });
    }

    @Test
    void postBatchSubmitWithInvalidPathsReturnsErrors() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-submit-1");
            installCovPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode/batch-submit",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {
                          "requests": [
                            {"input_path":"/etc/passwd","profile":"h264_fast","format":"dash"},
                            {"input_path":"/etc/shadow","profile":"h264_fast","format":"hls"}
                          ]
                        }
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("errors"));
                assertTrue(body.contains("batch_id"));
            }
        });
    }

    @Test
    void getBatchStatusReturnsFullStatusForExistingBatchWithMixedJobs() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-mixed-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("bm-j1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash", "batch-mix-1", null));
            env.jobRepository().create(job("bm-j2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "dash", "batch-mix-1", null));
            env.jobRepository().updateStatus("bm-j2", JobStatus.PROBING);
            env.jobRepository().updateStatus("bm-j2", JobStatus.TRANSCODING);
            env.jobRepository().updateStatus("bm-j2", JobStatus.COMPLETED);

            try (Response response = app.client().get("/api/v1/transcode/batches/batch-mix-1")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("\"total\":2"));
                assertTrue(body.contains("\"completed\":1"));
                assertTrue(body.contains("\"pending\":1"));
            }
        });
    }

    @Test
    void deleteBatchCancelReturnsResultWithMixedCancelledAndNotFound() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-del-mix-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("bd-j1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash", "del-batch-1", null));
            env.jobRepository().updateStatus("bd-j1", JobStatus.PROBING);
            env.jobRepository().updateStatus("bd-j1", JobStatus.TRANSCODING);
            env.jobRepository().create(job("bd-j2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "dash", "del-batch-1", null));
            env.jobRepository().updateStatus("bd-j2", JobStatus.PROBING);
            env.jobRepository().updateStatus("bd-j2", JobStatus.TRANSCODING);
            env.jobRepository().updateStatus("bd-j2", JobStatus.COMPLETED);

            try (Response response = app.client().delete("/api/v1/transcode/batches/del-batch-1")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("bd-j1"));
                assertTrue(body.contains("bd-j2"));
            }
        });
    }

    @Test
    void postBatchCancelWithMixedExistingAndNonexistentJobs() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-cancel-mix-1");
            installCovPlugins(app, env);

            env.jobRepository().create(job("bc-mix-j1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));
            env.jobRepository().updateStatus("bc-mix-j1", JobStatus.PROBING);
            env.jobRepository().updateStatus("bc-mix-j1", JobStatus.TRANSCODING);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\":[\"bc-mix-j1\",\"nonexistent-xyz\"]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("bc-mix-j1"));
                assertTrue(body.contains("nonexistent-xyz"));
                assertTrue(body.contains("cancelled"));
                assertTrue(body.contains("not_found"));
            }
        });
    }

    @Test
    void postBatchSubmitReturns503WhenFfmpegUnavailable() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createCovEnv("batch-503-1");
            HealthMonitor healthMonitor = () -> false;
            installCovPlugins(app, env, healthMonitor, null, List.of(), null);

            try (Response response = app.client().post(
                "/api/v1/transcode/batch-submit",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"requests\":[{\"input_path\":\"/test.mkv\",\"profile\":\"h264_fast\",\"format\":\"dash\"}]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getServiceUnavailable(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("FFMPEG_UNAVAILABLE"));
            }
        });
    }
}
