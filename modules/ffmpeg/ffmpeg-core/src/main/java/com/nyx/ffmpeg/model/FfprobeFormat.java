package com.nyx.ffmpeg.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FfprobeFormat(
    String formatName,
    String duration,
    String size,
    Map<String, String> tags
) {
    public FfprobeFormat {
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public FfprobeFormat() {
        this(null, null, null, Map.of());
    }

    public String getFormatName() {
        return formatName;
    }

    public String getDuration() {
        return duration;
    }

    public String getSize() {
        return size;
    }

    public Map<String, String> getTags() {
        return tags;
    }
}
