package com.nyx.eforms;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public final class ExportManifest {
    private final Instant exportDate;
    private final int version;
    private final int formCount;
    private final int metadataCount;

    public ExportManifest(Instant exportDate, int formCount, int metadataCount) {
        this(exportDate, 1, formCount, metadataCount);
    }

    @JsonCreator
    public ExportManifest(
        @JsonProperty("exportDate") Instant exportDate,
        @JsonProperty("version") int version,
        @JsonProperty("formCount") int formCount,
        @JsonProperty("metadataCount") int metadataCount
    ) {
        this.exportDate = exportDate;
        this.version = version;
        this.formCount = formCount;
        this.metadataCount = metadataCount;
    }

    public Instant getExportDate() {
        return exportDate;
    }

    public int getVersion() {
        return version;
    }

    public int getFormCount() {
        return formCount;
    }

    public int getMetadataCount() {
        return metadataCount;
    }
}
