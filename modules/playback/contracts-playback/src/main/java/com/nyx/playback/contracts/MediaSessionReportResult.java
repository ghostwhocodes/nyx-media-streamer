package com.nyx.playback.contracts;

import com.nyx.media.contracts.UserMediaState;

public sealed interface MediaSessionReportResult permits MediaSessionReportResult.Audio, MediaSessionReportResult.Playback {
    record Audio(AudioSession session) implements MediaSessionReportResult {
    }

    record Playback(PlaybackSession session) implements MediaSessionReportResult {
    }
}
