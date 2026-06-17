package com.nyx.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.javalin.http.HttpStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HttpValueCoverageTest {

    @Test
    void openApiValueObjectsRemainValueBasedAndDefensivelyCopied() {
        OpenApiParameter minimalParameter = new OpenApiParameter("id", "path");
        OpenApiParameter optionalParameter = new OpenApiParameter("sort", "query", "Sort");
        OpenApiParameter describedParameter = new OpenApiParameter("limit", "query", "Limit", true);
        OpenApiResponse minimalResponse = new OpenApiResponse(200);
        OpenApiResponse describedResponse = new OpenApiResponse(404, "Missing");
        List<OpenApiParameter> parameters = new ArrayList<>(List.of(describedParameter));
        List<OpenApiResponse> responses = new ArrayList<>(List.of(describedResponse));
        OpenApiOperation minimalOperation = new OpenApiOperation("GET", "/items/{id}");
        OpenApiOperation detailedOperation = new OpenApiOperation(
            "POST",
            "/items",
            "Create item",
            parameters,
            true,
            responses
        );

        parameters.add(minimalParameter);
        responses.add(minimalResponse);

        assertEquals("id", minimalParameter.getName());
        assertEquals("path", minimalParameter.getIn());
        assertNull(minimalParameter.getDescription());
        assertFalse(minimalParameter.getRequired());
        assertEquals("Sort", optionalParameter.getDescription());
        assertFalse(optionalParameter.getRequired());
        assertEquals(optionalParameter.hashCode(), new OpenApiParameter("sort", "query", "Sort").hashCode());
        assertFalse(optionalParameter.equals("sort"));
        assertEquals("OpenApiParameter(name=limit, in=query, description=Limit, required=true)", describedParameter.toString());
        assertEquals(describedParameter, new OpenApiParameter("limit", "query", "Limit", true));

        assertEquals(200, minimalResponse.getCode());
        assertNull(minimalResponse.getDescription());
        assertEquals(describedResponse.hashCode(), new OpenApiResponse(404, "Missing").hashCode());
        assertFalse(describedResponse.equals("Missing"));
        assertEquals("OpenApiResponse(code=404, description=Missing)", describedResponse.toString());
        assertEquals(describedResponse, new OpenApiResponse(404, "Missing"));

        assertEquals("GET", minimalOperation.getMethod());
        assertEquals("/items/{id}", minimalOperation.getPath());
        assertNull(minimalOperation.getDescription());
        assertTrue(minimalOperation.getParameters().isEmpty());
        assertFalse(minimalOperation.getHasRequestBody());
        assertTrue(minimalOperation.getResponses().isEmpty());

        assertEquals("Create item", detailedOperation.getDescription());
        assertEquals(List.of(describedParameter), detailedOperation.getParameters());
        assertTrue(detailedOperation.getHasRequestBody());
        assertEquals(List.of(describedResponse), detailedOperation.getResponses());
        assertEquals(
            detailedOperation,
            new OpenApiOperation("POST", "/items", "Create item", List.of(describedParameter), true, List.of(describedResponse))
        );
        assertEquals(
            detailedOperation.hashCode(),
            new OpenApiOperation("POST", "/items", "Create item", List.of(describedParameter), true, List.of(describedResponse)).hashCode()
        );
        assertFalse(detailedOperation.equals("operation"));
        assertTrue(detailedOperation.toString().contains("Create item"));
    }

    @Test
    void requestDocsAndHttpHelpersPreserveHistoricalSemantics() {
        RequestDoc doc = new RequestDoc();

        doc.body(String.class);
        doc.body(Integer.class, ignored -> {
        });
        doc.queryParameter("q");
        doc.queryParameter("limit", parameterDoc -> {
            parameterDoc.setDescription("Limit");
            parameterDoc.setRequired(true);
        });
        doc.pathParameter("id");
        doc.headerParameter("x-api-key", parameterDoc -> {
            parameterDoc.setDescription("API key");
        });

        assertTrue(doc.hasRequestBody());
        assertEquals(4, doc.getParameters().size());
        assertEquals("q", doc.getParameters().get(0).getName());
        assertEquals("query", doc.getParameters().get(0).getIn());
        assertEquals("Limit", doc.getParameters().get(1).getDescription());
        assertTrue(doc.getParameters().get(1).getRequired());
        assertEquals("path", doc.getParameters().get(2).getIn());
        assertTrue(doc.getParameters().get(2).getRequired());
        assertEquals("API key", doc.getParameters().get(3).getDescription());
        assertFalse(doc.getParameters().get(3).getRequired());

        ServerSentEvent emptyEvent = new ServerSentEvent();
        ServerSentEvent dataOnlyEvent = new ServerSentEvent("payload");
        ServerSentEvent namedEvent = new ServerSentEvent("payload", "update");

        assertNull(emptyEvent.getData());
        assertNull(emptyEvent.getEvent());
        assertEquals("payload", dataOnlyEvent.getData());
        assertNull(dataOnlyEvent.getEvent());
        assertEquals(namedEvent, new ServerSentEvent("payload", "update"));
        assertEquals(namedEvent.hashCode(), new ServerSentEvent("payload", "update").hashCode());
        assertFalse(namedEvent.equals("update"));
        assertTrue(namedEvent.toString().contains("update"));

        ContentType plain = new ContentType("text", "plain");
        ContentType parsed = ContentType.parse("TEXT/PLAIN; charset=UTF-8");

        assertEquals("text/plain", plain.getValue());
        assertTrue(plain.match(parsed));
        assertTrue(ContentType.Image.getAny().match(ContentType.Image.getJPEG()));
        assertTrue(ContentType.Audio.getAny().match(new ContentType("audio/mpeg")));
        assertTrue(ContentType.Video.getAny().match(new ContentType("video/mp4")));
        assertSame(ContentType.Application.getJson(), ContentType.Application.INSTANCE.getJson());
        assertSame(ContentType.Application.getXml(), ContentType.Application.INSTANCE.getXml());
        assertSame(ContentType.Application.getZip(), ContentType.Application.INSTANCE.getZip());
        assertSame(ContentType.Application.getOctetStream(), ContentType.Application.INSTANCE.getOctetStream());
        assertSame(ContentType.Text.getPlain(), ContentType.Text.INSTANCE.getPlain());
        assertSame(ContentType.Text.getHtml(), ContentType.Text.INSTANCE.getHtml());
        assertEquals(ContentType.Companion.parse("application/xml"), ContentType.parse("application/xml"));
    }

    @Test
    void httpStatusAndPrincipalHelpersRemainStable() {
        HttpStatusCode ok = HttpStatusCode.Companion.getOK();
        HttpStatusCode custom = HttpStatusCode.fromValue(418);
        HttpStatusCode fromStatus = HttpStatusCode.Companion.fromJavalinStatus(HttpStatus.NOT_FOUND);
        UserIdPrincipal principal = new UserIdPrincipal("alice");

        assertEquals(200, ok.getValue());
        assertSame(HttpStatus.OK, ok.toJavalinStatus());
        assertEquals("418 I'm a Teapot", custom.toString());
        assertEquals(404, fromStatus.getValue());
        assertSame(HttpStatusCode.NotFound, fromStatus);
        assertEquals(ok, HttpStatusCode.Companion.fromValue(200));
        assertSame(HttpStatusCode.Created, HttpStatusCode.Companion.getCreated());
        assertSame(HttpStatusCode.Accepted, HttpStatusCode.Companion.getAccepted());
        assertSame(HttpStatusCode.PartialContent, HttpStatusCode.Companion.getPartialContent());
        assertSame(HttpStatusCode.NoContent, HttpStatusCode.Companion.getNoContent());
        assertSame(HttpStatusCode.NotModified, HttpStatusCode.Companion.getNotModified());
        assertSame(HttpStatusCode.BadRequest, HttpStatusCode.Companion.getBadRequest());
        assertSame(HttpStatusCode.Unauthorized, HttpStatusCode.Companion.getUnauthorized());
        assertSame(HttpStatusCode.Forbidden, HttpStatusCode.Companion.getForbidden());
        assertSame(HttpStatusCode.NotFound, HttpStatusCode.Companion.getNotFound());
        assertSame(HttpStatusCode.Conflict, HttpStatusCode.Companion.getConflict());
        assertSame(HttpStatusCode.PayloadTooLarge, HttpStatusCode.Companion.getPayloadTooLarge());
        assertSame(HttpStatusCode.TooManyRequests, HttpStatusCode.Companion.getTooManyRequests());
        assertSame(HttpStatusCode.InternalServerError, HttpStatusCode.Companion.getInternalServerError());
        assertSame(HttpStatusCode.ServiceUnavailable, HttpStatusCode.Companion.getServiceUnavailable());
        assertNull(HttpStatusCode.Companion.fromJavalinStatus(null));

        assertEquals("alice", principal.getName());
        assertEquals(principal, new UserIdPrincipal("alice"));
        assertTrue(principal.toString().contains("alice"));
    }
}
