package com.nyx.eforms;

import static com.nyx.eforms.MetadataContractFactories.fieldDefinition;
import static com.nyx.eforms.TestJsonSupport.jsonPrimitive;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.eforms.model.FieldType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaVersioningTest {
    @Test
    void diffFieldsDetectsAddedFields() {
        var oldFields = List.of(fieldDefinition("title", FieldType.TEXT, true));
        var newFields = List.of(
            fieldDefinition("title", FieldType.TEXT, true),
            fieldDefinition("description", FieldType.TEXT)
        );

        VersionDiff diff = SchemaVersioning.diffFields(oldFields, newFields);
        assertEquals(1, diff.getAdded().size());
        assertEquals("description", diff.getAdded().get(0).getFieldName());
        assertTrue(diff.getAdded().get(0).getDetail().contains("added"));
        assertTrue(diff.getRemoved().isEmpty());
        assertTrue(diff.getModified().isEmpty());
    }

    @Test
    void diffFieldsDetectsRemovedFields() {
        var oldFields = List.of(
            fieldDefinition("title", FieldType.TEXT, true),
            fieldDefinition("tags", FieldType.TEXT)
        );
        var newFields = List.of(fieldDefinition("title", FieldType.TEXT, true));

        VersionDiff diff = SchemaVersioning.diffFields(oldFields, newFields);
        assertTrue(diff.getAdded().isEmpty());
        assertEquals(1, diff.getRemoved().size());
        assertEquals("tags", diff.getRemoved().get(0).getFieldName());
        assertTrue(diff.getRemoved().get(0).getDetail().contains("removed"));
        assertTrue(diff.getModified().isEmpty());
    }

    @Test
    void diffFieldsDetectsTypeChanges() {
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(fieldDefinition("rating", FieldType.TEXT)),
            List.of(fieldDefinition("rating", FieldType.NUMBER))
        );

        assertTrue(diff.getAdded().isEmpty());
        assertTrue(diff.getRemoved().isEmpty());
        assertEquals(1, diff.getModified().size());
        assertEquals("rating", diff.getModified().get(0).getFieldName());
        assertTrue(diff.getModified().get(0).getDetail().contains("type"));
    }

    @Test
    void diffFieldsDetectsRequiredChanges() {
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(fieldDefinition("title", FieldType.TEXT)),
            List.of(fieldDefinition("title", FieldType.TEXT, true))
        );

        assertEquals(1, diff.getModified().size());
        assertTrue(diff.getModified().get(0).getDetail().contains("required"));
    }

    @Test
    void diffFieldsDetectsOptionsChanges() {
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(fieldDefinition("genre", FieldType.SELECT, false, List.of("a", "b"))),
            List.of(fieldDefinition("genre", FieldType.SELECT, false, List.of("a", "b", "c")))
        );

        assertEquals(1, diff.getModified().size());
        assertTrue(diff.getModified().get(0).getDetail().contains("options"));
    }

    @Test
    void diffFieldsDetectsDefaultValueChanges() {
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(fieldDefinition("status", FieldType.TEXT, false, null, jsonPrimitive("draft"))),
            List.of(fieldDefinition("status", FieldType.TEXT, false, null, jsonPrimitive("published")))
        );

        assertEquals(1, diff.getModified().size());
        assertTrue(diff.getModified().get(0).getDetail().contains("defaultValue"));
    }

    @Test
    void diffFieldsDetectsMultipleChangesOnSameField() {
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(fieldDefinition("rating", FieldType.TEXT)),
            List.of(fieldDefinition("rating", FieldType.NUMBER, true))
        );

        assertEquals(1, diff.getModified().size());
        assertTrue(diff.getModified().get(0).getDetail().contains("type"));
        assertTrue(diff.getModified().get(0).getDetail().contains("required"));
    }

    @Test
    void diffFieldsReturnsEmptyDiffForIdenticalFields() {
        var fields = List.of(
            fieldDefinition("title", FieldType.TEXT, true),
            fieldDefinition("tags", FieldType.TEXT)
        );

        VersionDiff diff = SchemaVersioning.diffFields(fields, fields);
        assertTrue(diff.getAdded().isEmpty());
        assertTrue(diff.getRemoved().isEmpty());
        assertTrue(diff.getModified().isEmpty());
    }

    @Test
    void diffFieldsHandlesEmptyOldAndNewLists() {
        VersionDiff diff = SchemaVersioning.diffFields(List.of(), List.of());
        assertTrue(diff.getAdded().isEmpty());
        assertTrue(diff.getRemoved().isEmpty());
        assertTrue(diff.getModified().isEmpty());
    }

    @Test
    void diffFieldsHandlesAddRemoveAndModifySimultaneously() {
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(
                fieldDefinition("title", FieldType.TEXT, true),
                fieldDefinition("removed_field", FieldType.TEXT)
            ),
            List.of(
                fieldDefinition("title", FieldType.TEXT),
                fieldDefinition("added_field", FieldType.NUMBER, true)
            )
        );

        assertEquals(1, diff.getAdded().size());
        assertEquals(1, diff.getRemoved().size());
        assertEquals(1, diff.getModified().size());
        assertEquals("added_field", diff.getAdded().get(0).getFieldName());
        assertEquals("removed_field", diff.getRemoved().get(0).getFieldName());
        assertEquals("title", diff.getModified().get(0).getFieldName());
    }

    @Test
    void fieldChangeDataClassWorksCorrectly() {
        FieldChange change = new FieldChange("name", ChangeType.ADDED, "detail");
        assertEquals("name", change.getFieldName());
        assertEquals(ChangeType.ADDED, change.getChangeType());
        assertEquals("detail", change.getDetail());
    }

    @Test
    void versionDiffDataClassWorksCorrectly() {
        VersionDiff diff = new VersionDiff(
            0,
            1,
            List.of(
                new FieldChange("a", ChangeType.ADDED, "added"),
                new FieldChange("b", ChangeType.REMOVED, "removed"),
                new FieldChange("c", ChangeType.MODIFIED, "changed")
            )
        );
        assertEquals(1, diff.getAdded().size());
        assertEquals(1, diff.getRemoved().size());
        assertEquals(1, diff.getModified().size());
    }

    @Test
    void diffFieldsWithExplicitVersionsSetsVersionNumbers() {
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(fieldDefinition("title", FieldType.TEXT, true)),
            List.of(
                fieldDefinition("title", FieldType.TEXT, true),
                fieldDefinition("body", FieldType.TEXT)
            ),
            3,
            4
        );

        assertEquals(3, diff.getOldVersion());
        assertEquals(4, diff.getNewVersion());
        assertEquals(1, diff.getAdded().size());
    }

    @Test
    void diffFieldsDetectsFieldReordering() {
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(
                fieldDefinition("title", FieldType.TEXT, true),
                fieldDefinition("body", FieldType.TEXT)
            ),
            List.of(
                fieldDefinition("body", FieldType.TEXT),
                fieldDefinition("title", FieldType.TEXT, true)
            )
        );

        long reordered = diff.getChanges().stream()
            .filter(change -> change.getChangeType() == ChangeType.REORDERED)
            .count();
        assertEquals(1, reordered);
    }

    @Test
    void diffFieldsPopulatesNewValueForAddedFields() {
        var added = fieldDefinition("description", FieldType.TEXT);
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(fieldDefinition("title", FieldType.TEXT, true)),
            List.of(fieldDefinition("title", FieldType.TEXT, true), added)
        );

        assertEquals(1, diff.getAdded().size());
        assertNull(diff.getAdded().get(0).getOldValue());
        assertEquals(added, diff.getAdded().get(0).getNewValue());
    }

    @Test
    void diffFieldsPopulatesOldValueForRemovedFields() {
        var removed = fieldDefinition("tags", FieldType.TEXT);
        VersionDiff diff = SchemaVersioning.diffFields(
            List.of(fieldDefinition("title", FieldType.TEXT, true), removed),
            List.of(fieldDefinition("title", FieldType.TEXT, true))
        );

        assertEquals(1, diff.getRemoved().size());
        assertEquals(removed, diff.getRemoved().get(0).getOldValue());
        assertNull(diff.getRemoved().get(0).getNewValue());
    }

    @Test
    void diffFieldsPopulatesOldValueAndNewValueForModifiedFields() {
        var oldField = fieldDefinition("rating", FieldType.TEXT);
        var newField = fieldDefinition("rating", FieldType.NUMBER, true);

        VersionDiff diff = SchemaVersioning.diffFields(List.of(oldField), List.of(newField));
        assertEquals(1, diff.getModified().size());
        assertEquals(oldField, diff.getModified().get(0).getOldValue());
        assertEquals(newField, diff.getModified().get(0).getNewValue());
    }

    @Test
    void fieldChangeOldValueAndNewValueDefaultToNull() {
        FieldChange change = new FieldChange("name", ChangeType.ADDED, "detail");
        assertNull(change.getOldValue());
        assertNull(change.getNewValue());
    }
}
