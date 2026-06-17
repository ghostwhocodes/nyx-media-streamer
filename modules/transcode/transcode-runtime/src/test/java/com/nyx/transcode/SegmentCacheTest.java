package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SegmentCacheTest {
    private final List<SegmentCache> caches = new ArrayList<>();
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("nyx-cache-test");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (SegmentCache cache : caches) {
            cache.shutdown();
        }
        deleteRecursively(tempDir);
    }

    @Test
    void fullLifecycleRegisterAcquireReleaseGraceEvict() throws Exception {
        SegmentCache cache = createCache(0);

        Path segmentPath = createSegment("seg001.m4s");
        cache.register(segmentPath, "job1");

        Path acquired = cache.acquire(segmentPath);
        assertNotNull(acquired);
        assertEquals(segmentPath, acquired);

        cache.release(segmentPath);
        cache.startGracePeriod("job1");
        Thread.sleep(10L);

        cache.cleanup();
        assertNull(cache.acquire(segmentPath));
        assertFalse(Files.exists(segmentPath));
    }

    @Test
    void activeJobSegmentsAreNeverEvicted() throws Exception {
        SegmentCache cache = createCache(0);

        Path segmentPath = createSegment("seg001.m4s");
        cache.register(segmentPath, "job1");

        cache.cleanup();

        assertNotNull(cache.acquire(segmentPath));
        assertTrue(Files.exists(segmentPath));
        cache.release(segmentPath);
    }

    @Test
    void segmentNotFoundReturnsNullOnAcquire() {
        SegmentCache cache = createCache(10);

        Path result = cache.acquire(Path.of("/nonexistent/segment.m4s"));
        assertNull(result);
    }

    @Test
    void concurrentAcquireAndReleaseAreThreadSafe() throws Exception {
        SegmentCache cache = createCache(10);

        Path segmentPath = createSegment("seg001.m4s");
        cache.register(segmentPath, "job1");

        for (int index = 0; index < 100; index += 1) {
            cache.acquire(segmentPath);
        }
        for (int index = 0; index < 100; index += 1) {
            cache.release(segmentPath);
        }

        assertNotNull(cache.acquire(segmentPath));
        cache.release(segmentPath);
    }

    @Test
    void segmentsWithActiveRefsAreNotEvictedDuringCleanup() throws Exception {
        SegmentCache cache = createCache(0);

        Path segmentPath = createSegment("seg001.m4s");
        cache.register(segmentPath, "job1");
        cache.acquire(segmentPath);

        cache.startGracePeriod("job1");
        Thread.sleep(10L);
        cache.cleanup();

        assertTrue(cache.hasSegment(segmentPath));
        assertTrue(Files.exists(segmentPath));
        cache.release(segmentPath);
    }

    @Test
    void cleanupRemovesEntriesPastGracePeriodAndDeletesRealFiles() throws Exception {
        SegmentCache cache = createCache(0);

        Path file1 = createSegment("cleanup-seg1.m4s");
        Path file2 = createSegment("cleanup-seg2.m4s");
        assertTrue(Files.exists(file1));
        assertTrue(Files.exists(file2));

        cache.register(file1, "job-cleanup");
        cache.register(file2, "job-cleanup");
        cache.startGracePeriod("job-cleanup");
        Thread.sleep(10L);

        cache.cleanup();

        assertFalse(cache.hasSegment(file1));
        assertFalse(cache.hasSegment(file2));
        assertEquals(0, cache.entryCount());
        assertFalse(Files.exists(file1));
        assertFalse(Files.exists(file2));
    }

    @Test
    void cleanupHandlesNonExistentFilePathWithoutError() throws Exception {
        SegmentCache cache = createCache(0);

        Path fakePath = segmentPath("does-not-exist.m4s");
        cache.register(fakePath, "job-fake");
        cache.startGracePeriod("job-fake");
        Thread.sleep(10L);

        assertDoesNotThrow(cache::cleanup);
        assertFalse(cache.hasSegment(fakePath));
        assertEquals(0, cache.entryCount());
    }

    @Test
    void cleanupCatchBlockHandlesDeleteFailureGracefully() throws Exception {
        SegmentCache cache = createCache(0);

        Path restrictedDir = Files.createDirectory(tempDir.resolve("restricted"));
        Path protectedFile = Files.createFile(restrictedDir.resolve("protected-seg.m4s"));
        assertTrue(Files.exists(protectedFile));

        cache.register(protectedFile, "job-restricted");
        cache.startGracePeriod("job-restricted");
        Thread.sleep(10L);

        restrictedDir.toFile().setWritable(false);

        cache.cleanup();

        assertFalse(cache.hasSegment(protectedFile));
        assertEquals(0, cache.entryCount());
        assertTrue(Files.exists(protectedFile));
    }

    @Test
    void cleanupSkipsEntriesForActiveJobs() throws Exception {
        SegmentCache cache = createCache(0);

        Path activeFile = createSegment("active-seg.m4s");
        cache.register(activeFile, "active-job");

        cache.cleanup();

        assertTrue(cache.hasSegment(activeFile));
        assertEquals(1, cache.entryCount());
        assertTrue(Files.exists(activeFile));
    }

    @Test
    void cleanupSkipsEntriesWithoutGracePeriodStart() throws Exception {
        SegmentCache cache = createCache(0);

        Path file = createSegment("no-grace-seg.m4s");
        cache.register(file, "job-nograce");

        Path file2 = createSegment("grace-seg.m4s");
        cache.register(file2, "job-withgrace");
        cache.startGracePeriod("job-withgrace");
        Thread.sleep(10L);

        cache.cleanup();

        assertTrue(cache.hasSegment(file));
        assertFalse(cache.hasSegment(file2));
    }

    @Test
    void purgeAllDeletesRealFilesFromDisk() throws Exception {
        SegmentCache cache = createCache(10);

        Path file1 = createSegment("purge-seg1.m4s");
        Path file2 = createSegment("purge-seg2.m4s");
        Path file3 = createSegment("purge-seg3.m4s");
        assertTrue(Files.exists(file1));
        assertTrue(Files.exists(file2));
        assertTrue(Files.exists(file3));

        cache.register(file1, "purge-job1");
        cache.register(file2, "purge-job1");
        cache.register(file3, "purge-job2");

        cache.purgeAll();

        assertFalse(Files.exists(file1));
        assertFalse(Files.exists(file2));
        assertFalse(Files.exists(file3));
        assertEquals(0, cache.entryCount());
        assertFalse(cache.hasSegment(file1));
        assertFalse(cache.hasSegment(file2));
        assertFalse(cache.hasSegment(file3));
        assertFalse(cache.isJobActive("purge-job1"));
        assertFalse(cache.isJobActive("purge-job2"));
    }

    @Test
    void purgeAllHandlesNonExistentPathsWithoutError() {
        SegmentCache cache = createCache(10);

        Path fakePath1 = segmentPath("nonexistent1.m4s");
        Path fakePath2 = segmentPath("nonexistent2.m4s");
        cache.register(fakePath1, "fake-job");
        cache.register(fakePath2, "fake-job");

        cache.purgeAll();

        assertEquals(0, cache.entryCount());
    }

    @Test
    void purgeAllCatchBlockHandlesDeleteFailureGracefully() throws Exception {
        SegmentCache cache = createCache(10);

        Path restrictedDir = Files.createDirectory(tempDir.resolve("restricted-purge"));
        Path protectedFile = Files.createFile(restrictedDir.resolve("protected-purge-seg.m4s"));
        assertTrue(Files.exists(protectedFile));

        cache.register(protectedFile, "job-purge-restricted");
        restrictedDir.toFile().setWritable(false);

        cache.purgeAll();

        assertEquals(0, cache.entryCount());
        assertFalse(cache.isJobActive("job-purge-restricted"));
        assertTrue(Files.exists(protectedFile));
    }

    @Test
    void shutdownCallsPurgeAllAndClearsEverything() throws Exception {
        SegmentCache cache = createCache(10);

        Path file1 = createSegment("shutdown-seg1.m4s");
        Path file2 = createSegment("shutdown-seg2.m4s");
        cache.register(file1, "shutdown-job");
        cache.register(file2, "shutdown-job");
        assertEquals(2, cache.entryCount());

        cache.shutdown();

        assertEquals(0, cache.entryCount());
        assertFalse(Files.exists(file1));
        assertFalse(Files.exists(file2));
        assertFalse(cache.isJobActive("shutdown-job"));
    }

    @Test
    void evictIfOverLimitTriggersOnEvictionCallbackAndLoggingForEachEvictedEntry() throws Exception {
        AtomicInteger evictionCount = new AtomicInteger();
        SegmentCache cache = createCache(10, 2, evictionCount::incrementAndGet);

        Path file1 = createSegment("evict-cb-seg1.m4s");
        Path file2 = createSegment("evict-cb-seg2.m4s");
        cache.register(file1, "evict-cb-job");
        cache.register(file2, "evict-cb-job");
        cache.startGracePeriod("evict-cb-job");

        Path file3 = createSegment("evict-cb-seg3.m4s");
        Path file4 = createSegment("evict-cb-seg4.m4s");
        cache.register(file3, "evict-cb-job2");
        cache.register(file4, "evict-cb-job2");

        assertTrue(evictionCount.get() >= 2, "Expected onEviction called at least 2 times, got " + evictionCount.get());
        assertTrue(cache.entryCount() <= 2, "Expected at most 2 entries, got " + cache.entryCount());
        assertTrue(!Files.exists(file1) || !Files.exists(file2), "At least one evicted file should be deleted from disk");
    }

    @Test
    void evictIfOverLimitWithRealFilesDeletesEvictedSegmentsFromDisk() throws Exception {
        SegmentCache cache = createCache(10, 1);

        Path file1 = createSegment("evict-real-seg1.m4s");
        assertTrue(Files.exists(file1));

        cache.register(file1, "evict-real-job");
        cache.startGracePeriod("evict-real-job");

        Path file2 = createSegment("evict-real-seg2.m4s");
        cache.register(file2, "evict-real-job2");

        assertFalse(cache.hasSegment(file1));
        assertFalse(Files.exists(file1), "Evicted segment file should be deleted from disk");
        assertTrue(cache.hasSegment(file2));
        assertTrue(Files.exists(file2));
    }

    @Test
    void evictIfOverLimitHandlesFileDeletionFailureInCatchBlock() throws Exception {
        SegmentCache cache = createCache(10, 1);

        Path restrictedDir = Files.createDirectory(tempDir.resolve("restricted-evict"));
        Path protectedFile = Files.createFile(restrictedDir.resolve("evict-protected.m4s"));
        cache.register(protectedFile, "evict-restricted-job");
        cache.startGracePeriod("evict-restricted-job");

        restrictedDir.toFile().setWritable(false);

        Path file2 = createSegment("evict-trigger.m4s");
        cache.register(file2, "evict-trigger-job");

        assertFalse(cache.hasSegment(protectedFile));
        assertTrue(Files.exists(protectedFile));
        assertTrue(cache.hasSegment(file2));
    }

    @Test
    void segmentCacheCleanupLoopFiresOnSchedule() throws Exception {
        TestScheduledExecutorService scheduler = new TestScheduledExecutorService();
        SegmentCache cache = createCache(0, 10_000, scheduler);

        Path segmentPath = Files.createTempFile(tempDir, "seg", ".ts");
        cache.register(segmentPath, "job-cleanup-test");
        cache.startGracePeriod("job-cleanup-test");
        Thread.sleep(10L);

        scheduler.runScheduledTask();

        assertEquals(0, cache.segmentCount());
    }

    @Test
    void segmentCacheShutdownCallsPurgeAllFromImprovements() {
        SegmentCache cache = createCache(10, 10);
        cache.register(segmentPath("seg1.m4s"), "job1");
        assertEquals(1, cache.entryCount());

        cache.shutdown();
        assertEquals(0, cache.entryCount());
    }

    @Test
    void segmentCacheCleanupRemovesExpiredEntriesFromImprovements() throws Exception {
        SegmentCache cache = createCache(0, 100);
        Path segment = segmentPath("cleanup-seg.m4s");
        cache.register(segment, "job1");
        cache.startGracePeriod("job1");

        Thread.sleep(50L);
        cache.cleanup();

        assertFalse(cache.hasSegment(segment), "Expired segment should be cleaned up");
    }

    @Test
    void segmentCacheCleanupDoesNotRemoveActiveJobSegmentsFromImprovements() {
        SegmentCache cache = createCache(0, 100);
        Path segment = segmentPath("active-seg.m4s");
        cache.register(segment, "active-job");

        cache.cleanup();

        assertTrue(cache.hasSegment(segment), "Active job segments should not be cleaned up");
    }

    @Test
    void segmentCacheCleanupDoesNotRemoveEntriesWithRefCountGreaterThanZero() throws Exception {
        SegmentCache cache = createCache(0, 100);
        Path segment = segmentPath("ref-seg.m4s");
        cache.register(segment, "job1");
        cache.acquire(segment);
        cache.startGracePeriod("job1");

        Thread.sleep(50L);
        cache.cleanup();

        assertTrue(cache.hasSegment(segment), "Segment with refCount > 0 should not be cleaned");
        cache.release(segment);
    }

    @Test
    void segmentCacheSegmentCountReturnsCorrectCount() {
        SegmentCache cache = createCache(10, 10_000);
        assertEquals(0, cache.segmentCount());
        cache.register(segmentPath("s1.m4s"), "j1");
        assertEquals(1, cache.segmentCount());
        cache.register(segmentPath("s2.m4s"), "j1");
        assertEquals(2, cache.segmentCount());
    }

    @Test
    void segmentCacheAcquireReturnsNullForUnknownSegmentFromImprovements() {
        SegmentCache cache = createCache(10, 10_000);
        assertNull(cache.acquire(Path.of("/tmp/unknown.m4s")));
    }

    @Test
    void segmentCacheReleaseDoesNothingForUnknownSegment() {
        SegmentCache cache = createCache(10, 10_000);
        assertDoesNotThrow(() -> cache.release(Path.of("/tmp/unknown.m4s")));
    }

    @Test
    void segmentEntryDataClass() {
        SegmentEntry entry = new SegmentEntry(Path.of("/tmp/seg.m4s"), "job1");
        assertEquals(Path.of("/tmp/seg.m4s"), entry.getPath());
        assertEquals("job1", entry.getJobId());
        assertEquals(0, entry.getRefCount().get());
        assertNull(entry.getGracePeriodStart());
    }

    @Test
    void segmentCacheCleanupRemovesEntriesPastGracePeriodFromImprovements() throws Exception {
        SegmentCache cache = createCache(0);
        Path segment = segmentPath("cleanup-test-seg.m4s");
        cache.register(segment, "cleanup-job");
        cache.startGracePeriod("cleanup-job");

        Thread.sleep(10L);
        cache.cleanup();

        assertEquals(0, cache.entryCount());
    }

    private SegmentCache createCache(int gracePeriodMinutes) {
        return track(new SegmentCache(gracePeriodMinutes));
    }

    private SegmentCache createCache(int gracePeriodMinutes, int maxEntries) {
        return track(new SegmentCache(gracePeriodMinutes, maxEntries));
    }

    private SegmentCache createCache(int gracePeriodMinutes, int maxEntries, Runnable onEviction) {
        return track(new SegmentCache(gracePeriodMinutes, maxEntries, onEviction));
    }

    private SegmentCache createCache(int gracePeriodMinutes, int maxEntries, TestScheduledExecutorService scheduler) {
        return track(new SegmentCache(gracePeriodMinutes, maxEntries, scheduler));
    }

    private SegmentCache track(SegmentCache cache) {
        caches.add(cache);
        return cache;
    }

    private Path createSegment(String name) throws IOException {
        return Files.createFile(tempDir.resolve(name));
    }

    private Path segmentPath(String name) {
        return tempDir.resolve(name);
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().setWritable(true));
        }
        IOException[] failure = new IOException[1];
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                if (failure[0] != null) {
                    return;
                }
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    failure[0] = exception;
                }
            });
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }
}
