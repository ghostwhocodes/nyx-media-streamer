package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.json.NyxJson;
import com.nyx.transcode.contracts.JobStatus;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class ErrorResponsesTest {
    private final ObjectMapper objectMapper = NyxJson.newMapper();

    @Test
    void nyxExceptionIsHandledWithCorrectStatusCode() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-nyx", scope -> {
                throw sneakyThrow(new NyxException(ErrorCode.FILE_NOT_FOUND, "File missing"));
            }));

            try (Response response = app.client().get("/test-nyx")) {
                assertEquals(HttpStatusCode.NotFound, ServerCommonTestSupport.status(response));
                String body = ServerCommonTestSupport.bodyAsText(response);
                assertTrue(body.contains("FILE_NOT_FOUND"));
                assertTrue(body.contains("File missing"));
            }
        });
    }

    @Test
    void nyxExceptionWithDetailsIncludesDetailsInResponse() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-details", scope -> {
                throw sneakyThrow(new NyxException(
                    ErrorCode.INVALID_REQUEST,
                    "Bad param",
                    java.util.Map.of("param", "value")
                ));
            }));

            try (Response response = app.client().get("/test-details")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void pathNotAllowedExceptionIsHandledAsForbidden() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-path", scope -> {
                throw sneakyThrow(new PathNotAllowedException("/etc/passwd"));
            }));

            try (Response response = app.client().get("/test-path")) {
                assertEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("PATH_NOT_ALLOWED"));
            }
        });
    }

    @Test
    void pathNotFoundExceptionIsHandledAsNotFound() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-path-not-found", scope -> {
                throw sneakyThrow(new PathNotFoundException("/missing/file.mkv"));
            }));

            try (Response response = app.client().get("/test-path-not-found")) {
                assertEquals(HttpStatusCode.NotFound, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("FILE_NOT_FOUND"));
            }
        });
    }

    @Test
    void virtualRootNotFoundExceptionIsHandledAsNotFound() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-virtual-root", scope -> {
                throw sneakyThrow(new VirtualRootNotFoundException("missing-root"));
            }));

            try (Response response = app.client().get("/test-virtual-root")) {
                assertEquals(HttpStatusCode.NotFound, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("VIRTUAL_ROOT_NOT_FOUND"));
            }
        });
    }

    @Test
    void invalidPathRequestExceptionIsHandledAsBadRequest() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-invalid-path", scope -> {
                throw sneakyThrow(new InvalidPathRequestException("Empty virtual path"));
            }));

            try (Response response = app.client().get("/test-invalid-path")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void pathNotDirectoryExceptionIsHandledAsBadRequest() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-not-directory", scope -> {
                throw sneakyThrow(new PathNotDirectoryException("/tmp/file.txt"));
            }));

            try (Response response = app.client().get("/test-not-directory")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void invalidJobTransitionExceptionIsHandledAsConflict() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-job-transition", scope -> {
                throw sneakyThrow(new InvalidJobTransitionException(JobStatus.QUEUED, JobStatus.TRANSCODING));
            }));

            try (Response response = app.client().get("/test-job-transition")) {
                assertEquals(HttpStatusCode.Conflict, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("INVALID_JOB_TRANSITION"));
            }
        });
    }

    @Test
    void illegalArgumentExceptionIsHandledAsBadRequest() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-illegal", scope -> {
                throw new IllegalArgumentException("Invalid argument");
            }));

            try (Response response = app.client().get("/test-illegal")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                String body = ServerCommonTestSupport.bodyAsText(response);
                assertTrue(body.contains("INVALID_REQUEST"));
                assertTrue(body.contains("Invalid argument"));
            }
        });
    }

    @Test
    void illegalArgumentExceptionWithNullMessageUsesDefaultText() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-null-illegal", scope -> {
                throw new IllegalArgumentException((String) null);
            }));

            try (Response response = app.client().get("/test-null-illegal")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("Invalid request"));
            }
        });
    }

    @Test
    void quotaExceededSetsRetryAfterHeader() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-quota", scope -> {
                throw sneakyThrow(new NyxException(ErrorCode.QUOTA_EXCEEDED, "Too many requests"));
            }));

            try (Response response = app.client().get("/test-quota")) {
                assertEquals(HttpStatusCode.TooManyRequests, ServerCommonTestSupport.status(response));
                assertEquals("60", response.header(HttpHeaders.RetryAfter));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("QUOTA_EXCEEDED"));
            }
        });
    }

    @Test
    void allErrorCodeValuesHaveCorrectHttpStatus() {
        assertEquals(HttpStatusCode.NotFound, ErrorCode.FILE_NOT_FOUND.getHttpStatus());
        assertEquals(HttpStatusCode.Forbidden, ErrorCode.PATH_NOT_ALLOWED.getHttpStatus());
        assertEquals(HttpStatusCode.BadRequest, ErrorCode.INVALID_REQUEST.getHttpStatus());
        assertEquals(HttpStatusCode.BadRequest, ErrorCode.CODEC_UNSUPPORTED.getHttpStatus());
        assertEquals(HttpStatusCode.InternalServerError, ErrorCode.TRANSCODE_FAILED.getHttpStatus());
        assertEquals(HttpStatusCode.NotFound, ErrorCode.JOB_NOT_FOUND.getHttpStatus());
        assertEquals(HttpStatusCode.ServiceUnavailable, ErrorCode.QUEUE_FULL.getHttpStatus());
        assertEquals(HttpStatusCode.InternalServerError, ErrorCode.PROBE_FAILED.getHttpStatus());
        assertEquals(HttpStatusCode.NotFound, ErrorCode.FORM_NOT_FOUND.getHttpStatus());
        assertEquals(HttpStatusCode.BadRequest, ErrorCode.VALIDATION_ERROR.getHttpStatus());
        assertEquals(HttpStatusCode.NotFound, ErrorCode.METADATA_NOT_FOUND.getHttpStatus());
        assertEquals(HttpStatusCode.Conflict, ErrorCode.SCHEMA_CONFLICT.getHttpStatus());
        assertEquals(HttpStatusCode.BadRequest, ErrorCode.IMPORT_ERROR.getHttpStatus());
        assertEquals(HttpStatusCode.InternalServerError, ErrorCode.EXPORT_ERROR.getHttpStatus());
        assertEquals(HttpStatusCode.NotFound, ErrorCode.IMAGE_NOT_FOUND.getHttpStatus());
        assertEquals(HttpStatusCode.NotFound, ErrorCode.AUDIO_NOT_FOUND.getHttpStatus());
        assertEquals(HttpStatusCode.NotFound, ErrorCode.PLAYLIST_NOT_FOUND.getHttpStatus());
        assertEquals(HttpStatusCode.BadRequest, ErrorCode.INVALID_THUMBNAIL_SIZE.getHttpStatus());
        assertEquals(HttpStatusCode.InternalServerError, ErrorCode.TRANSCODE_ERROR.getHttpStatus());
    }

    @Test
    void errorDetailWithEmptyDetailsMap() {
        ErrorDetail detail = new ErrorDetail("TEST", "Test error");

        assertEquals("TEST", detail.code());
        assertEquals("Test error", detail.message());
        assertTrue(detail.details().isEmpty());
    }

    @Test
    void errorDetailWithPopulatedDetailsMap() {
        ErrorDetail detail = new ErrorDetail("TEST", "msg", java.util.Map.of("key", "value"));

        assertEquals("value", detail.details().get("key"));
    }

    @Test
    void errorResponseWrapsErrorDetail() {
        ErrorResponse response = new ErrorResponse(new ErrorDetail("CODE", "message"));

        assertEquals("CODE", response.error().code());
        assertEquals("message", response.error().message());
    }

    @Test
    void nyxExceptionHasCorrectMessageAndCode() {
        NyxException exception = new NyxException(ErrorCode.QUEUE_FULL, "Queue is full");

        assertEquals(ErrorCode.QUEUE_FULL, exception.getErrorCode());
        assertEquals("Queue is full", exception.getMessage());
        assertTrue(exception.getDetails().isEmpty());
    }

    @Test
    void statusPagesHandlesNyxExceptionFromImprovements() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> {
                route.get("/test-nyx", scope -> {
                    throw sneakyThrow(new NyxException(
                        ErrorCode.FILE_NOT_FOUND,
                        "Test file not found",
                        java.util.Map.of("path", "/test")
                    ));
                });
                route.get("/test-path", scope -> {
                    throw sneakyThrow(new PathNotAllowedException("/forbidden/path"));
                });
                route.get("/test-arg", scope -> {
                    throw new IllegalArgumentException("bad argument");
                });
            });

            try (Response resp1 = app.client().get("/test-nyx")) {
                assertEquals(HttpStatusCode.NotFound, ServerCommonTestSupport.status(resp1));
                assertTrue(ServerCommonTestSupport.bodyAsText(resp1).contains("FILE_NOT_FOUND"));
            }

            try (Response resp2 = app.client().get("/test-path")) {
                assertEquals(HttpStatusCode.Forbidden, ServerCommonTestSupport.status(resp2));
                assertTrue(ServerCommonTestSupport.bodyAsText(resp2).contains("PATH_NOT_ALLOWED"));
            }

            try (Response resp3 = app.client().get("/test-arg")) {
                assertEquals(HttpStatusCode.BadRequest, ServerCommonTestSupport.status(resp3));
                assertTrue(ServerCommonTestSupport.bodyAsText(resp3).contains("INVALID_REQUEST"));
            }
        });
    }

    @Test
    void errorCodeTranscodeErrorHasCorrectStatus() {
        assertEquals(HttpStatusCode.InternalServerError, ErrorCode.TRANSCODE_ERROR.getHttpStatus());
    }

    @Test
    void errorCodeInvalidThumbnailSizeHasCorrectStatus() {
        assertEquals(HttpStatusCode.BadRequest, ErrorCode.INVALID_THUMBNAIL_SIZE.getHttpStatus());
    }

    @Test
    void nyxExceptionCarriesErrorDetails() {
        NyxException exception = new NyxException(
            ErrorCode.PROBE_FAILED,
            "Failed to probe",
            java.util.Map.of("path", "/test/file.mp4")
        );

        assertEquals(ErrorCode.PROBE_FAILED, exception.getErrorCode());
        assertEquals("Failed to probe", exception.getMessage());
        assertEquals("/test/file.mp4", exception.getDetails().get("path"));
    }

    @Test
    void errorCodeInvalidJobTransitionHasConflictStatus() {
        assertEquals(HttpStatusCode.Conflict, ErrorCode.INVALID_JOB_TRANSITION.getHttpStatus());
    }

    @Test
    void errorCodeRateLimitedHasTooManyRequestsStatus() {
        assertEquals(HttpStatusCode.TooManyRequests, ErrorCode.RATE_LIMITED.getHttpStatus());
    }

    @Test
    void errorCodeRequestTooLargeHasPayloadTooLargeStatus() {
        assertEquals(HttpStatusCode.PayloadTooLarge, ErrorCode.REQUEST_TOO_LARGE.getHttpStatus());
    }

    @Test
    void statusPagesHandlesInvalidJobTransitionExceptionWith409Conflict() throws Exception {
        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test-invalid-transition", scope -> {
                throw sneakyThrow(new InvalidJobTransitionException(JobStatus.QUEUED, JobStatus.COMPLETED));
            }));

            try (Response response = app.client().get("/test-invalid-transition")) {
                assertEquals(HttpStatusCode.Conflict, ServerCommonTestSupport.status(response));
                assertTrue(ServerCommonTestSupport.bodyAsText(response).contains("INVALID_JOB_TRANSITION"));
            }
        });
    }

    @Test
    void errorDetailSerializesWithJackson() throws Exception {
        ErrorDetail errorDetail = new ErrorDetail("RATE_LIMITED", "Too many requests", java.util.Map.of("limit", "60"));
        String jsonPayload = objectMapper.writeValueAsString(errorDetail);

        assertTrue(jsonPayload.contains("\"code\":\"RATE_LIMITED\""));
        assertEquals(errorDetail, objectMapper.readValue(jsonPayload, ErrorDetail.class));
    }

    private static RuntimeException sneakyThrow(Throwable throwable) {
        ErrorResponsesTest.<RuntimeException>throwUnchecked(throwable);
        throw new AssertionError("Unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
