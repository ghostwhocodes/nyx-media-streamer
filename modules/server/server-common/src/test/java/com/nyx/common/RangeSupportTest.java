package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RangeSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void eTagIsDeterministicForSameFile() throws Exception {
        Path file = createFile("test.txt", "hello world");

        String etag1 = RangeSupport.generateETag(file);
        String etag2 = RangeSupport.generateETag(file);

        assertEquals(etag1, etag2);
        assertTrue(etag1.startsWith("\""));
        assertTrue(etag1.endsWith("\""));
    }

    @Test
    void eTagChangesWhenFileContentChanges() throws Exception {
        Path file = createFile("test.txt", "hello");

        String etag1 = RangeSupport.generateETag(file);

        Thread.sleep(50);
        Files.writeString(file, "hello world expanded");

        String etag2 = RangeSupport.generateETag(file);
        assertNotEquals(etag1, etag2);
    }

    @Test
    void eTagContainsSizeAndMtime() throws Exception {
        Path file = createFile("test.txt", "12345");

        String etag = RangeSupport.generateETag(file);
        String inner = etag.substring(1, etag.length() - 1);
        String[] parts = inner.split("-");

        assertEquals(2, parts.length);
        assertEquals("5", parts[0]);
        assertTrue(Long.parseLong(parts[1]) > 0);
    }

    @Test
    void isCacheValidReturnsTrueWhenFileUnchanged() throws Exception {
        Path file = createFile("test.txt", "content");
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();

        assertTrue(RangeSupport.isCacheValid(file, size, mtime));
    }

    @Test
    void isCacheValidReturnsFalseWhenFileChanged() throws Exception {
        Path file = createFile("test.txt", "original");
        long size = Files.size(file);
        long mtime = Files.getLastModifiedTime(file).toMillis();

        Thread.sleep(50);
        Files.writeString(file, "modified content that is different");

        assertFalse(RangeSupport.isCacheValid(file, size, mtime));
    }

    @Test
    void isCacheValidReturnsFalseForNonExistentFile() {
        Path file = tempDir.resolve("missing.txt");

        assertFalse(RangeSupport.isCacheValid(file, 100, 12_345));
    }

    @Test
    void respondFileServesFileWithCorrectHeaders() throws Exception {
        Path file = createFile("image.jpg", "fake image content");

        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test", scope -> {
                RangeSupport.respondFile(scope.getCall(), file, MediaTypes.IMAGE_JPEG);
            }));

            try (Response response = app.client().get("/test")) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(response));
                assertEquals("image/jpeg", ServerCommonTestSupport.contentType(response));
                assertNotNull(response.header(HttpHeaders.ETag));
                assertEquals("bytes", response.header(HttpHeaders.AcceptRanges));
                assertTrue(response.header(HttpHeaders.CacheControl).contains("max-age=3600"));
                assertEquals("fake image content", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void respondFileReturns304ForMatchingETag() throws Exception {
        Path file = createFile("cached.jpg", "cached content");
        String etag = RangeSupport.generateETag(file);

        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test", scope -> {
                RangeSupport.respondFile(scope.getCall(), file, MediaTypes.IMAGE_JPEG);
            }));

            try (Response response = app.client().get("/test", request -> request.header(HttpHeaders.IfNoneMatch, etag))) {
                assertEquals(HttpStatusCode.NotModified, ServerCommonTestSupport.status(response));
            }
        });
    }

    @Test
    void rangeRequestReturns206PartialContent() throws Exception {
        Path file = createFile("large.bin", "0123456789abcdef");

        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test", scope -> {
                RangeSupport.respondFile(scope.getCall(), file, MediaTypes.APPLICATION_OCTET_STREAM);
            }));

            try (Response response = app.client().get("/test", request -> request.header(HttpHeaders.Range, "bytes=0-4"))) {
                assertEquals(HttpStatusCode.PartialContent, ServerCommonTestSupport.status(response));
                assertEquals("01234", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void suffixByteRangeReturnsTheTrailingBytes() throws Exception {
        Path file = createFile("suffix.bin", "0123456789abcdef");

        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test", scope -> {
                RangeSupport.respondFile(scope.getCall(), file, MediaTypes.APPLICATION_OCTET_STREAM);
            }));

            try (Response response = app.client().get("/test", request -> request.header(HttpHeaders.Range, "bytes=-4"))) {
                assertEquals(HttpStatusCode.PartialContent, ServerCommonTestSupport.status(response));
                assertEquals("cdef", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void openEndedByteRangeReturnsFromStartToEndOfFile() throws Exception {
        Path file = createFile("open-ended.bin", "0123456789abcdef");

        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test", scope -> {
                RangeSupport.respondFile(scope.getCall(), file, MediaTypes.APPLICATION_OCTET_STREAM);
            }));

            try (Response response = app.client().get("/test", request -> request.header(HttpHeaders.Range, "bytes=4-"))) {
                assertEquals(HttpStatusCode.PartialContent, ServerCommonTestSupport.status(response));
                assertEquals("456789abcdef", ServerCommonTestSupport.bodyAsText(response));
            }
        });
    }

    @Test
    void unsupportedOrMultiRangeHeadersFallBackToANormalResponse() throws Exception {
        Path file = createFile("invalid.bin", "0123456789abcdef");

        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test", scope -> {
                RangeSupport.respondFile(scope.getCall(), file, MediaTypes.APPLICATION_OCTET_STREAM);
            }));

            try (Response unsupportedUnit = app.client().get("/test", request -> request.header(HttpHeaders.Range, "items=0-4"))) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(unsupportedUnit));
                assertEquals("0123456789abcdef", ServerCommonTestSupport.bodyAsText(unsupportedUnit));
            }

            try (Response multiple = app.client().get("/test", request -> request.header(HttpHeaders.Range, "bytes=0-1,4-5"))) {
                assertEquals(HttpStatusCode.OK, ServerCommonTestSupport.status(multiple));
                assertEquals("0123456789abcdef", ServerCommonTestSupport.bodyAsText(multiple));
            }
        });
    }

    @Test
    void respondFileThrowsForNonExistentFile() throws Exception {
        Path file = tempDir.resolve("missing.jpg");

        ServerCommonTestSupport.testApplication(app -> {
            app.routing(route -> route.get("/test", scope -> {
                RangeSupport.respondFile(scope.getCall(), file, MediaTypes.IMAGE_JPEG);
            }));

            try (Response response = app.client().get("/test")) {
                assertEquals(HttpStatusCode.NotFound, ServerCommonTestSupport.status(response));
            }
        });
    }

    private Path createFile(String name, String content) throws Exception {
        Path file = tempDir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
