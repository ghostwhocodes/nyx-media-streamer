package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record TranscodePreferences(
    String profileHint,
    HardwareAccelerationPreference hardwareAcceleration,
    List<RepresentationConstraint> explicitRepresentations
) {
    public TranscodePreferences() {
        this(null, HardwareAccelerationPreference.AUTO, List.of());
    }

    public TranscodePreferences {
        hardwareAcceleration = hardwareAcceleration == null ? HardwareAccelerationPreference.AUTO : hardwareAcceleration;
        explicitRepresentations = PlaybackContractSupport.immutableList(explicitRepresentations);
    }
}
