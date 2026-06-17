package com.nyx.playback.contracts;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AudioClientIdentity(
    String clientId,
    String clientName,
    String deviceId,
    String deviceName,
    String userAgent
) {
    public AudioClientIdentity() {
        this(null, null, null, null, null);
    }
}
