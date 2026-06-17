package com.nyx.eforms.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetadataContractDefaultsCoverageTest {

    @Test
    void fieldDefinitionConstructorsPreserveHistoricalDefaults() {
        List<String> options = new ArrayList<>(List.of("g", "pg"));

        FieldDefinition optionalField = new FieldDefinition("genre", FieldType.SELECT);
        FieldDefinition requiredField = new FieldDefinition("rating", FieldType.NUMBER, true);
        FieldDefinition fieldWithOptions = new FieldDefinition("certification", FieldType.SELECT, true, options);

        options.add("r");

        assertEquals("genre", optionalField.name());
        assertEquals(FieldType.SELECT, optionalField.type());
        assertEquals(false, optionalField.required());
        assertNull(optionalField.options());
        assertNull(optionalField.defaultValue());

        assertEquals("rating", requiredField.name());
        assertTrue(requiredField.required());
        assertNull(requiredField.options());

        assertEquals(List.of("g", "pg"), fieldWithOptions.options());
        assertThrows(UnsupportedOperationException.class, () -> fieldWithOptions.options().add("nc-17"));
    }

    @Test
    void formDefinitionAndMediaMetadataConstructorsApplyCopiesAndDefaults() {
        Set<MediaType> mediaTypes = new HashSet<>(Set.of(MediaType.IMAGE));
        List<FormVersion> versions = new ArrayList<>(
            List.of(new FormVersion(3, List.of(), Instant.parse("2026-05-01T10:15:30Z")))
        );
        Map<String, com.fasterxml.jackson.databind.JsonNode> values = new HashMap<>(
            Map.of("title", JsonNodeFactory.instance.textNode("Movie"))
        );

        FormDefinition defaultedForm = new FormDefinition("form-1", "Images", 3, mediaTypes, null, null, null);
        FormDefinition versionedForm = new FormDefinition("form-2", "Videos", 4, mediaTypes, versions);
        MediaMetadata defaultedMetadata = new MediaMetadata(
            "meta-1",
            "/srv/media/movie.mkv",
            "hash-1",
            "form-1",
            3,
            values,
            null,
            null
        );
        MediaMetadata convenienceMetadata = new MediaMetadata("meta-2", "/srv/media/poster.jpg", "form-2", 4, values);
        MediaMetadata convenienceHashMetadata = new MediaMetadata(
            "meta-3",
            "/srv/media/trailer.mp4",
            "hash-3",
            "form-3",
            5,
            values
        );

        mediaTypes.add(MediaType.VIDEO);
        versions.add(new FormVersion(4, List.of(), Instant.parse("2026-05-01T10:16:30Z")));
        values.put("rating", JsonNodeFactory.instance.numberNode(5));

        assertEquals(Set.of(MediaType.IMAGE), defaultedForm.mediaTypes());
        assertTrue(defaultedForm.versions().isEmpty());
        assertEquals(Instant.EPOCH, defaultedForm.createdAt());
        assertEquals(Instant.EPOCH, defaultedForm.updatedAt());
        assertThrows(UnsupportedOperationException.class, () -> defaultedForm.mediaTypes().add(MediaType.VIDEO));

        assertEquals(1, versionedForm.versions().size());
        assertEquals(Instant.EPOCH, versionedForm.createdAt());
        assertEquals(Instant.EPOCH, versionedForm.updatedAt());
        assertThrows(UnsupportedOperationException.class, () -> versionedForm.versions().add(null));

        assertEquals("hash-1", defaultedMetadata.contentHash());
        assertEquals(Instant.EPOCH, defaultedMetadata.createdAt());
        assertEquals(Instant.EPOCH, defaultedMetadata.updatedAt());
        assertEquals("Movie", defaultedMetadata.values().get("title").asText());
        assertThrows(UnsupportedOperationException.class, () -> defaultedMetadata.values().put("extra", JsonNodeFactory.instance.nullNode()));

        assertNull(convenienceMetadata.contentHash());
        assertEquals(Instant.EPOCH, convenienceMetadata.createdAt());
        assertEquals(Instant.EPOCH, convenienceMetadata.updatedAt());
        assertEquals("hash-3", convenienceHashMetadata.contentHash());
        assertEquals(Instant.EPOCH, convenienceHashMetadata.createdAt());
        assertEquals(Instant.EPOCH, convenienceHashMetadata.updatedAt());
    }

    @Test
    void searchQueryConstructorsPreserveDefaultsAndImmutableFilters() {
        Map<String, com.fasterxml.jackson.databind.JsonNode> filters = new HashMap<>(
            Map.of("genre", JsonNodeFactory.instance.textNode("drama"))
        );

        SearchQuery defaultedQuery = new SearchQuery("bird", filters, "form-7", MediaType.IMAGE, "relevance", null, null);
        SearchQuery emptyQuery = new SearchQuery();
        SearchQuery textOnlyQuery = new SearchQuery("movie");

        filters.put("rating", JsonNodeFactory.instance.numberNode(5));

        assertEquals("bird", defaultedQuery.text());
        assertEquals("form-7", defaultedQuery.formId());
        assertEquals(MediaType.IMAGE, defaultedQuery.mediaType());
        assertEquals("relevance", defaultedQuery.sortBy());
        assertEquals(50, defaultedQuery.limit());
        assertEquals(0, defaultedQuery.offset());
        assertEquals("drama", defaultedQuery.filters().get("genre").asText());
        assertThrows(UnsupportedOperationException.class, () -> defaultedQuery.filters().put("extra", JsonNodeFactory.instance.nullNode()));

        assertNull(emptyQuery.text());
        assertTrue(emptyQuery.filters().isEmpty());
        assertEquals(50, emptyQuery.limit());
        assertEquals(0, emptyQuery.offset());

        assertEquals("movie", textOnlyQuery.text());
        assertTrue(textOnlyQuery.filters().isEmpty());
        assertEquals(50, textOnlyQuery.limit());
        assertEquals(0, textOnlyQuery.offset());
    }
}
