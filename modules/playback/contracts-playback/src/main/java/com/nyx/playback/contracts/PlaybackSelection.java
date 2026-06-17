package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackSelection(
    AudioTrackSelection audio,
    SubtitleSelection subtitles
) {
    public PlaybackSelection() {
        this(new AudioTrackSelection(), new SubtitleSelection());
    }

    public PlaybackSelection {
        audio = audio == null ? new AudioTrackSelection() : audio;
        subtitles = subtitles == null ? new SubtitleSelection() : subtitles;
    }
}
