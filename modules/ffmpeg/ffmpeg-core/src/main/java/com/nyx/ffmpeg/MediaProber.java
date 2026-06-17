package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.ProbeResult;
import java.nio.file.Path;

public interface MediaProber {
    ProbeResult probe(Path path);

    ProbeResult probeCached(Path path);

    void clearCache();
}
