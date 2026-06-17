package com.nyx.eforms;

import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;
import static com.nyx.eforms.MetadataContractFactories.fieldDefinition;
import static com.nyx.eforms.MetadataContractFactories.searchQuery;
import static com.nyx.eforms.TestJsonSupport.jsonPrimitive;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EFormServiceTest {
    private Path tempDir;
    private DatabaseResources databaseResources;
    private Jdbi db;
    private HikariDataSource ds;
    private EFormService service;

    private final FieldDefinition textField = fieldDefinition("title", FieldType.TEXT, true);
    private final FieldDefinition numberField = fieldDefinition("year", FieldType.NUMBER);

    @BeforeEach
    void setup() throws IOException {
        tempDir = Files.createTempDirectory("nyx-eform-service-test");
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
    void createFormReturnsVersion1WithCorrectFields() {
        FormDefinition form = service.createForm(
            "Movie Info",
            Set.of(MediaType.VIDEO),
            List.of(textField, numberField)
        );

        assertEquals("Movie Info", form.name());
        assertEquals(1, form.currentVersion());
        assertEquals(Set.of(MediaType.VIDEO), form.mediaTypes());
        assertEquals(1, form.versions().size());
        assertEquals(2, form.versions().get(0).fields().size());
        assertEquals("title", form.versions().get(0).fields().get(0).name());
        assertTrue(form.versions().get(0).fields().get(0).required());
    }

    @Test
    void getFormReturnsAllVersions() {
        FormDefinition created = service.createForm(
            "Test Form",
            Set.of(MediaType.VIDEO),
            List.of(textField)
        );

        service.updateForm(created.id(), List.of(textField, numberField));

        FormDefinition form = service.getForm(created.id());
        assertNotNull(form);
        assertEquals(2, form.currentVersion());
        assertEquals(2, form.versions().size());
        assertEquals(1, form.versions().get(0).fields().size());
        assertEquals(2, form.versions().get(1).fields().size());
    }

    @Test
    void getFormReturnsNullForNonExistentForm() {
        assertNull(service.getForm("non-existent"));
    }

    @Test
    void listFormsReturnsCurrentVersionOnly() {
        FormDefinition form1 = service.createForm(
            "Form A",
            Set.of(MediaType.VIDEO),
            List.of(textField)
        );
        service.updateForm(form1.id(), List.of(textField, numberField));

        service.createForm(
            "Form B",
            Set.of(MediaType.AUDIO),
            List.of(textField)
        );

        List<FormDefinition> forms = service.listForms();
        assertEquals(2, forms.size());

        FormDefinition formA = forms.stream()
            .filter(form -> form.name().equals("Form A"))
            .findFirst()
            .orElseThrow();
        assertEquals(2, formA.currentVersion());
        assertEquals(1, formA.versions().size());
        assertEquals(2, formA.versions().get(0).fields().size());
    }

    @Test
    void updateFormIncrementsVersionAndProducesCorrectDiff() {
        FormDefinition created = service.createForm(
            "Test",
            Set.of(MediaType.VIDEO),
            List.of(textField, numberField)
        );

        FieldDefinition boolField = fieldDefinition("watched", FieldType.BOOLEAN);
        var update = service.updateForm(created.id(), List.of(textField, boolField));
        FormDefinition updated = update.getKey();
        VersionDiff diff = update.getValue();

        assertEquals(2, updated.currentVersion());
        assertEquals(1, diff.getAdded().size());
        assertEquals("watched", diff.getAdded().get(0).getFieldName());
        assertEquals(1, diff.getRemoved().size());
        assertEquals("year", diff.getRemoved().get(0).getFieldName());
    }

    @Test
    void updateNonExistentFormThrows() {
        NyxException ex = assertThrows(NyxException.class, () -> service.updateForm("non-existent", List.of(textField)));
        assertEquals(ErrorCode.FORM_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void deleteFormWithDeleteMetadataCascades() {
        FormDefinition form = service.createForm(
            "To Delete",
            Set.of(MediaType.VIDEO),
            List.of(textField)
        );

        service.deleteForm(form.id(), true);

        assertNull(service.getForm(form.id()));
        int formVersions = withHandleUnchecked(db, handle -> handle
            .createQuery("SELECT COUNT(*) FROM form_versions")
            .mapTo(Integer.class)
            .one());
        assertEquals(0, formVersions);
    }

    @Test
    void deleteFormWithoutDeleteMetadataDeletesFormOnly() {
        FormDefinition form = service.createForm(
            "To Delete",
            Set.of(MediaType.VIDEO),
            List.of(textField)
        );

        service.deleteForm(form.id(), false);

        assertNull(service.getForm(form.id()));
    }

    @Test
    void deleteNonExistentFormThrows() {
        NyxException ex = assertThrows(NyxException.class, () -> service.deleteForm("non-existent"));
        assertEquals(ErrorCode.FORM_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void invalidFieldsRejectedWhenEmptyList() {
        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.createForm("Test", Set.of(MediaType.VIDEO), List.of())
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void invalidFieldsRejectedWhenDuplicateNames() {
        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.createForm(
                "Test",
                Set.of(MediaType.VIDEO),
                List.of(
                    textField,
                    new FieldDefinition(
                        textField.name(),
                        textField.type(),
                        textField.required(),
                        textField.options(),
                        textField.defaultValue()
                    )
                )
            )
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("Duplicate"));
    }

    @Test
    void invalidFieldsRejectedWhenSelectWithoutOptions() {
        FieldDefinition badField = fieldDefinition("genre", FieldType.SELECT);
        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.createForm("Test", Set.of(MediaType.VIDEO), List.of(badField))
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("options"));
    }

    @Test
    void invalidFieldsRejectedWhenMultiSelectWithoutOptions() {
        FieldDefinition badField = fieldDefinition("tags", FieldType.MULTI_SELECT);
        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.createForm("Test", Set.of(MediaType.VIDEO), List.of(badField))
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void invalidFieldsRejectedWhenBlankName() {
        FieldDefinition badField = fieldDefinition("", FieldType.TEXT);
        NyxException ex = assertThrows(
            NyxException.class,
            () -> service.createForm("Test", Set.of(MediaType.VIDEO), List.of(badField))
        );
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void listFormsReturnsCorrectDataForMultipleForms() {
        FieldDefinition field = fieldDefinition("note", FieldType.TEXT);
        List<String> names = List.of("Form Alpha", "Form Beta", "Form Gamma", "Form Delta", "Form Epsilon");
        names.forEach(name -> service.createForm(name, Set.of(MediaType.VIDEO), List.of(field)));

        List<FormDefinition> listed = service.listForms();
        assertEquals(names.size(), listed.size());
        listed.forEach(form -> {
            assertEquals(1, form.currentVersion());
            assertEquals(1, form.versions().size());
            assertEquals("note", form.versions().get(0).fields().get(0).name());
        });
        assertEquals(Set.copyOf(names), listed.stream().map(FormDefinition::name).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void listFormsAfterUpdateReflectsNewCurrentVersionForAllForms() {
        FieldDefinition field = fieldDefinition("x", FieldType.TEXT);
        FieldDefinition updatedField = fieldDefinition("y", FieldType.TEXT);

        FormDefinition form1 = service.createForm("F1", Set.of(MediaType.VIDEO), List.of(field));
        FormDefinition form2 = service.createForm("F2", Set.of(MediaType.IMAGE), List.of(field));
        service.updateForm(form1.id(), List.of(updatedField));

        List<FormDefinition> listed = service.listForms();
        FormDefinition listedF1 = listed.stream().filter(form -> form.id().equals(form1.id())).findFirst().orElseThrow();
        FormDefinition listedF2 = listed.stream().filter(form -> form.id().equals(form2.id())).findFirst().orElseThrow();

        assertEquals(2, listedF1.currentVersion());
        assertEquals("y", listedF1.versions().get(0).fields().get(0).name());
        assertEquals(1, listedF2.currentVersion());
        assertEquals("x", listedF2.versions().get(0).fields().get(0).name());
    }

    @Test
    void listFormsReturnsEmptyListWhenNoFormsExist() {
        assertTrue(service.listForms().isEmpty());
    }

    @Test
    void searchWithApostropheInQueryTextUsesFtsEscape() {
        FormDefinition form = service.createForm(
            "Books",
            Set.of(MediaType.VIDEO),
            List.of(fieldDefinition("title", FieldType.TEXT, true))
        );

        service.attachMetadata(
            "/media/book1.pdf",
            form.id(),
            java.util.Map.of("title", jsonPrimitive("Harry Potter and the Philosopher's Stone"))
        );
        service.attachMetadata(
            "/media/book2.pdf",
            form.id(),
            java.util.Map.of("title", jsonPrimitive("It's a Wonderful Life"))
        );

        var result = service.search(searchQuery("Philosopher's"));

        assertNotNull(result);
        assertFalse(result.results().isEmpty());
    }

    @Test
    void searchWithDoubleQuotesInQueryText() {
        FormDefinition form = service.createForm(
            "Quotes",
            Set.of(MediaType.VIDEO),
            List.of(fieldDefinition("content", FieldType.TEXT, true))
        );

        service.attachMetadata(
            "/media/quote1.txt",
            form.id(),
            java.util.Map.of("content", jsonPrimitive("She said hello world loudly"))
        );

        assertDoesNotThrow(() -> service.search(searchQuery("hello\"world")));
    }
}
