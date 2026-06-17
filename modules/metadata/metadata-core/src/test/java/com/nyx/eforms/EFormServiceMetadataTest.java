package com.nyx.eforms;

import static com.nyx.eforms.MetadataContractFactories.fieldDefinition;
import static com.nyx.eforms.TestJsonSupport.jsonArray;
import static com.nyx.eforms.TestJsonSupport.jsonPrimitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FieldType;
import com.nyx.eforms.model.FormDefinition;
import com.nyx.eforms.model.MediaType;
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

class EFormServiceMetadataTest {
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
        tempDir = Files.createTempDirectory("nyx-eform-metadata-test");
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
    void attachValidMetadataSucceeds() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));
        Map<String, com.fasterxml.jackson.databind.JsonNode> values = Map.of(
            "title", jsonPrimitive("The Matrix"),
            "year", jsonPrimitive(1999),
            "watched", jsonPrimitive(true),
            "releaseDate", jsonPrimitive("1999-03-31"),
            "genre", jsonPrimitive("Action"),
            "tags", jsonArray(List.of(jsonPrimitive("classic"), jsonPrimitive("favorite")))
        );

        var metadata = service.attachMetadata("/media/matrix.mkv", form.id(), values);

        assertEquals("/media/matrix.mkv", metadata.mediaPath());
        assertEquals(form.id(), metadata.formId());
        assertEquals(1, metadata.formVersion());
        assertEquals("The Matrix", metadata.values().get("title").asText());
    }

    @Test
    void missingRequiredFieldRejected() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));

        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.attachMetadata("/media/test.mkv", form.id(), Map.of("year", jsonPrimitive(1999)))
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("title"));
    }

    @Test
    void wrongTypeForNumberFieldRejected() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));

        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.attachMetadata(
                "/media/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "year", jsonPrimitive("not a number"))
            )
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("year"));
    }

    @Test
    void wrongTypeForBooleanFieldRejected() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));

        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.attachMetadata(
                "/media/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "watched", jsonPrimitive("yes"))
            )
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("watched"));
    }

    @Test
    void selectValueOutsideOptionsRejected() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));

        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.attachMetadata(
                "/media/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "genre", jsonPrimitive("Horror"))
            )
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Horror"));
    }

    @Test
    void multiSelectWithInvalidOptionRejected() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));

        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.attachMetadata(
                "/media/test.mkv",
                form.id(),
                Map.of(
                    "title", jsonPrimitive("Test"),
                    "tags", jsonArray(List.of(jsonPrimitive("classic"), jsonPrimitive("invalid")))
                )
            )
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("invalid"));
    }

    @Test
    void multiSelectWithNonArrayRejected() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));

        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.attachMetadata(
                "/media/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "tags", jsonPrimitive("classic"))
            )
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("tags"));
    }

    @Test
    void unknownFieldRejected() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));

        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.attachMetadata(
                "/media/test.mkv",
                form.id(),
                Map.of("title", jsonPrimitive("Test"), "unknown_field", jsonPrimitive("value"))
            )
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("unknown_field"));
    }

    @Test
    void multipleFormsOnSameMediaPathWorks() {
        FormDefinition form1 = service.createForm("Form A", Set.of(MediaType.VIDEO), List.of(textField));
        FormDefinition form2 = service.createForm(
            "Form B",
            Set.of(MediaType.VIDEO),
            List.of(fieldDefinition("director", FieldType.TEXT, true))
        );

        service.attachMetadata("/media/test.mkv", form1.id(), Map.of("title", jsonPrimitive("Test")));
        service.attachMetadata("/media/test.mkv", form2.id(), Map.of("director", jsonPrimitive("Nolan")));

        assertEquals(2, service.getMetadata("/media/test.mkv").size());
    }

    @Test
    void updateValidatesAgainstCreationVersionNotCurrentVersion() {
        FormDefinition form = service.createForm("Test", Set.of(MediaType.VIDEO), List.of(textField));
        var metadata = service.attachMetadata("/media/test.mkv", form.id(), Map.of("title", jsonPrimitive("Original")));

        service.updateForm(
            form.id(),
            List.of(textField, fieldDefinition("director", FieldType.TEXT, true))
        );

        var updated = service.updateMetadata(metadata.id(), Map.of("title", jsonPrimitive("Updated")));
        assertEquals("Updated", updated.values().get("title").asText());
    }

    @Test
    void updateMetadataWithFieldFromNewVersionFails() {
        FormDefinition form = service.createForm("Test", Set.of(MediaType.VIDEO), List.of(textField));
        var metadata = service.attachMetadata("/media/test.mkv", form.id(), Map.of("title", jsonPrimitive("Original")));

        service.updateForm(
            form.id(),
            List.of(textField, fieldDefinition("director", FieldType.TEXT))
        );

        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.updateMetadata(
                metadata.id(),
                Map.of("title", jsonPrimitive("Updated"), "director", jsonPrimitive("Nolan"))
            )
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void getMetadataById() {
        FormDefinition form = createTestForm(List.of(textField));
        var metadata = service.attachMetadata("/media/test.mkv", form.id(), Map.of("title", jsonPrimitive("Test")));

        var retrieved = service.getMetadataById(metadata.id());
        assertNotNull(retrieved);
        assertEquals(metadata.id(), retrieved.id());
        assertEquals("Test", retrieved.values().get("title").asText());
    }

    @Test
    void getMetadataByIdReturnsNullForNonExistent() {
        assertNull(service.getMetadataById("non-existent"));
    }

    @Test
    void deleteMetadata() {
        FormDefinition form = createTestForm(List.of(textField));
        var metadata = service.attachMetadata("/media/test.mkv", form.id(), Map.of("title", jsonPrimitive("Test")));

        service.deleteMetadata(metadata.id());

        assertNull(service.getMetadataById(metadata.id()));
        assertTrue(service.getMetadata("/media/test.mkv").isEmpty());
    }

    @Test
    void deleteNonExistentMetadataThrows() {
        NyxException ex = assertThrows(NyxException.class, () -> service.deleteMetadata("non-existent"));
        assertEquals(ErrorCode.METADATA_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void attachToNonExistentFormThrows() {
        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.attachMetadata("/media/test.mkv", "non-existent", Map.of("title", jsonPrimitive("Test")))
        );
        assertEquals(ErrorCode.FORM_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void optionalFieldsCanBeOmitted() {
        FormDefinition form = createTestForm(List.of(textField, numberField, boolField, dateField, selectField, multiSelectField));

        var metadata = service.attachMetadata("/media/test.mkv", form.id(), Map.of("title", jsonPrimitive("Minimal")));
        assertEquals(1, metadata.values().size());
    }

    private FormDefinition createTestForm(List<FieldDefinition> fields) {
        return service.createForm("Test Form", Set.of(MediaType.VIDEO), fields);
    }
}
