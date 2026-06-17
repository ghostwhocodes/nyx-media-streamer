package com.nyx.eforms;

import java.util.List;
import java.util.Objects;

public final class VersionDiff {
    private final int oldVersion;
    private final int newVersion;
    private final List<FieldChange> changes;

    public VersionDiff(int oldVersion, int newVersion, List<FieldChange> changes) {
        this.oldVersion = oldVersion;
        this.newVersion = newVersion;
        this.changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }

    public int getOldVersion() {
        return oldVersion;
    }

    public int getNewVersion() {
        return newVersion;
    }

    public List<FieldChange> getChanges() {
        return changes;
    }

    public List<FieldChange> getAdded() {
        return changes.stream().filter(change -> change.getChangeType() == ChangeType.ADDED).toList();
    }

    public List<FieldChange> getRemoved() {
        return changes.stream().filter(change -> change.getChangeType() == ChangeType.REMOVED).toList();
    }

    public List<FieldChange> getModified() {
        return changes.stream().filter(change -> change.getChangeType() == ChangeType.MODIFIED).toList();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VersionDiff that)) {
            return false;
        }
        return oldVersion == that.oldVersion
            && newVersion == that.newVersion
            && changes.equals(that.changes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(oldVersion, newVersion, changes);
    }

    @Override
    public String toString() {
        return "VersionDiff{"
            + "oldVersion=" + oldVersion
            + ", newVersion=" + newVersion
            + ", changes=" + changes
            + '}';
    }
}
