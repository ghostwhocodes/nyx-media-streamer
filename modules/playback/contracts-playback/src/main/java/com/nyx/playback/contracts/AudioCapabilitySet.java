package com.nyx.playback.contracts;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AudioCapabilitySet(
    Set<String> supportedMimeTypes,
    Set<String> supportedContainers,
    Set<String> supportedAudioCodecs,
    boolean allowDirectPlay,
    boolean allowTranscode
) {
    public AudioCapabilitySet() {
        this(Set.of(), Set.of(), Set.of(), true, true);
    }

    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static AudioCapabilitySet create(
        @JsonProperty("supportedMimeTypes") Set<String> supportedMimeTypes,
        @JsonProperty("supportedContainers") Set<String> supportedContainers,
        @JsonProperty("supportedAudioCodecs") Set<String> supportedAudioCodecs,
        @JsonProperty("allowDirectPlay") Boolean allowDirectPlay,
        @JsonProperty("allowTranscode") Boolean allowTranscode
    ) {
        return new AudioCapabilitySet(
            supportedMimeTypes,
            supportedContainers,
            supportedAudioCodecs,
            allowDirectPlay == null || allowDirectPlay,
            allowTranscode == null || allowTranscode
        );
    }

    public AudioCapabilitySet {
        supportedMimeTypes = PlaybackContractSupport.immutableSet(supportedMimeTypes);
        supportedContainers = PlaybackContractSupport.immutableSet(supportedContainers);
        supportedAudioCodecs = PlaybackContractSupport.immutableSet(supportedAudioCodecs);
    }
}
