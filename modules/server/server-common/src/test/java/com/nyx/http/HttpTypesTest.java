package com.nyx.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpTypesTest {
    @Test
    void knownStatusCodesReuseSharedInstances() {
        assertSame(HttpStatusCode.OK, HttpStatusCode.fromValue(200));
        assertSame(HttpStatusCode.Created, HttpStatusCode.fromValue(201));
        assertSame(HttpStatusCode.Accepted, HttpStatusCode.fromValue(202));
        assertSame(HttpStatusCode.PartialContent, HttpStatusCode.fromValue(206));
        assertSame(HttpStatusCode.NoContent, HttpStatusCode.fromValue(204));
        assertSame(HttpStatusCode.NotModified, HttpStatusCode.fromValue(304));
        assertSame(HttpStatusCode.BadRequest, HttpStatusCode.fromValue(400));
        assertSame(HttpStatusCode.Unauthorized, HttpStatusCode.fromValue(401));
        assertSame(HttpStatusCode.Forbidden, HttpStatusCode.fromValue(403));
        assertSame(HttpStatusCode.NotFound, HttpStatusCode.fromValue(404));
        assertSame(HttpStatusCode.Conflict, HttpStatusCode.fromValue(409));
        assertSame(HttpStatusCode.PayloadTooLarge, HttpStatusCode.fromValue(413));
        assertSame(HttpStatusCode.TooManyRequests, HttpStatusCode.fromValue(429));
        assertSame(HttpStatusCode.InternalServerError, HttpStatusCode.fromValue(500));
        assertSame(HttpStatusCode.ServiceUnavailable, HttpStatusCode.fromValue(503));
    }

    @Test
    void unknownStatusCodesStillMapByNumericEquality() {
        HttpStatusCode teapot = HttpStatusCode.fromValue(418);

        assertEquals(418, teapot.getValue());
        assertEquals(teapot, HttpStatusCode.fromValue(418));
        assertEquals(teapot.hashCode(), HttpStatusCode.fromValue(418).hashCode());
        assertNotEquals(teapot, HttpStatusCode.OK);
        assertTrue(teapot.toString().contains("418"));
    }

    @Test
    void contentTypeMatchingHandlesExactValuesWildcardsAndParameters() {
        assertTrue(ContentType.Application.Json.match(ContentType.parse("application/json; charset=utf-8")));
        assertTrue(ContentType.parse("image/jpeg; q=0.9").match(ContentType.Image.Any));
        assertTrue(ContentType.Audio.Any.match(ContentType.parse("audio/mpeg")));
        assertTrue(ContentType.Video.Any.match(ContentType.parse("video/mp4")));
        assertFalse(ContentType.Text.Html.match(ContentType.Application.Json));
    }

    @Test
    void contentTypeConstructorsHeadersParametersAndPrincipalRemainStable() {
        ContentType custom = new ContentType("application", "x-ndjson");
        ContentType parsed = ContentType.parse("text/plain");
        Parameters parameters = new Parameters(name -> "foo".equals(name) ? "bar" : null);
        UserIdPrincipal principal = new UserIdPrincipal("alice");

        assertEquals("application/x-ndjson", custom.getValue());
        assertEquals("application/x-ndjson", custom.toString());
        assertEquals(ContentType.Text.Plain, parsed);
        assertEquals(ContentType.Text.Plain.hashCode(), parsed.hashCode());
        assertEquals("bar", parameters.get("foo"));
        assertNull(parameters.get("missing"));
        assertEquals("alice", principal.getName());

        assertEquals("Accept", HttpHeaders.Accept);
        assertEquals("Accept-Encoding", HttpHeaders.AcceptEncoding);
        assertEquals("Access-Control-Request-Method", HttpHeaders.AccessControlRequestMethod);
        assertEquals("Accept-Ranges", HttpHeaders.AcceptRanges);
        assertEquals("Authorization", HttpHeaders.Authorization);
        assertEquals("Cache-Control", HttpHeaders.CacheControl);
        assertEquals("Content-Encoding", HttpHeaders.ContentEncoding);
        assertEquals("Content-Disposition", HttpHeaders.ContentDisposition);
        assertEquals("Content-Length", HttpHeaders.ContentLength);
        assertEquals("Content-Range", HttpHeaders.ContentRange);
        assertEquals("Content-Type", HttpHeaders.ContentType);
        assertEquals("ETag", HttpHeaders.ETag);
        assertEquals("If-None-Match", HttpHeaders.IfNoneMatch);
        assertEquals("Location", HttpHeaders.Location);
        assertEquals("Origin", HttpHeaders.Origin);
        assertEquals("Range", HttpHeaders.Range);
        assertEquals("Retry-After", HttpHeaders.RetryAfter);
        assertEquals("X-Request-ID", HttpHeaders.XRequestId);

        assertEquals("application/xml", ContentType.Application.Xml.getValue());
        assertEquals("application/zip", ContentType.Application.Zip.getValue());
        assertEquals("application/octet-stream", ContentType.Application.OctetStream.getValue());
        assertEquals("text/html", ContentType.Text.Html.getValue());
        assertEquals("image/jpeg", ContentType.Image.JPEG.getValue());
    }
}
