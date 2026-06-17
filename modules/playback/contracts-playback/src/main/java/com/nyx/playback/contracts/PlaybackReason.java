package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public enum PlaybackReason {
    EXPLICIT_TRANSCODE_REQUEST,
    ADAPTIVE_STREAMING_REQUESTED,
    CLIENT_CAPABILITY_LIMIT,
    VIDEO_CODEC_UNSUPPORTED,
    AUDIO_CODEC_UNSUPPORTED,
    CONTAINER_UNSUPPORTED,
    VIDEO_BITRATE_TOO_HIGH,
    VIDEO_RESOLUTION_TOO_HIGH,
    AUDIO_BITRATE_TOO_HIGH,
    AUDIO_CHANNELS_TOO_HIGH,
    AUDIO_SAMPLE_RATE_TOO_HIGH,
    SUBTITLE_BURN_IN_REQUIRED,
}
