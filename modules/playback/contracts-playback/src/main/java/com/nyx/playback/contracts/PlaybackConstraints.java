package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackConstraints(
    VideoConstraint video,
    AudioConstraint audio
) {
    public PlaybackConstraints() {
        this(new VideoConstraint(), new AudioConstraint());
    }

    public PlaybackConstraints {
        video = video == null ? new VideoConstraint() : video;
        audio = audio == null ? new AudioConstraint() : audio;
    }
}
