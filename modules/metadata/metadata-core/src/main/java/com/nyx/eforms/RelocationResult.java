package com.nyx.eforms;

import java.util.List;
import java.util.Objects;

public final class RelocationResult {
    private final int updated;
    private final List<RelocationPreview> previews;

    public RelocationResult(int updated) {
        this(updated, null);
    }

    public RelocationResult(int updated, List<RelocationPreview> previews) {
        this.updated = updated;
        this.previews = previews == null ? null : List.copyOf(previews);
    }

    public int getUpdated() {
        return updated;
    }

    public List<RelocationPreview> getPreviews() {
        return previews;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RelocationResult that)) {
            return false;
        }
        return updated == that.updated && Objects.equals(previews, that.previews);
    }

    @Override
    public int hashCode() {
        return Objects.hash(updated, previews);
    }

    @Override
    public String toString() {
        return "RelocationResult{"
            + "updated=" + updated
            + ", previews=" + previews
            + '}';
    }
}
