package com.nyx.transcode;

import com.nyx.common.ManagedService;
import com.nyx.transcode.contracts.SegmentCacheService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SegmentCache implements ManagedService, SegmentCacheService {
    public static final long CLEANUP_INTERVAL_MS = 60_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(SegmentCache.class);

    private final int gracePeriodMinutes;
    private final int maxEntries;
    private final Runnable onEviction;
    private final ConcurrentHashMap<Path, SegmentEntry> entries = new ConcurrentHashMap<>();
    private final Set<String> activeJobs = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Set<Path>> jobSegments = new ConcurrentHashMap<>();
    private final PriorityQueue<Map.Entry<Instant, Path>> evictionQueue =
        new PriorityQueue<>(Comparator.comparing(Map.Entry::getKey));
    private final ReentrantLock evictionLock = new ReentrantLock();
    private final boolean ownsCleanupScheduler;
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> cleanupTask;

    public SegmentCache() {
        this(10, 10_000, null, null);
    }

    public SegmentCache(int gracePeriodMinutes) {
        this(gracePeriodMinutes, 10_000, null, null);
    }

    public SegmentCache(int gracePeriodMinutes, int maxEntries) {
        this(gracePeriodMinutes, maxEntries, null, null);
    }

    public SegmentCache(int gracePeriodMinutes, int maxEntries, Runnable onEviction) {
        this(gracePeriodMinutes, maxEntries, null, onEviction);
    }

    public SegmentCache(int gracePeriodMinutes, int maxEntries, ScheduledExecutorService cleanupScheduler) {
        this(gracePeriodMinutes, maxEntries, cleanupScheduler, null);
    }

    public SegmentCache(
        int gracePeriodMinutes,
        int maxEntries,
        ScheduledExecutorService cleanupScheduler,
        Runnable onEviction
    ) {
        this.gracePeriodMinutes = gracePeriodMinutes;
        this.maxEntries = maxEntries;
        this.onEviction = onEviction;
        this.ownsCleanupScheduler = cleanupScheduler == null;
        this.scheduler = cleanupScheduler != null
            ? cleanupScheduler
            : Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nyx-segment-cache-cleanup");
                thread.setDaemon(true);
                return thread;
            });
        this.cleanupTask = scheduler.scheduleWithFixedDelay(
            this::cleanup,
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void register(Path segmentPath, String jobId) {
        entries.put(segmentPath, new SegmentEntry(segmentPath, jobId));
        jobSegments.computeIfAbsent(jobId, ignored -> ConcurrentHashMap.newKeySet()).add(segmentPath);
        activeJobs.add(jobId);
        evictIfOverLimit();
    }

    @Override
    public Path acquire(Path segmentPath) {
        SegmentEntry entry = entries.get(segmentPath);
        if (entry == null) {
            return null;
        }
        entry.getRefCount().incrementAndGet();
        return entry.getPath();
    }

    @Override
    public void release(Path segmentPath) {
        SegmentEntry entry = entries.get(segmentPath);
        if (entry == null) {
            return;
        }
        int newCount = entry.getRefCount().decrementAndGet();
        if (newCount == 0 && entry.getGracePeriodStart() != null) {
            enqueueEvictionCandidate(entry.getGracePeriodStart(), segmentPath);
        }
    }

    @Override
    public void startGracePeriod(String jobId) {
        activeJobs.remove(jobId);
        Instant now = Instant.now();
        Set<Path> paths = jobSegments.get(jobId);
        if (paths == null) {
            return;
        }
        for (Path path : paths) {
            SegmentEntry entry = entries.get(path);
            if (entry == null) {
                continue;
            }
            entry.setGracePeriodStart(now);
            if (entry.getRefCount().get() == 0) {
                enqueueEvictionCandidate(now, entry.getPath());
            }
        }
    }

    public boolean isJobActive(String jobId) {
        return activeJobs.contains(jobId);
    }

    public boolean hasSegment(Path segmentPath) {
        return entries.containsKey(segmentPath);
    }

    public int segmentCount() {
        return entries.size();
    }

    public void cleanup() {
        Instant now = Instant.now();
        for (Map.Entry<Path, SegmentEntry> mapEntry : entries.entrySet()) {
            SegmentEntry entry = mapEntry.getValue();
            if (activeJobs.contains(entry.getJobId())) {
                continue;
            }
            Instant graceStart = entry.getGracePeriodStart();
            if (graceStart == null) {
                continue;
            }
            if (entry.getRefCount().get() == 0 && graceStart.plusSeconds(gracePeriodMinutes * 60L).isBefore(now)) {
                entries.remove(mapEntry.getKey());
                Set<Path> paths = jobSegments.get(entry.getJobId());
                if (paths != null) {
                    paths.remove(mapEntry.getKey());
                }
                tryDelete(mapEntry.getKey(), "cleanup");
            }
        }
    }

    @Override
    public void shutdown() {
        cleanupTask.cancel(true);
        if (ownsCleanupScheduler) {
            scheduler.shutdownNow();
        }
        purgeAll();
    }

    @Override
    public void purgeAll() {
        for (Path path : entries.keySet()) {
            tryDelete(path, "purge");
        }
        entries.clear();
        activeJobs.clear();
        jobSegments.clear();
        evictionLock.lock();
        try {
            evictionQueue.clear();
        } finally {
            evictionLock.unlock();
        }
    }

    @Override
    public int entryCount() {
        return entries.size();
    }

    private void enqueueEvictionCandidate(Instant gracePeriodStart, Path path) {
        evictionLock.lock();
        try {
            evictionQueue.offer(Map.entry(gracePeriodStart, path));
        } finally {
            evictionLock.unlock();
        }
    }

    private void evictIfOverLimit() {
        if (entries.size() <= maxEntries) {
            return;
        }

        int removed = 0;
        evictionLock.lock();
        try {
            while (entries.size() - removed > maxEntries && !evictionQueue.isEmpty()) {
                Map.Entry<Instant, Path> candidate = evictionQueue.poll();
                if (candidate == null) {
                    break;
                }
                Path path = candidate.getValue();
                SegmentEntry entry = entries.get(path);
                if (entry == null) {
                    continue;
                }
                if (activeJobs.contains(entry.getJobId()) || entry.getRefCount().get() > 0) {
                    continue;
                }
                SegmentEntry evicted = entries.remove(path);
                if (evicted == null) {
                    continue;
                }
                removed += 1;
                Set<Path> paths = jobSegments.get(evicted.getJobId());
                if (paths != null) {
                    paths.remove(path);
                }
                tryDelete(path, "eviction");
            }
        } finally {
            evictionLock.unlock();
        }

        if (removed > 0) {
            LOGGER.info("Evicted {} segment cache entries (limit: {})", removed, maxEntries);
            if (onEviction != null) {
                for (int index = 0; index < removed; index += 1) {
                    onEviction.run();
                }
            }
        }
    }

    private void tryDelete(Path path, String phase) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception exception) {
            LOGGER.debug("Failed to delete segment file during {} {}: {}", phase, path, exception.getMessage());
        }
    }
}
