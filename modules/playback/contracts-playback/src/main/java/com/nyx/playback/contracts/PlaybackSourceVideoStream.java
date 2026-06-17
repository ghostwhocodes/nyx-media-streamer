package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlaybackSourceVideoStream(
    int index,
    String codec,
    int width,
    int height,
    double fps,
    Integer bitrateKbps
) {
    public PlaybackSourceVideoStream(int index, String codec, int width, int height, double fps) {
        this(index, codec, width, height, fps, null);
    }
}
