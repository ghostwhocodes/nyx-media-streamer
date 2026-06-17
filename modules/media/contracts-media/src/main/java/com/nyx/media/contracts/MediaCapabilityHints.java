package com.nyx.media.contracts;

public record MediaCapabilityHints(
    boolean directPlayAvailable,
    boolean transcodeRequired,
    Double durationSeconds,
    String mimeType,
    String objectId
) {
    public MediaCapabilityHints() {
        this(false, false, null, null, null);
    }
}
