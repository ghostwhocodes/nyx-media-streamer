package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackRequest(
    MediaSourceRef source,
    long startPositionMillis,
    PlaybackOutputPreferences output,
    PlaybackClientProfile clientProfile,
    PlaybackCapabilitySet capabilities,
    PlaybackSelection selection,
    PlaybackConstraints constraints,
    TranscodePreferences transcode
) {
    public PlaybackRequest(MediaSourceRef source) {
        this(source, 0L, new PlaybackOutputPreferences(), null, null, new PlaybackSelection(), new PlaybackConstraints(), new TranscodePreferences());
    }

    public PlaybackRequest {
        output = output == null ? new PlaybackOutputPreferences() : output;
        selection = selection == null ? new PlaybackSelection() : selection;
        constraints = constraints == null ? new PlaybackConstraints() : constraints;
        transcode = transcode == null ? new TranscodePreferences() : transcode;
    }
}
