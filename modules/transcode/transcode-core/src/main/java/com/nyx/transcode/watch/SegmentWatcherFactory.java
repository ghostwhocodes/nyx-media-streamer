package com.nyx.transcode.watch;

public final class SegmentWatcherFactory {
    private SegmentWatcherFactory() {
    }

    public static SegmentWatcher createWatcher(WatchStrategy strategy) {
        return createWatcher(strategy, 500L);
    }

    public static SegmentWatcher createWatcher(WatchStrategy strategy, long pollIntervalMs) {
        return switch (strategy) {
            case INOTIFY -> new InotifySegmentWatcher();
            case POLLING -> new PollingSegmentWatcher(pollIntervalMs);
            case HYBRID -> new HybridSegmentWatcher(pollIntervalMs);
        };
    }
}
