package com.nyx.playback;

import com.nyx.ffmpeg.MediaProber;
import com.nyx.playback.contracts.AudioNegotiationService;
import com.nyx.playback.contracts.AudioSessionService;
import com.nyx.playback.contracts.MediaPlaystateProjector;
import com.nyx.playback.contracts.MediaSessionReportService;
import com.nyx.playback.contracts.PlaybackDeliveryService;
import com.nyx.playback.contracts.PlaybackDecisionService;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;

public final class PlaybackRuntimeModule {
    private static final long DEFAULT_TERMINAL_SESSION_RETENTION_MS = 5 * 60_000L;

    private PlaybackRuntimeModule() {}

    public static PlaybackRuntimeBindings createPlaybackRuntimeBindings(
        MediaProber mediaProber,
        TranscodeApplicationService transcodeApplicationService,
        AudioNegotiationService audioNegotiationService,
        MediaPlaystateProjector mediaPlaystateProjector
    ) {
        return createPlaybackRuntimeBindings(
            mediaProber,
            transcodeApplicationService,
            audioNegotiationService,
            mediaPlaystateProjector,
            null
        );
    }

    public static PlaybackRuntimeBindings createPlaybackRuntimeBindings(
        MediaProber mediaProber,
        TranscodeApplicationService transcodeApplicationService,
        AudioNegotiationService audioNegotiationService,
        MediaPlaystateProjector mediaPlaystateProjector,
        ScheduledExecutorService cleanupScheduler
    ) {
        AudioSessionService audioSessionService = new LocalAudioSessionService(
            audioNegotiationService,
            cleanupScheduler,
            DEFAULT_TERMINAL_SESSION_RETENTION_MS,
            Clock.systemUTC()
        );
        PlaybackDecisionService playbackDecisionService = new LocalPlaybackDecisionService(mediaProber);
        PlaybackSessionService playbackSessionService = new LocalPlaybackSessionService(
            playbackDecisionService,
            transcodeApplicationService,
            cleanupScheduler,
            DEFAULT_TERMINAL_SESSION_RETENTION_MS,
            Clock.systemUTC()
        );
        PlaybackDeliveryService playbackDeliveryService = new LocalPlaybackDeliveryService(
            playbackSessionService,
            cleanupScheduler,
            Clock.systemUTC()
        );
        MediaSessionReportService mediaSessionReportService = new LocalMediaSessionReportService(
            audioSessionService,
            playbackSessionService,
            mediaPlaystateProjector
        );
        return new PlaybackRuntimeBindings(
            audioSessionService,
            playbackDecisionService,
            playbackSessionService,
            playbackDeliveryService,
            mediaSessionReportService
        );
    }
}
