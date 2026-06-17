package com.nyx.playback.contracts;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AudioFormatDescriptor(
    String container,
    String codec,
    String mimeType,
    Integer bitrateKbps,
    Integer channels,
    Integer sampleRateHz
) {
    public AudioFormatDescriptor() {
        this(null, null, null, null, null, null);
    }
}
