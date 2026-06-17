package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;

public enum PlaybackLifecycleEndReason {
    CLIENT_REQUESTED,
    PLAYBACK_COMPLETED,
    BACKING_JOB_MISSING,
    BACKING_JOB_CANCELLED,
    BACKING_JOB_FAILED,
    SESSION_INVALID,
}
