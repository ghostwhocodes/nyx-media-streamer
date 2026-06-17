package com.nyx.eforms;

import static com.nyx.eforms.MetadataContractFactories.fieldDefinition;
import static com.nyx.eforms.MetadataContractFactories.searchQuery;
import static com.nyx.eforms.TestJsonSupport.jsonArray;
import static com.nyx.eforms.TestJsonSupport.jsonObject;
import static com.nyx.eforms.TestJsonSupport.jsonPrimitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FieldType;
import com.nyx.eforms.model.FormDefinition;
import com.nyx.eforms.model.MediaType;
import com.nyx.eforms.model.SearchResult;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class EFormServiceDeepTest {
    private Path tempDir;
    private DatabaseResources databaseResources;
    private Jdbi db;
    private HikariDataSource ds;
    private EFormService service;

    private final FieldDefinition textField = fieldDefinition("title", FieldType.TEXT, true);
    private final FieldDefinition numberField = fieldDefinition("year", FieldType.NUMBER);
    private final FieldDefinition boolField = fieldDefinition("watched", FieldType.BOOLEAN);
    private final FieldDefinition dateField = fieldDefinition("releaseDate", FieldType.DATE);
    private final FieldDefinition selectField = fieldDefinition(
        "genre",
        FieldType.SELECT,
        false,
        List.of("Action", "Comedy", "Drama")
    );
    private final FieldDefinition multiSelectField = fieldDefinition(
        "tags",
        FieldType.MULTI_SELECT,
        false,
        List.of("classic", "favorite", "rewatch")
    );

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-eform-deep-test");
        databaseResources = EFormsDatabase.createDatabase(tempDir);
        db = databaseResources.getJdbi();
        ds = databaseResources.getDataSource();
        service = new EFormService(db);
    }

    @AfterEach
    void teardown() {
        if (ds != null) {
            ds.close();
        }
        TestFileSupport.deleteTree(tempDir);
    }

    @Test
    void textFieldAcceptsValidString() {
        FormDefinition form = createTestForm(List.of(textField));
        var metadata = service.attachMetadata("/test.mkv", form.id(), Map.of("title", jsonPrimitive("Hello")));
        assertEquals("Hello", metadata.values().get("title").asText());
    }

    @Test
    void textFieldRejectsNumberValue() {
        FormDefinition form = createTestForm(List.of(textField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata("/test.mkv", form.id(), Map.of("title", jsonPrimitive(123)))
        );
        assertTrue(ex.getMessage().contains("title"));
        assertTrue(ex.getMessage().contains("string"));
    }

    @Test
    void textFieldRejectsBooleanValue() {
        FormDefinition form = createTestForm(List.of(textField));
        assertValidationFailure(() ->
            service.attachMetadata("/test.mkv", form.id(), Map.of("title", jsonPrimitive(true)))
        );
    }

    @Test
    void textFieldRejectsArrayValue() {
        FormDefinition form = createTestForm(List.of(textField));
        assertValidationFailure(() ->
            service.attachMetadata("/test.mkv", form.id(), Map.of("title", jsonArray(List.of(jsonPrimitive("a")))))
        );
    }

    @Test
    void textFieldRejectsObjectValue() {
        FormDefinition form = createTestForm(List.of(textField));
        assertValidationFailure(() ->
            service.attachMetadata("/test.mkv", form.id(), Map.of("title", jsonObject(Map.of("k", jsonPrimitive("v")))))
        );
    }

    @Test
    void numberFieldAcceptsIntegerValue() {
        FormDefinition form = createTestForm(List.of(textField, numberField));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "year", jsonPrimitive(2024))
        );
        assertEquals(2024, metadata.values().get("year").intValue());
    }

    @Test
    void numberFieldAcceptsDoubleValue() {
        FormDefinition form = createTestForm(List.of(textField, numberField));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "year", jsonPrimitive(3.14))
        );
        assertEquals(3.14, metadata.values().get("year").doubleValue(), 0.0001);
    }

    @Test
    void numberFieldRejectsStringValue() {
        FormDefinition form = createTestForm(List.of(textField, numberField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "year", jsonPrimitive("not a number"))
            )
        );
        assertTrue(ex.getMessage().contains("year"));
    }

    @Test
    void numberFieldRejectsBooleanValue() {
        FormDefinition form = createTestForm(List.of(textField, numberField));
        assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "year", jsonPrimitive(true))
            )
        );
    }

    @Test
    void numberFieldRejectsArrayValue() {
        FormDefinition form = createTestForm(List.of(textField, numberField));
        assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "year", jsonArray(List.of(jsonPrimitive(1))))
            )
        );
    }

    @Test
    void booleanFieldAcceptsTrue() {
        FormDefinition form = createTestForm(List.of(textField, boolField));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "watched", jsonPrimitive(true))
        );
        assertTrue(metadata.values().get("watched").booleanValue());
    }

    @Test
    void booleanFieldAcceptsFalse() {
        FormDefinition form = createTestForm(List.of(textField, boolField));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "watched", jsonPrimitive(false))
        );
        assertFalse(metadata.values().get("watched").booleanValue());
    }

    @Test
    void booleanFieldRejectsStringValue() {
        FormDefinition form = createTestForm(List.of(textField, boolField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "watched", jsonPrimitive("yes"))
            )
        );
        assertTrue(ex.getMessage().contains("watched"));
    }

    @Test
    void booleanFieldRejectsNumberValue() {
        FormDefinition form = createTestForm(List.of(textField, boolField));
        assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "watched", jsonPrimitive(1))
            )
        );
    }

    @Test
    void dateFieldAcceptsDateString() {
        FormDefinition form = createTestForm(List.of(textField, dateField));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "releaseDate", jsonPrimitive("2024-01-15"))
        );
        assertEquals("2024-01-15", metadata.values().get("releaseDate").asText());
    }

    @Test
    void dateFieldRejectsNumberValue() {
        FormDefinition form = createTestForm(List.of(textField, dateField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "releaseDate", jsonPrimitive(20240115))
            )
        );
        assertTrue(ex.getMessage().contains("releaseDate"));
        assertTrue(ex.getMessage().contains("date"));
    }

    @Test
    void dateFieldRejectsBooleanValue() {
        FormDefinition form = createTestForm(List.of(textField, dateField));
        assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "releaseDate", jsonPrimitive(true))
            )
        );
    }

    @Test
    void dateFieldRejectsArrayValue() {
        FormDefinition form = createTestForm(List.of(textField, dateField));
        assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "releaseDate", jsonArray(List.of(jsonPrimitive("2024-01-15"))))
            )
        );
    }

    @Test
    void selectFieldAcceptsValidOption() {
        FormDefinition form = createTestForm(List.of(textField, selectField));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "genre", jsonPrimitive("Action"))
        );
        assertEquals("Action", metadata.values().get("genre").asText());
    }

    @Test
    void selectFieldRejectsInvalidOption() {
        FormDefinition form = createTestForm(List.of(textField, selectField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "genre", jsonPrimitive("Horror"))
            )
        );
        assertTrue(ex.getMessage().contains("Horror"));
        assertTrue(ex.getMessage().contains("options"));
    }

    @Test
    void selectFieldRejectsNumberValue() {
        FormDefinition form = createTestForm(List.of(textField, selectField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "genre", jsonPrimitive(42))
            )
        );
        assertTrue(ex.getMessage().contains("genre"));
    }

    @Test
    void selectFieldRejectsArrayValue() {
        FormDefinition form = createTestForm(List.of(textField, selectField));
        assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "genre", jsonArray(List.of(jsonPrimitive("Action"))))
            )
        );
    }

    @Test
    void selectFieldWithoutOptionsConstraintAcceptsAnyString() {
        FieldDefinition selectNoOptions = fieldDefinition(
            "category",
            FieldType.SELECT,
            false,
            List.of("A", "B")
        );
        FormDefinition form = createTestForm(List.of(textField, selectNoOptions));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "category", jsonPrimitive("A"))
        );
        assertEquals("A", metadata.values().get("category").asText());
    }

    @Test
    void multiSelectFieldAcceptsValidArray() {
        FormDefinition form = createTestForm(List.of(textField, multiSelectField));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "tags", jsonArray(List.of(jsonPrimitive("classic"), jsonPrimitive("favorite"))))
        );
        ArrayNode tags = (ArrayNode) metadata.values().get("tags");
        assertEquals(2, tags.size());
    }

    @Test
    void multiSelectFieldAcceptsEmptyArray() {
        FormDefinition form = createTestForm(List.of(textField, multiSelectField));
        var metadata = service.attachMetadata(
            "/test.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("Test"), "tags", jsonArray(List.of()))
        );
        ArrayNode tags = (ArrayNode) metadata.values().get("tags");
        assertEquals(0, tags.size());
    }

    @Test
    void multiSelectFieldRejectsNonArray() {
        FormDefinition form = createTestForm(List.of(textField, multiSelectField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "tags", jsonPrimitive("classic"))
            )
        );
        assertTrue(ex.getMessage().contains("array"));
    }

    @Test
    void multiSelectFieldRejectsArrayWithNonStringItems() {
        FormDefinition form = createTestForm(List.of(textField, multiSelectField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "tags", jsonArray(List.of(jsonPrimitive(123))))
            )
        );
        assertTrue(ex.getMessage().contains("strings"));
    }

    @Test
    void multiSelectFieldRejectsInvalidOptionInArray() {
        FormDefinition form = createTestForm(List.of(textField, multiSelectField));
        NyxException ex = assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of(
                    "title", jsonPrimitive("Test"),
                    "tags", jsonArray(List.of(jsonPrimitive("classic"), jsonPrimitive("unknown")))
                )
            )
        );
        assertTrue(ex.getMessage().contains("unknown"));
        assertTrue(ex.getMessage().contains("options"));
    }

    @Test
    void multiSelectFieldRejectsArrayWithNestedArray() {
        FormDefinition form = createTestForm(List.of(textField, multiSelectField));
        assertValidationFailure(() ->
            service.attachMetadata(
                "/test.mkv",
                form.id(),
                Map.of(
                    "title", jsonPrimitive("Test"),
                    "tags", jsonArray(List.of(jsonArray(List.of(jsonPrimitive("classic")))))
                )
            )
        );
    }

    @Test
    void buildFtsContentExtractsOnlyTextFieldValues() {
        List<FieldDefinition> fields = List.of(
            fieldDefinition("title", FieldType.TEXT),
            fieldDefinition("year", FieldType.NUMBER),
            fieldDefinition("description", FieldType.TEXT)
        );
        Map<String, JsonNode> values = Map.of(
            "title", jsonPrimitive("The Matrix"),
            "year", jsonPrimitive(1999),
            "description", jsonPrimitive("A sci-fi classic")
        );

        assertEquals("The Matrix A sci-fi classic", service.buildFtsContent(fields, values));
    }

    @Test
    void buildFtsContentWithNoTextFieldsReturnsEmptyString() {
        List<FieldDefinition> fields = List.of(
            fieldDefinition("year", FieldType.NUMBER),
            fieldDefinition("watched", FieldType.BOOLEAN)
        );
        Map<String, JsonNode> values = Map.of(
            "year", jsonPrimitive(2024),
            "watched", jsonPrimitive(true)
        );

        assertEquals("", service.buildFtsContent(fields, values));
    }

    @Test
    void buildFtsContentWithTextFieldButMissingValueSkipsIt() {
        List<FieldDefinition> fields = List.of(
            fieldDefinition("title", FieldType.TEXT),
            fieldDefinition("notes", FieldType.TEXT)
        );

        assertEquals(
            "Test",
            service.buildFtsContent(fields, Map.of("title", jsonPrimitive("Test")))
        );
    }

    @Test
    void buildFtsContentWithEmptyValuesMap() {
        assertEquals("", service.buildFtsContent(List.of(fieldDefinition("title", FieldType.TEXT)), Map.of()));
    }

    @Test
    void buildFtsContentWithNonPrimitiveTextValueSkipsIt() {
        List<FieldDefinition> fields = List.of(fieldDefinition("title", FieldType.TEXT));
        assertEquals(
            "",
            service.buildFtsContent(fields, Map.of("title", jsonArray(List.of(jsonPrimitive("array")))))
        );
    }

    @Test
    void searchWithTextQueryFindsMatchingMetadata() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/movie1.mkv", form.id(), Map.of("title", jsonPrimitive("The Matrix")));
        service.attachMetadata("/movie2.mkv", form.id(), Map.of("title", jsonPrimitive("Inception")));
        service.attachMetadata("/movie3.mkv", form.id(), Map.of("title", jsonPrimitive("The Matrix Reloaded")));

        SearchResult result = service.search(searchQuery("Matrix"));
        assertEquals(2, result.results().size());
        assertTrue(result.results().stream().allMatch(item ->
            item.metadata().get("title").asText().contains("Matrix")
        ));
    }

    @Test
    void searchWithTextQueryReturnsEmptyForNoMatch() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/movie1.mkv", form.id(), Map.of("title", jsonPrimitive("The Matrix")));

        SearchResult result = service.search(searchQuery("Inception"));
        assertEquals(0, result.results().size());
        assertEquals(0, result.total());
    }

    @Test
    void searchWithBlankTextQueryIgnoresFts() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/movie1.mkv", form.id(), Map.of("title", jsonPrimitive("The Matrix")));

        SearchResult result = service.search(searchQuery("   ", Map.of(), null, null, null, null, null));
        assertEquals(1, result.results().size());
    }

    @Test
    void searchWithNullTextQueryReturnsAll() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/movie1.mkv", form.id(), Map.of("title", jsonPrimitive("Test 1")));
        service.attachMetadata("/movie2.mkv", form.id(), Map.of("title", jsonPrimitive("Test 2")));

        SearchResult result = service.search(searchQuery());
        assertEquals(2, result.results().size());
    }

    @Test
    void searchWithFormIdFilterReturnsOnlyThatForm() {
        FormDefinition form1 = service.createForm("Form A", Set.of(MediaType.VIDEO), List.of(textField));
        FormDefinition form2 = service.createForm("Form B", Set.of(MediaType.VIDEO), List.of(textField));

        service.attachMetadata("/movie1.mkv", form1.id(), Map.of("title", jsonPrimitive("Test A")));
        service.attachMetadata("/movie2.mkv", form2.id(), Map.of("title", jsonPrimitive("Test B")));

        SearchResult result = service.search(searchQuery(null, Map.of(), form1.id(), null, null, null, null));
        assertEquals(1, result.results().size());
        assertEquals(form1.id(), result.results().get(0).formId());
    }

    @Test
    void searchWithMediaTypeFilterReturnsMatchingForms() {
        FormDefinition videoForm = service.createForm("Video Form", Set.of(MediaType.VIDEO), List.of(textField));
        FormDefinition audioForm = service.createForm("Audio Form", Set.of(MediaType.AUDIO), List.of(textField));

        service.attachMetadata("/movie.mkv", videoForm.id(), Map.of("title", jsonPrimitive("Movie")));
        service.attachMetadata("/song.mp3", audioForm.id(), Map.of("title", jsonPrimitive("Song")));

        SearchResult result = service.search(searchQuery(null, Map.of(), null, MediaType.VIDEO, null, null, null));
        assertEquals(1, result.results().size());
        assertEquals("Movie", result.results().get(0).metadata().get("title").asText());
    }

    @Test
    void searchWithMediaTypeFilterThatMatchesNothing() {
        FormDefinition videoForm = service.createForm("Video Form", Set.of(MediaType.VIDEO), List.of(textField));
        service.attachMetadata("/movie.mkv", videoForm.id(), Map.of("title", jsonPrimitive("Movie")));

        SearchResult result = service.search(searchQuery(null, Map.of(), null, MediaType.IMAGE, null, null, null));
        assertEquals(0, result.results().size());
    }

    @Test
    void searchWithSortByCreatedAt() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A")));
        service.attachMetadata("/b.mkv", form.id(), Map.of("title", jsonPrimitive("B")));

        SearchResult result = service.search(searchQuery(null, Map.of(), null, null, "createdAt", null, null));
        assertEquals(2, result.results().size());
    }

    @Test
    void searchWithSortByUpdatedAt() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A")));
        service.attachMetadata("/b.mkv", form.id(), Map.of("title", jsonPrimitive("B")));

        SearchResult result = service.search(searchQuery(null, Map.of(), null, null, "updatedAt", null, null));
        assertEquals(2, result.results().size());
    }

    @Test
    void searchWithUnknownSortByDefaultsToUpdatedAt() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A")));

        SearchResult result = service.search(searchQuery(null, Map.of(), null, null, "unknownField", null, null));
        assertEquals(1, result.results().size());
    }

    @Test
    void searchWithNullSortByDefaultsToUpdatedAt() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A")));

        SearchResult result = service.search(searchQuery());
        assertEquals(1, result.results().size());
    }

    @Test
    void searchWithFieldFiltersMatches() {
        FormDefinition form = createTestForm(List.of(textField, numberField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A"), "year", jsonPrimitive(2020)));
        service.attachMetadata("/b.mkv", form.id(), Map.of("title", jsonPrimitive("B"), "year", jsonPrimitive(2024)));

        SearchResult result = service.search(searchQuery(null, Map.of("year", jsonPrimitive(2024)), null, null, null, null, null));
        assertEquals(1, result.results().size());
        assertEquals("B", result.results().get(0).metadata().get("title").asText());
    }

    @Test
    void searchWithFieldFiltersNoMatch() {
        FormDefinition form = createTestForm(List.of(textField, numberField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A"), "year", jsonPrimitive(2020)));

        SearchResult result = service.search(searchQuery(null, Map.of("year", jsonPrimitive(9999)), null, null, null, null, null));
        assertEquals(0, result.results().size());
    }

    @Test
    void searchWithMultipleFieldFilters() {
        FormDefinition form = createTestForm(List.of(textField, numberField, selectField));
        service.attachMetadata(
            "/a.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("A"), "year", jsonPrimitive(2020), "genre", jsonPrimitive("Action"))
        );
        service.attachMetadata(
            "/b.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("B"), "year", jsonPrimitive(2020), "genre", jsonPrimitive("Comedy"))
        );
        service.attachMetadata(
            "/c.mkv",
            form.id(),
            Map.of("title", jsonPrimitive("C"), "year", jsonPrimitive(2024), "genre", jsonPrimitive("Action"))
        );

        SearchResult result = service.search(searchQuery(
            null,
            Map.of("year", jsonPrimitive(2020), "genre", jsonPrimitive("Action")),
            null,
            null,
            null,
            null,
            null
        ));
        assertEquals(1, result.results().size());
        assertEquals("A", result.results().get(0).metadata().get("title").asText());
    }

    @Test
    void searchWithTextAndFormIdCombined() {
        FormDefinition form1 = service.createForm("Form A", Set.of(MediaType.VIDEO), List.of(textField));
        FormDefinition form2 = service.createForm("Form B", Set.of(MediaType.VIDEO), List.of(textField));

        service.attachMetadata("/a.mkv", form1.id(), Map.of("title", jsonPrimitive("Matrix A")));
        service.attachMetadata("/b.mkv", form2.id(), Map.of("title", jsonPrimitive("Matrix B")));

        SearchResult result = service.search(searchQuery("Matrix", Map.of(), form1.id(), null, null, null, null));
        assertEquals(1, result.results().size());
        assertEquals(form1.id(), result.results().get(0).formId());
    }

    @Test
    void searchWithLimit() {
        FormDefinition form = createTestForm(List.of(textField));
        for (int index = 0; index < 5; index++) {
            service.attachMetadata("/movie" + index + ".mkv", form.id(), Map.of("title", jsonPrimitive("Movie " + index)));
        }

        SearchResult result = service.search(searchQuery(null, Map.of(), null, null, null, 2, null));
        assertEquals(2, result.results().size());
        assertEquals(5, result.total());
        assertEquals(2, result.limit());
    }

    @Test
    void searchWithOffset() {
        FormDefinition form = createTestForm(List.of(textField));
        for (int index = 0; index < 5; index++) {
            service.attachMetadata("/movie" + index + ".mkv", form.id(), Map.of("title", jsonPrimitive("Movie " + index)));
        }

        SearchResult result = service.search(searchQuery(null, Map.of(), null, null, null, 2, 3));
        assertEquals(2, result.results().size());
        assertEquals(3, result.offset());
    }

    @Test
    void searchOnEmptyDatabaseReturnsEmptyResults() {
        SearchResult result = service.search(searchQuery());
        assertEquals(0, result.results().size());
        assertEquals(0, result.total());
    }

    @Test
    void searchWithNoConditionsReturnsAllMetadata() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A")));
        service.attachMetadata("/b.mkv", form.id(), Map.of("title", jsonPrimitive("B")));

        SearchResult result = service.search(searchQuery());
        assertEquals(2, result.results().size());
        assertEquals(2, result.total());
    }

    @Test
    void storeContentHashUpdatesMatchingRows() {
        FormDefinition form = createTestForm(List.of(textField));
        var metadata = service.attachMetadata("/movie.mkv", form.id(), Map.of("title", jsonPrimitive("Test")));
        assertNull(metadata.contentHash());

        int updatedCount = service.storeContentHash("/movie.mkv", "sha256:abc123");
        assertEquals(1, updatedCount);

        var retrieved = service.getMetadataById(metadata.id());
        assertEquals("sha256:abc123", retrieved.contentHash());
    }

    @Test
    void storeContentHashReturnsZeroForNonExistentPath() {
        assertEquals(0, service.storeContentHash("/nonexistent.mkv", "sha256:abc123"));
    }

    @Test
    void storeContentHashUpdatesMultipleMetadataForSamePath() {
        FormDefinition form1 = service.createForm("Form A", Set.of(MediaType.VIDEO), List.of(textField));
        FormDefinition form2 = service.createForm("Form B", Set.of(MediaType.VIDEO), List.of(textField));

        var metadata1 = service.attachMetadata("/movie.mkv", form1.id(), Map.of("title", jsonPrimitive("A")));
        var metadata2 = service.attachMetadata("/movie.mkv", form2.id(), Map.of("title", jsonPrimitive("B")));

        int updatedCount = service.storeContentHash("/movie.mkv", "sha256:xyz");
        assertEquals(2, updatedCount);

        assertEquals("sha256:xyz", service.getMetadataById(metadata1.id()).contentHash());
        assertEquals("sha256:xyz", service.getMetadataById(metadata2.id()).contentHash());
    }

    @Test
    void deleteMetadataRemovesFtsIndexAndMetadataRow() {
        FormDefinition form = createTestForm(List.of(textField));
        var metadata = service.attachMetadata("/test.mkv", form.id(), Map.of("title", jsonPrimitive("Searchable Title")));

        SearchResult beforeDelete = service.search(searchQuery("Searchable"));
        assertEquals(1, beforeDelete.results().size());

        service.deleteMetadata(metadata.id());

        assertNull(service.getMetadataById(metadata.id()));
        SearchResult afterDelete = service.search(searchQuery("Searchable"));
        assertEquals(0, afterDelete.results().size());
    }

    @Test
    void deleteMetadataForNonExistentIdThrows() {
        NyxException ex = assertThrows(NyxException.class, () -> service.deleteMetadata("nonexistent-id"));
        assertEquals(ErrorCode.METADATA_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getMetadataByFormIdReturnsAllMetadataForForm() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A")));
        service.attachMetadata("/b.mkv", form.id(), Map.of("title", jsonPrimitive("B")));

        assertEquals(2, service.getMetadataByFormId(form.id()).size());
    }

    @Test
    void getMetadataByFormIdReturnsEmptyForNonExistentForm() {
        assertTrue(service.getMetadataByFormId("nonexistent").isEmpty());
    }

    @Test
    void updateMetadataUpdatesFtsIndex() {
        FormDefinition form = createTestForm(List.of(textField));
        var metadata = service.attachMetadata("/test.mkv", form.id(), Map.of("title", jsonPrimitive("Original Title")));

        assertEquals(1, service.search(searchQuery("Original")).results().size());

        service.updateMetadata(metadata.id(), Map.of("title", jsonPrimitive("Updated Title")));

        assertEquals(0, service.search(searchQuery("Original")).results().size());
        assertEquals(1, service.search(searchQuery("Updated")).results().size());
    }

    @Test
    void updateMetadataForNonExistentIdThrows() {
        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.updateMetadata("nonexistent-id", Map.of("title", jsonPrimitive("Test")))
        );
        assertEquals(ErrorCode.METADATA_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void deleteFormWithDeleteMetadataCleansUpFtsEntries() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("Searchable A")));
        service.attachMetadata("/b.mkv", form.id(), Map.of("title", jsonPrimitive("Searchable B")));

        assertEquals(2, service.search(searchQuery("Searchable")).results().size());

        service.deleteForm(form.id(), true);

        assertTrue(service.getMetadataByFormId(form.id()).isEmpty());
    }

    @Test
    void searchWithTextPopulatesRelevanceScores() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("Matrix")));
        service.attachMetadata("/b.mkv", form.id(), Map.of("title", jsonPrimitive("The Matrix Reloaded Matrix")));

        SearchResult result = service.search(searchQuery("Matrix"));
        assertEquals(2, result.results().size());
        assertTrue(result.results().stream().allMatch(item -> item.relevance() != null));
    }

    @Test
    void searchWithoutTextHasNullRelevance() {
        FormDefinition form = createTestForm(List.of(textField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("Test")));

        SearchResult result = service.search(searchQuery());
        assertEquals(1, result.results().size());
        assertNull(result.results().get(0).relevance());
    }

    @Test
    void searchTotalReflectsPostFilterCountWhenFieldFiltersActive() {
        FormDefinition form = createTestForm(List.of(textField, numberField));
        service.attachMetadata("/a.mkv", form.id(), Map.of("title", jsonPrimitive("A"), "year", jsonPrimitive(2020)));
        service.attachMetadata("/b.mkv", form.id(), Map.of("title", jsonPrimitive("B"), "year", jsonPrimitive(2024)));
        service.attachMetadata("/c.mkv", form.id(), Map.of("title", jsonPrimitive("C"), "year", jsonPrimitive(2024)));

        SearchResult result = service.search(searchQuery(null, Map.of("year", jsonPrimitive(2024)), null, null, null, null, null));
        assertEquals(2, result.results().size());
        assertEquals(2, result.total());
    }

    @Test
    void searchTotalReflectsPostFilterCountWhenMediaTypeFilterActive() {
        FormDefinition videoForm = service.createForm("Video Form", Set.of(MediaType.VIDEO), List.of(textField));
        FormDefinition audioForm = service.createForm("Audio Form", Set.of(MediaType.AUDIO), List.of(textField));

        service.attachMetadata("/movie.mkv", videoForm.id(), Map.of("title", jsonPrimitive("Movie")));
        service.attachMetadata("/song.mp3", audioForm.id(), Map.of("title", jsonPrimitive("Song")));

        SearchResult result = service.search(searchQuery(null, Map.of(), null, MediaType.AUDIO, null, null, null));
        assertEquals(1, result.results().size());
        assertEquals(1, result.total());
    }

    private FormDefinition createTestForm(List<FieldDefinition> fields) {
        return createTestForm(fields, Set.of(MediaType.VIDEO));
    }

    private FormDefinition createTestForm(List<FieldDefinition> fields, Set<MediaType> mediaTypes) {
        return service.createForm("Test Form", mediaTypes, fields);
    }

    private NyxException assertValidationFailure(Executable executable) {
        NyxException ex = assertThrows(NyxException.class, executable);
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        return ex;
    }
}
