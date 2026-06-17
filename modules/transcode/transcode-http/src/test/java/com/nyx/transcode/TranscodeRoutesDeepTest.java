package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.contracts.JobStatus;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class TranscodeRoutesDeepTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void getJobsReturnsEmptyListingWhenNoJobsExist() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("list-empty.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("\"jobs\":[]"));
                assertTrue(body.contains("\"total\":0"));
            }
        });
    }

    @Test
    void getJobsReturnsListWithExistingJobs() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("list-with-data.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("list-j1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));
            env.jobRepository().create(job("list-j2", JobStatus.COMPLETED, "/b.mkv", "h264_fast", "hls"));

            try (Response response = app.client().get("/api/v1/transcode/jobs")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("list-j1"));
                assertTrue(body.contains("list-j2"));
            }
        });
    }

    @Test
    void getJobDetailReturnsExistingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("detail-ok.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("detail-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/detail-j1")) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("detail-j1"));
                assertTrue(body.toUpperCase().contains("QUEUED"));
            }
        });
    }

    @Test
    void getJobDetailReturns404ForNonexistentJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("detail-missing.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/no-such-job")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("JOB_NOT_FOUND"));
            }
        });
    }

    @Test
    void getSegmentWithSlashInNameReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-slash.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/anyjob/segments/sub%2Ffile.m4s")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("Invalid segment name"));
            }
        });
    }

    @Test
    void getSegmentWithDotDotInNameReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-dotdot.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/anyjob/segments/..%2F..%2Fetc%2Fpasswd")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getSegmentForJobWithNoOutputDirReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seg-no-dir.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("no-dir-job", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().get("/api/v1/transcode/jobs/no-dir-job/segments/init.mp4")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("JOB_NOT_FOUND"));
            }
        });
    }

    @Test
    void getProbeWithMissingPathParameterReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("probe-missing.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/media/probe")) {
                assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
                assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("Missing path"));
            }
        });
    }

    @Test
    void getProbeWithPathOutsideMediaRootReturns403() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("probe-forbidden.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/media/probe?path=/etc/passwd")) {
                assertEquals(HttpStatusCode.Companion.getForbidden(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postTranscodeWithMissingContentTypeReturns400Or415() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("transcode-no-body.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post("/api/v1/transcode")) {
                int statusCode = response.code();
                assertTrue(statusCode == 400 || statusCode == 415);
            }
        });
    }

    @Test
    void postTranscodeWithInvalidJsonReturns400() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("transcode-bad-json.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{not valid json");
                }
            )) {
                int statusCode = response.code();
                assertTrue(statusCode == 400 || statusCode == 500);
            }
        });
    }

    @Test
    void getLogsForNonexistentJobReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("logs-missing.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/jobs/nonexistent/logs")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void deleteNonexistentJobReturnsNoContent() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("delete-noexist.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().delete("/api/v1/transcode/jobs/ghost-job")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void deleteExistingQueuedJobReturnsNoContent() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("delete-existing.db");
            installEnvPlugins(app, env);

            env.jobRepository().create(job("del-existing", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

            try (Response response = app.client().delete("/api/v1/transcode/jobs/del-existing")) {
                assertEquals(HttpStatusCode.Companion.getNoContent(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void postSeekWithMissingBodyReturnsError() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("seek-no-body.db");
            installEnvPlugins(app, env);

            try (Response response = app.client().post("/api/v1/transcode/jobs/some-job/seek")) {
                int statusCode = response.code();
                assertTrue(statusCode == 400 || statusCode == 415);
            }
        });
    }
}
