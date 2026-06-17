package com.nyx.media.contracts;

public record UserMediaStateEntry(
    UserMediaStateMediaSummary media,
    UserMediaState state
) {
}
