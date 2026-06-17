package com.nyx.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenApiRegistryTest {
    @Test
    void openApiHelperTypesKeepStableDefaults() {
        OpenApiParameter parameter = new OpenApiParameter("id", "path");
        OpenApiResponse response = new OpenApiResponse(204);
        OpenApiOperation operation = new OpenApiOperation("GET", "/items");
        ParameterDoc parameterDoc = new ParameterDoc();
        parameterDoc.setDescription("Identifier");
        parameterDoc.setRequired(true);
        BodyDoc bodyDoc = new BodyDoc();
        bodyDoc.setDescription("Payload");
        ResponseCollection responseCollection = new ResponseCollection();
        responseCollection.code(HttpStatusCode.OK, doc -> {
            doc.setDescription("Ok");
        });
        ResponseDoc responseDoc = responseCollection.getResponses().getFirst();

        assertEquals("id", parameter.getName());
        assertEquals("path", parameter.getIn());
        assertNull(parameter.getDescription());
        assertFalse(parameter.getRequired());

        assertEquals(204, response.getCode());
        assertNull(response.getDescription());

        assertEquals("GET", operation.getMethod());
        assertEquals("/items", operation.getPath());
        assertNull(operation.getDescription());
        assertTrue(operation.getParameters().isEmpty());
        assertFalse(operation.getHasRequestBody());
        assertTrue(operation.getResponses().isEmpty());

        assertEquals("Identifier", parameterDoc.getDescription());
        assertTrue(parameterDoc.getRequired());
        assertEquals("Payload", bodyDoc.getDescription());
        assertEquals(200, responseDoc.getCode());
        assertEquals("Ok", responseDoc.getDescription());
    }

    @Test
    void routeConfigConvertsRequestAndResponseDocsIntoAnOperation() {
        OpenApiRouteConfig config = new OpenApiRouteConfig();
        config.setDescription("Create item");
        config.request(request -> {
            request.body(Map.class, body -> {
                body.setDescription("Item payload");
            });
            request.queryParameter("expand", parameter -> {
                parameter.setDescription("Expand related resources");
                parameter.setRequired(true);
            });
            request.pathParameter("id", parameter -> {
                parameter.setDescription("Item identifier");
                parameter.setRequired(false);
            });
            request.headerParameter("X-Trace-Id", parameter -> {
                parameter.setDescription("Correlation id");
            });
        });
        config.response(responses -> {
            responses.code(HttpStatusCode.Created, response -> {
                response.setDescription("Created");
                response.body(Map.class, body -> {
                    body.setDescription("Created entity");
                });
            });
            responses.code(HttpStatusCode.BadRequest, response -> {
                response.setDescription("Validation failed");
            });
        });

        OpenApiOperation operation = config.toOperation("POST", "/items/{id}");

        assertEquals("POST", operation.getMethod());
        assertEquals("/items/{id}", operation.getPath());
        assertEquals("Create item", operation.getDescription());
        assertTrue(operation.getHasRequestBody());
        assertEquals(3, operation.getParameters().size());

        OpenApiParameter query = operation.getParameters().stream()
            .filter(parameter -> "expand".equals(parameter.getName()))
            .findFirst()
            .orElseThrow();
        assertEquals("query", query.getIn());
        assertEquals("Expand related resources", query.getDescription());
        assertTrue(query.getRequired());

        OpenApiParameter path = operation.getParameters().stream()
            .filter(parameter -> "id".equals(parameter.getName()))
            .findFirst()
            .orElseThrow();
        assertEquals("path", path.getIn());
        assertEquals("Item identifier", path.getDescription());
        assertTrue(path.getRequired());

        OpenApiParameter header = operation.getParameters().stream()
            .filter(parameter -> "X-Trace-Id".equals(parameter.getName()))
            .findFirst()
            .orElseThrow();
        assertEquals("header", header.getIn());
        assertEquals("Correlation id", header.getDescription());
        assertFalse(header.getRequired());

        assertEquals(
            List.of(
                new OpenApiResponse(201, "Created"),
                new OpenApiResponse(400, "Validation failed")
            ),
            operation.getResponses()
        );
    }

    @Test
    void registryBuildsGroupedOpenApiSpecWithOptionalSections() {
        OpenApiRegistry registry = new OpenApiRegistry();

        registry.register("GET", "/items", config -> {
            config.setDescription("List items");
            config.request(request -> {
                request.queryParameter("cursor", parameter -> {
                    parameter.setDescription("Pagination cursor");
                });
            });
            config.response(response -> {
                response.code(HttpStatusCode.OK, doc -> {
                    doc.setDescription("Success");
                });
            });
        });

        registry.register("POST", "/items", config -> {
            config.request(request -> {
                request.body(Map.class);
            });
            config.response(response -> {
                response.code(HttpStatusCode.Created, doc -> {
                    doc.setDescription("Created");
                });
            });
        });

        registry.register("DELETE", "/items/{id}", config -> {
            config.request(request -> {
                request.pathParameter("id", parameter -> {
                    parameter.setDescription("Item identifier");
                });
            });
            config.response(response -> {
                response.code(HttpStatusCode.NoContent);
            });
        });

        Map<String, Object> spec = registry.buildSpec("Nyx API", "1.0.0", "API surface");

        assertEquals("3.1.0", spec.get("openapi"));

        Map<?, ?> info = assertInstanceOf(Map.class, spec.get("info"));
        assertEquals("Nyx API", info.get("title"));
        assertEquals("1.0.0", info.get("version"));
        assertEquals("API surface", info.get("description"));

        Map<?, ?> paths = assertInstanceOf(Map.class, spec.get("paths"));
        Map<?, ?> items = assertInstanceOf(Map.class, paths.get("/items"));
        Map<?, ?> get = assertInstanceOf(Map.class, items.get("get"));
        Map<?, ?> post = assertInstanceOf(Map.class, items.get("post"));
        Map<?, ?> deleteOperation = assertInstanceOf(
            Map.class,
            assertInstanceOf(Map.class, paths.get("/items/{id}")).get("delete")
        );

        assertEquals("List items", get.get("description"));
        List<?> getParameters = assertInstanceOf(List.class, get.get("parameters"));
        assertEquals(1, getParameters.size());
        Map<?, ?> firstParameter = assertInstanceOf(Map.class, getParameters.getFirst());
        assertEquals("cursor", firstParameter.get("name"));
        assertEquals("query", firstParameter.get("in"));
        assertEquals(false, firstParameter.get("required"));
        assertEquals("Pagination cursor", firstParameter.get("description"));
        assertEquals(Map.of("type", "string"), firstParameter.get("schema"));

        Map<?, ?> postResponses = assertInstanceOf(Map.class, post.get("responses"));
        Map<?, ?> created = assertInstanceOf(Map.class, postResponses.get("201"));
        assertEquals("Created", created.get("description"));

        Map<?, ?> requestBody = assertInstanceOf(Map.class, post.get("requestBody"));
        assertEquals(true, requestBody.get("required"));
        Map<?, ?> content = assertInstanceOf(Map.class, requestBody.get("content"));
        Map<?, ?> jsonContent = assertInstanceOf(Map.class, content.get(ContentType.Application.Json.getValue()));
        assertEquals(Map.of("type", "object"), jsonContent.get("schema"));

        assertNull(post.get("description"));
        assertNull(post.get("parameters"));

        Map<?, ?> deleteResponses = assertInstanceOf(Map.class, deleteOperation.get("responses"));
        Map<?, ?> noContent = assertInstanceOf(Map.class, deleteResponses.get("204"));
        assertEquals("", noContent.get("description"));
        List<?> deleteParameters = assertInstanceOf(List.class, deleteOperation.get("parameters"));
        Map<?, ?> pathParameter = assertInstanceOf(Map.class, deleteParameters.getFirst());
        assertEquals("id", pathParameter.get("name"));
        assertEquals("path", pathParameter.get("in"));
        assertTrue((Boolean) pathParameter.get("required"));
        assertNotNull(paths.get("/items/{id}"));
    }
}
