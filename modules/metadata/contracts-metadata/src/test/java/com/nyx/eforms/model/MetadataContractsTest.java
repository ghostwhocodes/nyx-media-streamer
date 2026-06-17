package com.nyx.eforms.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.json.NyxJson;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetadataContractsTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void formDefinitionContractsRoundTripWithJavaTimeAndJsonNodes() throws Exception {
        FormDefinition definition = new FormDefinition(
            "form-1",
            "Movie Metadata",
            2,
            Set.of(MediaType.VIDEO, MediaType.IMAGE),
            List.of(
                new FormVersion(
                    2,
                    List.of(
                        new FieldDefinition(
                            "rating",
                            FieldType.NUMBER,
                            true,
                            null,
                            json.createObjectNode().put("stars", 5)
                        )
                    ),
                    Instant.parse("2026-04-15T12:00:00Z")
                )
            ),
            Instant.parse("2026-04-15T11:00:00Z"),
            Instant.parse("2026-04-15T12:00:00Z")
        );

        String encoded = json.writeValueAsString(definition);
        FormDefinition decoded = json.readValue(encoded, FormDefinition.class);

        assertTrue(encoded.contains("\"mediaTypes\":[\"VIDEO\",\"IMAGE\"]") || encoded.contains("\"mediaTypes\":[\"IMAGE\",\"VIDEO\"]"));
        assertTrue(encoded.contains("\"createdAt\":\"2026-04-15T11:00:00Z\"") || encoded.contains("\"createdAt\":\"2026-04-15T11:00:00.000Z\""));
        assertEquals(definition, decoded);
        assertEquals(5, decoded.versions().getFirst().fields().getFirst().defaultValue().get("stars").asInt());
    }

    @Test
    void metadataSearchContractsKeepAdditiveDefaults() throws Exception {
        SearchQuery query = json.readValue("{}", SearchQuery.class);
        MediaMetadata metadata = new MediaMetadata(
            "meta-1",
            "/srv/media/movie.mkv",
            "form-1",
            2,
            Map.of("title", json.createObjectNode().put("value", "Movie Title"))
        );

        assertNull(query.text());
        assertTrue(query.filters().isEmpty());
        assertEquals(50, query.limit());
        assertEquals(0, query.offset());
        assertEquals(Instant.EPOCH, metadata.createdAt());
        assertEquals("Movie Title", metadata.values().get("title").get("value").asText());
    }

    @Test
    void searchResultContractsRoundTripWithOptionalSearchMetadata() throws Exception {
        SearchResult result = new SearchResult(
            List.of(
                new SearchResultItem(
                    "/srv/media/movie.mkv",
                    MediaType.VIDEO,
                    "form-1",
                    2,
                    Map.of("title", json.createObjectNode().put("value", "Movie Title")),
                    0.91
                ),
                new SearchResultItem(
                    "/srv/media/poster.jpg",
                    "form-2",
                    1,
                    Map.of()
                )
            ),
            2,
            50,
            0
        );

        String encoded = json.writeValueAsString(result);
        SearchResult decoded = json.readValue(encoded, SearchResult.class);

        assertEquals(result, decoded);
        assertTrue(encoded.contains("\"mediaPath\":\"/srv/media/movie.mkv\""));
        assertEquals(0.91, decoded.results().getFirst().relevance());
        assertNull(decoded.results().getLast().mediaType());
    }

    @Test
    void metadataContractDefaultsRemainStableForOmittedOptionalFields() {
        FieldDefinition field = new FieldDefinition("genre", FieldType.SELECT);
        FormDefinition definition = new FormDefinition(
            "form-2",
            "Photo Metadata",
            1,
            Set.of(MediaType.IMAGE)
        );
        SearchQuery query = new SearchQuery("bird", Map.of(), "form-2", MediaType.IMAGE, "relevance", 50, 0);

        assertEquals(false, field.required());
        assertNull(field.options());
        assertNull(field.defaultValue());
        assertTrue(definition.versions().isEmpty());
        assertEquals(Instant.EPOCH, definition.createdAt());
        assertEquals(Instant.EPOCH, definition.updatedAt());
        assertEquals("bird", query.text());
        assertEquals("form-2", query.formId());
        assertEquals(MediaType.IMAGE, query.mediaType());
        assertEquals("relevance", query.sortBy());
    }
}
