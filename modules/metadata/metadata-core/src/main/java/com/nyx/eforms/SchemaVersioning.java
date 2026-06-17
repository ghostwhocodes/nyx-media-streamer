package com.nyx.eforms;

import com.nyx.eforms.model.FieldDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SchemaVersioning {
    private SchemaVersioning() {
    }

    public static VersionDiff diffFields(List<FieldDefinition> oldFields, List<FieldDefinition> newFields) {
        return diffFields(oldFields, newFields, 0, 1);
    }

    public static VersionDiff diffFields(
        List<FieldDefinition> oldFields,
        List<FieldDefinition> newFields,
        int oldVersion,
        int newVersion
    ) {
        Map<String, FieldDefinition> oldByName = oldFields.stream()
            .collect(Collectors.toMap(FieldDefinition::name, field -> field, (left, right) -> left, LinkedHashMap::new));
        Map<String, FieldDefinition> newByName = newFields.stream()
            .collect(Collectors.toMap(FieldDefinition::name, field -> field, (left, right) -> left, LinkedHashMap::new));

        List<FieldChange> changes = new ArrayList<>();

        for (FieldDefinition field : newFields) {
            if (!oldByName.containsKey(field.name())) {
                changes.add(new FieldChange(
                    field.name(),
                    ChangeType.ADDED,
                    "added with type " + field.type(),
                    null,
                    field
                ));
            }
        }

        for (FieldDefinition field : oldFields) {
            if (!newByName.containsKey(field.name())) {
                changes.add(new FieldChange(
                    field.name(),
                    ChangeType.REMOVED,
                    "removed",
                    field,
                    null
                ));
            }
        }

        for (FieldDefinition field : newFields) {
            FieldDefinition oldField = oldByName.get(field.name());
            if (oldField != null && !Objects.equals(oldField, field)) {
                List<String> details = new ArrayList<>();
                if (oldField.type() != field.type()) {
                    details.add("type " + oldField.type() + " -> " + field.type());
                }
                if (oldField.required() != field.required()) {
                    details.add("required " + oldField.required() + " -> " + field.required());
                }
                if (!Objects.equals(oldField.options(), field.options())) {
                    details.add("options changed");
                }
                if (!Objects.equals(oldField.defaultValue(), field.defaultValue())) {
                    details.add("defaultValue changed");
                }
                changes.add(new FieldChange(
                    field.name(),
                    ChangeType.MODIFIED,
                    String.join("; ", details),
                    oldField,
                    field
                ));
            }
        }

        List<String> commonNames = oldFields.stream()
            .map(FieldDefinition::name)
            .filter(newByName::containsKey)
            .toList();
        List<String> newOrder = newFields.stream()
            .map(FieldDefinition::name)
            .filter(oldByName::containsKey)
            .toList();
        if (!commonNames.equals(newOrder)) {
            changes.add(new FieldChange("*", ChangeType.REORDERED, "field order changed"));
        }

        return new VersionDiff(oldVersion, newVersion, changes);
    }
}
