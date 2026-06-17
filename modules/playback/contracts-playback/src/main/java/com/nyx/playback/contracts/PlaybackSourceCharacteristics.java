package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackSourceCharacteristics(
    String container,
    Long durationMillis,
    Long sizeBytes,
    List<PlaybackSourceVideoStream> videoStreams,
    List<PlaybackSourceAudioStream> audioStreams,
    List<PlaybackSourceSubtitleStream> subtitleStreams
) {
    public PlaybackSourceCharacteristics(String container) {
        this(container, null, null, List.of(), List.of(), List.of());
    }

    public PlaybackSourceCharacteristics {
        videoStreams = PlaybackContractSupport.immutableList(videoStreams);
        audioStreams = PlaybackContractSupport.immutableList(audioStreams);
        subtitleStreams = PlaybackContractSupport.immutableList(subtitleStreams);
    }
}
