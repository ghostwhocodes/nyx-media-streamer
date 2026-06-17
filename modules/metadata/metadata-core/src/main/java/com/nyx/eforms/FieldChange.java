package com.nyx.eforms;

import com.nyx.eforms.model.FieldDefinition;
import java.util.Objects;

public final class FieldChange {
    private final String fieldName;
    private final ChangeType changeType;
    private final String detail;
    private final FieldDefinition oldValue;
    private final FieldDefinition newValue;

    public FieldChange(String fieldName, ChangeType changeType, String detail) {
        this(fieldName, changeType, detail, null, null);
    }

    public FieldChange(
        String fieldName,
        ChangeType changeType,
        String detail,
        FieldDefinition oldValue,
        FieldDefinition newValue
    ) {
        this.fieldName = Objects.requireNonNull(fieldName, "fieldName");
        this.changeType = Objects.requireNonNull(changeType, "changeType");
        this.detail = detail == null ? "" : detail;
        this.oldValue = oldValue;
        this.newValue = newValue;
    }

    public String getFieldName() {
        return fieldName;
    }

    public ChangeType getChangeType() {
        return changeType;
    }

    public String getDetail() {
        return detail;
    }

    public FieldDefinition getOldValue() {
        return oldValue;
    }

    public FieldDefinition getNewValue() {
        return newValue;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FieldChange that)) {
            return false;
        }
        return fieldName.equals(that.fieldName)
            && changeType == that.changeType
            && detail.equals(that.detail)
            && Objects.equals(oldValue, that.oldValue)
            && Objects.equals(newValue, that.newValue);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, changeType, detail, oldValue, newValue);
    }

    @Override
    public String toString() {
        return "FieldChange{"
            + "fieldName='" + fieldName + '\''
            + ", changeType=" + changeType
            + ", detail='" + detail + '\''
            + ", oldValue=" + oldValue
            + ", newValue=" + newValue
            + '}';
    }
}
