package com.nyx.media.contracts;

public record UserMediaStateWriteRequest(
    Long resumePositionMillis,
    boolean watched,
    boolean favorite,
    Integer rating
) {
    public UserMediaStateWriteRequest() {
        this(null, false, false, null);
    }
}
