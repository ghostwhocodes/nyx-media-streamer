package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackSourceAudioStream(
    int index,
    String codec,
    int channels,
    Integer bitrateKbps,
    Integer sampleRateHz,
    String language,
    String title
) {
    public PlaybackSourceAudioStream(int index, String codec, int channels) {
        this(index, codec, channels, null, null, null, null);
    }
}
