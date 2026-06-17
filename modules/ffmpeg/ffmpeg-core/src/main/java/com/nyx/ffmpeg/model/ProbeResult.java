package com.nyx.ffmpeg.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ProbeResult(
    String path,
    String format,
    double durationSecs,
    long sizeBytes,
    ProbeStreams streams,
    Map<String, String> tags
) {
    public ProbeResult {
        path = Objects.requireNonNull(path, "path");
        format = Objects.requireNonNull(format, "format");
        streams = Objects.requireNonNull(streams, "streams");
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public ProbeResult(
        String path,
        String format,
        double durationSecs,
        long sizeBytes,
        ProbeStreams streams
    ) {
        this(path, format, durationSecs, sizeBytes, streams, Map.of());
    }

    public String getPath() {
        return path;
    }

    public String getFormat() {
        return format;
    }

    public double getDurationSecs() {
        return durationSecs;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public ProbeStreams getStreams() {
        return streams;
    }

    public Map<String, String> getTags() {
        return tags;
    }
}
