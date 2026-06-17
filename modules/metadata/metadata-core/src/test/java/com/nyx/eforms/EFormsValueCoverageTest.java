package com.nyx.eforms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FieldType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EFormsValueCoverageTest {

    @Test
    void fieldChangesAndVersionDiffRemainValueBased() {
        FieldDefinition oldField = new FieldDefinition("rating", FieldType.NUMBER, true);
        FieldDefinition newField = new FieldDefinition("rating", FieldType.TEXT, true);
        FieldChange added = new FieldChange("title", ChangeType.ADDED, null);
        FieldChange removed = new FieldChange("genre", ChangeType.REMOVED, "deleted");
        FieldChange modified = new FieldChange("rating", ChangeType.MODIFIED, "type changed", oldField, newField);
        List<FieldChange> changes = new ArrayList<>(List.of(added, removed, modified));
        VersionDiff diff = new VersionDiff(1, 2, changes);

        changes.add(new FieldChange("ignored", ChangeType.ADDED, "later"));

        assertEquals("title", added.getFieldName());
        assertEquals(ChangeType.ADDED, added.getChangeType());
        assertEquals("", added.getDetail());
        assertNull(added.getOldValue());
        assertNull(added.getNewValue());
        assertEquals(modified, new FieldChange("rating", ChangeType.MODIFIED, "type changed", oldField, newField));
        assertEquals(modified.hashCode(), new FieldChange("rating", ChangeType.MODIFIED, "type changed", oldField, newField).hashCode());
        assertTrue(modified.toString().contains("rating"));

        assertEquals(1, diff.getOldVersion());
        assertEquals(2, diff.getNewVersion());
        assertEquals(List.of(added, removed, modified), diff.getChanges());
        assertEquals(List.of(added), diff.getAdded());
        assertEquals(List.of(removed), diff.getRemoved());
        assertEquals(List.of(modified), diff.getModified());
        assertThrows(UnsupportedOperationException.class, () -> diff.getChanges().add(added));
        assertTrue(diff.toString().contains("oldVersion=1"));
    }

    @Test
    void relocationAndBindingHoldersExposeStoredValues() {
        List<RelocationPreview> previews = new ArrayList<>(List.of(new RelocationPreview("/from", "/to")));
        RelocationResult countOnly = new RelocationResult(2);
        RelocationResult withPreviews = new RelocationResult(1, previews);
        EFormsBindings bindings = new EFormsBindings(null, null, null, null);
        EFormsPersistenceResources resources = new EFormsPersistenceResources(null, null);

        previews.add(new RelocationPreview("/later", "/ignored"));

        assertEquals(2, countOnly.getUpdated());
        assertNull(countOnly.getPreviews());
        assertEquals(List.of(new RelocationPreview("/from", "/to")), withPreviews.getPreviews());
        assertEquals(withPreviews, new RelocationResult(1, List.of(new RelocationPreview("/from", "/to"))));
        assertEquals(withPreviews.hashCode(), new RelocationResult(1, List.of(new RelocationPreview("/from", "/to"))).hashCode());
        assertTrue(withPreviews.toString().contains("updated=1"));
        assertThrows(UnsupportedOperationException.class, () -> withPreviews.getPreviews().add(new RelocationPreview("/x", "/y")));

        assertNull(bindings.getResources());
        assertNull(bindings.getEFormService());
        assertNull(bindings.getExportImportService());
        assertNull(bindings.getRelocationService());

        assertNull(resources.getJdbi());
        assertNull(resources.getDataSource());
    }
}
