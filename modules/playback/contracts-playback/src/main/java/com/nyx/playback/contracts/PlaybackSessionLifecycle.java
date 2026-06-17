package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;

public record PlaybackSessionLifecycle(
    PlaybackLifecyclePhase phase,
    String startedAt,
    String readyAt,
    String endedAt,
    Double progressPercent,
    boolean canStop,
    PlaybackLifecycleEndReason endReason
) {
}
