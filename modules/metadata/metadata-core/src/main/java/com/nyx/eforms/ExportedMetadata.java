package com.nyx.eforms;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ExportedMetadata {
    private final String mediaPath;
    private final List<ExportedMetadataEntry> entries;

    @JsonCreator
    public ExportedMetadata(
        @JsonProperty("mediaPath") String mediaPath,
        @JsonProperty("entries") List<ExportedMetadataEntry> entries
    ) {
        this.mediaPath = mediaPath;
        this.entries = List.copyOf(entries);
    }

    public String getMediaPath() {
        return mediaPath;
    }

    public List<ExportedMetadataEntry> getEntries() {
        return entries;
    }
}
