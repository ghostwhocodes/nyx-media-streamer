package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackDecision(
    PlaybackMode mode,
    StreamDescriptor stream,
    Set<PlaybackReason> reasons,
    boolean videoPreserved,
    boolean audioPreserved,
    SubtitleDelivery subtitleDelivery,
    PlaybackOutputSummary output
) {
    public PlaybackDecision(PlaybackMode mode, StreamDescriptor stream) {
        this(mode, stream, Set.of(), false, false, SubtitleDelivery.NONE, new PlaybackOutputSummary());
    }

    public PlaybackDecision {
        reasons = PlaybackContractSupport.immutableSet(reasons);
        subtitleDelivery = subtitleDelivery == null ? SubtitleDelivery.NONE : subtitleDelivery;
        output = output == null ? new PlaybackOutputSummary() : output;
    }
}
