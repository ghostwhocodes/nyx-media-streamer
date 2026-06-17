package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;

public enum PlaybackSessionState {
    PENDING,
    READY,
    FAILED,
    CLOSED,
}
