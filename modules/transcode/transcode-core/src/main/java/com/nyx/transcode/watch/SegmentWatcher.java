package com.nyx.transcode.watch;

import com.nyx.concurrent.BlockingStream;
import java.nio.file.Path;

public interface SegmentWatcher {
    BlockingStream<Path> watch(Path directory);

    void close();
}
