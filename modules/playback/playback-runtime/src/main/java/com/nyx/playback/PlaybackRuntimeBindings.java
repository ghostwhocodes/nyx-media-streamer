package com.nyx.playback;

import com.nyx.playback.contracts.AudioSessionService;
import com.nyx.playback.contracts.MediaSessionReportService;
import com.nyx.playback.contracts.PlaybackDeliveryService;
import com.nyx.playback.contracts.PlaybackDecisionService;
import com.nyx.playback.contracts.PlaybackSessionService;

public record PlaybackRuntimeBindings(
    AudioSessionService audioSessionService,
    PlaybackDecisionService playbackDecisionService,
    PlaybackSessionService playbackSessionService,
    PlaybackDeliveryService playbackDeliveryService,
    MediaSessionReportService mediaSessionReportService
) {}
