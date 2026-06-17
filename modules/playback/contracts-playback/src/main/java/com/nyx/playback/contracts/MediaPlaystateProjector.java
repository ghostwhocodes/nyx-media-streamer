package com.nyx.playback.contracts;

import com.nyx.media.contracts.UserMediaState;

public interface MediaPlaystateProjector {
    UserMediaState projectPlaybackState(
        String userId,
        String objectId,
        MediaSessionPlaybackReport report
    );
}
