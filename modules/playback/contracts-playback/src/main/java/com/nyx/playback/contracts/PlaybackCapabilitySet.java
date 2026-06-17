package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackCapabilitySet(
    Set<String> supportedContainers,
    Set<String> supportedVideoCodecs,
    Set<String> supportedAudioCodecs,
    Set<String> supportedSubtitleFormats,
    boolean allowDirectPlay,
    boolean allowRemux,
    boolean allowAudioTranscode,
    boolean allowVideoTranscode,
    boolean allowSubtitleBurnIn
) {
    public PlaybackCapabilitySet() {
        this(Set.of(), Set.of(), Set.of(), Set.of(), true, true, true, true, true);
    }

    public PlaybackCapabilitySet {
        supportedContainers = PlaybackContractSupport.immutableSet(supportedContainers);
        supportedVideoCodecs = PlaybackContractSupport.immutableSet(supportedVideoCodecs);
        supportedAudioCodecs = PlaybackContractSupport.immutableSet(supportedAudioCodecs);
        supportedSubtitleFormats = PlaybackContractSupport.immutableSet(supportedSubtitleFormats);
    }
}
