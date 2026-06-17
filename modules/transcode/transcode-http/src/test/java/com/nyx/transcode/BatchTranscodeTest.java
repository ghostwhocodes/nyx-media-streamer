package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.HealthMonitor;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.transcode.contracts.JobStatus;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class BatchTranscodeTest extends AbstractTranscodeRoutesTestSupport {
    @Test
    void batchSubmitWithTwoValidPathsReturns200WithBatchId() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-submit-two-valid");
            installEnvPlugins(app, env);

            var file1 = java.nio.file.Files.createFile(mediaDir.resolve("a.mkv"));
            var file2 = java.nio.file.Files.createFile(mediaDir.resolve("b.mkv"));

            try (Response response = app.client().post(
                "/api/v1/transcode/batch-submit",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"requests":[
                          {"input_path":"%s","profile":"h264_fast","format":"dash"},
                          {"input_path":"%s","profile":"h264_fast","format":"dash"}
                        ]}
                        """.formatted(file1, file2));
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("batch_id"));
                assertTrue(body.contains("jobs"));
                assertTrue(body.contains("errors"));
                assertTrue(body.contains("\"batch_id\""));
            }
        });
    }

    @Test
    void batchSubmitWithEmptyRequestsReturns200WithEmptyJobs() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-submit-empty");
            installEnvPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode/batch-submit",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("{\"requests\":[]}");
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("batch_id"));
                assertTrue(body.contains("\"jobs\":[]") || body.contains("\"jobs\" : []"));
            }
        });
    }

    @Test
    void batchSubmitWithPathOutsideMediaRootReturns200WithError() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-submit-outside-root");
            installEnvPlugins(app, env);

            try (Response response = app.client().post(
                "/api/v1/transcode/batch-submit",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"requests":[
                          {"input_path":"/etc/passwd","profile":"h264_fast","format":"dash"}
                        ]}
                        """);
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("errors"));
                assertTrue(body.contains("PATH_VIOLATION") || body.contains("error"));
                assertTrue(body.contains("\"jobs\":[]") || body.contains("\"jobs\" : []"));
            }
        });
    }

    @Test
    void batchSubmitWithDirectFileFormatReturnsItemErrorWithoutCreatingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-submit-direct-file");
            installEnvPlugins(app, env);

            var file = java.nio.file.Files.createFile(mediaDir.resolve("direct-file.mkv"));

            try (Response response = app.client().post(
                "/api/v1/transcode/batch-submit",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"requests":[
                          {"input_path":"%s","profile":"h264_fast","format":"direct-file"}
                        ]}
                        """.formatted(file));
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("\"jobs\":[]") || body.contains("\"jobs\" : []"));
                assertTrue(body.contains("INVALID_REQUEST"));
                assertEquals(0, env.jobRepository().countAll(null));
            }
        });
    }

    @Test
    void batchSubmitWithHlsMpegTsAndMultipleRepresentationsReturnsItemErrorWithoutCreatingJob() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-submit-hls-ts-multi-representation");
            installEnvPlugins(app, env);

            var file = java.nio.file.Files.createFile(mediaDir.resolve("hls-ts-multi.mkv"));

            try (Response response = app.client().post(
                "/api/v1/transcode/batch-submit",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"requests":[
                          {
                            "input_path":"%s",
                            "profile":"h264_fast",
                            "format":"hls_ts",
                            "representations":[
                              {"width":1920,"height":1080,"bitrateKbps":6000},
                              {"width":1280,"height":720,"bitrateKbps":3000}
                            ]
                          }
                        ]}
                        """.formatted(file));
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("\"jobs\":[]") || body.contains("\"jobs\" : []"));
                assertTrue(body.contains("INVALID_REQUEST"));
                assertEquals(0, env.jobRepository().countAll(null));
            }
        });
    }

    @Test
    void batchSubmitWhenFfmpegUnavailableReturns503() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-submit-ffmpeg-down");
            HealthMonitor healthMonitor = () -> false;
            installCovPlugins(app, env, healthMonitor, null, java.util.List.of(), null);

            try (Response response = app.client().post(
                "/api/v1/transcode/batch-submit",
                request -> {
                    request.contentType(ContentType.Application.Json);
                    request.setBody("""
                        {"requests":[{"input_path":"%s/a.mkv","profile":"h264_fast","format":"dash"}]}
                        """.formatted(mediaDir));
                }
            )) {
                assertEquals(HttpStatusCode.Companion.getServiceUnavailable(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void getBatchStatusForKnownBatchReturns200WithAggregateCounts() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-status-known");
            installEnvPlugins(app, env);

            String batchId = "test-b1";
            env.jobRepository().create(job("bs-1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash", batchId, null));
            env.jobRepository().create(job("bs-2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "dash", batchId, null));

            try (Response response = app.client().get("/api/v1/transcode/batches/" + batchId)) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("\"total\":2"));
                assertTrue(body.contains("\"pending\":2"));
                assertTrue(body.contains("\"running\":0") || body.contains("\"running\" : 0"));
                assertTrue(body.contains("\"completed\":0") || body.contains("\"completed\" : 0"));
                assertTrue(body.contains("\"failed\":0") || body.contains("\"failed\" : 0"));
                assertTrue(body.contains("\"cancelled\":0") || body.contains("\"cancelled\" : 0"));
            }
        });
    }

    @Test
    void getBatchStatusForUnknownBatchIdReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-status-missing");
            installEnvPlugins(app, env);

            try (Response response = app.client().get("/api/v1/transcode/batches/nonexistent-batch")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }

    @Test
    void deleteBatchCancelForKnownBatchWithQueuedJobsReturnsCancelledList() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-delete-known");
            installEnvPlugins(app, env);

            String batchId = "del-b1";
            env.jobRepository().create(job("dc-1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash", batchId, null));
            env.jobRepository().create(job("dc-2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "dash", batchId, null));

            try (Response response = app.client().delete("/api/v1/transcode/batches/" + batchId)) {
                assertEquals(HttpStatusCode.Companion.getOK(), TranscodeHttpTestSupport.status(response));
                String body = TranscodeHttpTestSupport.bodyAsText(response);
                assertTrue(body.contains("dc-1"));
                assertTrue(body.contains("dc-2"));
                assertTrue(body.contains("cancelled"));
            }
        });
    }

    @Test
    void deleteBatchCancelForUnknownBatchIdReturns404() throws Exception {
        TranscodeHttpTestSupport.testApplication(app -> {
            TestEnv env = createEnv("batch-delete-missing");
            installEnvPlugins(app, env);

            try (Response response = app.client().delete("/api/v1/transcode/batches/nonexistent-batch")) {
                assertEquals(HttpStatusCode.Companion.getNotFound(), TranscodeHttpTestSupport.status(response));
            }
        });
    }
}
