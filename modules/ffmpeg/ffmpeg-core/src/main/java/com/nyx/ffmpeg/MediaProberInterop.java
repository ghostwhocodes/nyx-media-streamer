package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.ProbeResult;
import java.nio.file.Path;

public final class MediaProberInterop {
    private MediaProberInterop() {
    }

    public static ProbeResult probeOrThrow(MediaProber mediaProber, Path path) {
        return mediaProber.probe(path);
    }

    public static ProbeResult probeCachedOrThrow(MediaProber mediaProber, Path path) {
        return mediaProber.probeCached(path);
    }
}
