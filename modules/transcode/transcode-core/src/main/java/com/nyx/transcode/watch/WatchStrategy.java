package com.nyx.transcode.watch;

import java.util.List;

public enum WatchStrategy {
    INOTIFY,
    POLLING,
    HYBRID;

    public static final List<WatchStrategy> entries = List.of(values());
}
