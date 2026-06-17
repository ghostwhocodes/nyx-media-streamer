package com.nyx.eforms;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ImportResult {
    private final int formsCreated;
    private final int formsSkipped;
    private final int metadataCreated;
    private final int metadataUpdated;
    private final int metadataSkipped;
    private final List<String> errors;

    @JsonCreator
    public ImportResult(
        @JsonProperty("formsCreated") int formsCreated,
        @JsonProperty("formsSkipped") int formsSkipped,
        @JsonProperty("metadataCreated") int metadataCreated,
        @JsonProperty("metadataUpdated") int metadataUpdated,
        @JsonProperty("metadataSkipped") int metadataSkipped,
        @JsonProperty("errors") List<String> errors
    ) {
        this.formsCreated = formsCreated;
        this.formsSkipped = formsSkipped;
        this.metadataCreated = metadataCreated;
        this.metadataUpdated = metadataUpdated;
        this.metadataSkipped = metadataSkipped;
        this.errors = List.copyOf(errors);
    }

    public int getFormsCreated() {
        return formsCreated;
    }

    public int getFormsSkipped() {
        return formsSkipped;
    }

    public int getMetadataCreated() {
        return metadataCreated;
    }

    public int getMetadataUpdated() {
        return metadataUpdated;
    }

    public int getMetadataSkipped() {
        return metadataSkipped;
    }

    public List<String> getErrors() {
        return errors;
    }
}
