package com.nyx.playback.contracts;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AudioOutputPreferences(
    List<String> preferredMimeTypes,
    List<String> preferredContainers,
    List<String> preferredAudioCodecs
) {
    public AudioOutputPreferences() {
        this(List.of(), List.of(), List.of());
    }

    public AudioOutputPreferences {
        preferredMimeTypes = List.copyOf(Objects.requireNonNullElse(preferredMimeTypes, List.of()));
        preferredContainers = List.copyOf(Objects.requireNonNullElse(preferredContainers, List.of()));
        preferredAudioCodecs = List.copyOf(Objects.requireNonNullElse(preferredAudioCodecs, List.of()));
    }
}
