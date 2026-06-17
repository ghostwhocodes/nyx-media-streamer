package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.contracts.JobStatus;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class BatchCancelTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void batchCancelWithValidJobsReturnsCancelledList() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-valid.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("bc-1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));
            env.jobRepository().create(job("bc-2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "dash"));

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\": [\"bc-1\", \"bc-2\"]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("bc-1"));
                assertTrue(body.contains("bc-2"));
                assertTrue(body.contains("cancelled"));
            }
        });
    }

    @Test
    void batchCancelWithInvalidIdsReturnsNotFoundList() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-invalid.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\": [\"nonexistent-1\", \"nonexistent-2\"]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("nonexistent-1"));
                assertTrue(body.contains("nonexistent-2"));
                assertTrue(body.contains("not_found"));
            }
        });
    }

    @Test
    void batchCancelWithMixOfValidAndInvalidIds() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-mixed.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("bc-valid", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\": [\"bc-valid\", \"bc-ghost\"]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("bc-valid"));
                assertTrue(body.contains("bc-ghost"));
                assertTrue(body.contains("cancelled"));
                assertTrue(body.contains("not_found"));
            }
        });
    }

    @Test
    void batchCancelWithEmptyListReturnsBothEmpty() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-empty.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\": []}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("\"cancelled\":[]") || body.contains("\"cancelled\" : []"));
                assertTrue(body.contains("\"not_found\":[]") || body.contains("\"not_found\" : []"));
            }
        });
    }

    @Test
    void batchCancelOnAlreadyTerminalJobStillReportsAsCancelled() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-terminal.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("bc-failed", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));
            env.jobRepository().updateStatus("bc-failed", JobStatus.PROBING);
            env.jobRepository().updateStatus("bc-failed", JobStatus.FAILED);

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\": [\"bc-failed\"]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("bc-failed"));
                assertTrue(body.contains("cancelled"));
            }
        });
    }

    @Test
    void batchCancelWithSingleJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-single.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("bc-single", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));

            try (Response response = app.client().post(
                "/api/v1/transcode/jobs/batch-cancel",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"job_ids\": [\"bc-single\"]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("bc-single"));
                assertTrue(body.contains("cancelled"));
            }
        });
    }

    @Test
    void getJobsWithPaginationParamsReturnsPaginatedResponse() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("route-pag.db");
            installEnvPlugins(app, env);

            for (int index = 1; index <= 5; index += 1) {
                env.jobRepository().create(job("rp-" + index, JobStatus.QUEUED, "/file" + index + ".mkv", "h264_fast", "dash"));
            }

            try (Response response = app.client().get("/api/v1/transcode/jobs?page=1&limit=2")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("\"total\":5"));
                assertTrue(body.contains("\"page\":1"));
                assertTrue(body.contains("\"limit\":2"));
            }
        });
    }

    @Test
    void getJobsWithInvalidPageReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("route-bad-page.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs?page=0")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getJobsWithInvalidLimitReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("route-bad-limit.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs?limit=999")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }
}
