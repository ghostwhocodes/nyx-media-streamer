package com.nyx.ffmpeg.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record FfprobeStream(
    int index,
    String codecName,
    String codecType,
    Integer width,
    Integer height,
    String rFrameRate,
    String avgFrameRate,
    String bitRate,
    Integer channels,
    String sampleRate,
    Map<String, String> tags
) {
    public FfprobeStream {
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public FfprobeStream() {
        this(0, null, null, null, null, null, null, null, null, null, Map.of());
    }

    public int getIndex() {
        return index;
    }

    public String getCodecName() {
        return codecName;
    }

    public String getCodecType() {
        return codecType;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public String getRFrameRate() {
        return rFrameRate;
    }

    public String getAvgFrameRate() {
        return avgFrameRate;
    }

    public String getBitRate() {
        return bitRate;
    }

    public Integer getChannels() {
        return channels;
    }

    public String getSampleRate() {
        return sampleRate;
    }

    public Map<String, String> getTags() {
        return tags;
    }
}
