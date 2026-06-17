package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SegmentCacheEvictionTest {
    private final List<SegmentCache> caches = new ArrayList<>();
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("nyx-segment-cache-eviction");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (SegmentCache cache : caches) {
            cache.shutdown();
        }
        deleteRecursively(tempDir);
    }

    @Test
    void evictionTriggersWhenEntriesExceedMaxEntries() {
        SegmentCache cache = createCache(10, 3);

        Path segment1 = segmentPath("seg001.m4s");
        Path segment2 = segmentPath("seg002.m4s");
        Path segment3 = segmentPath("seg003.m4s");
        cache.register(segment1, "job1");
        cache.register(segment2, "job1");
        cache.register(segment3, "job1");

        assertEquals(3, cache.entryCount());

        cache.startGracePeriod("job1");

        Path segment4 = segmentPath("seg004.m4s");
        cache.register(segment4, "job2");

        assertTrue(cache.entryCount() <= 3, "Expected at most 3 entries after eviction, got " + cache.entryCount());
        assertTrue(cache.hasSegment(segment4));
    }

    @Test
    void activeJobSegmentsAreProtectedFromEviction() {
        SegmentCache cache = createCache(10, 2);

        Path activeSegment1 = segmentPath("active-seg001.m4s");
        Path activeSegment2 = segmentPath("active-seg002.m4s");
        cache.register(activeSegment1, "active-job");
        cache.register(activeSegment2, "active-job");

        assertEquals(2, cache.entryCount());

        Path inactiveSegment = segmentPath("inactive-seg001.m4s");
        cache.register(inactiveSegment, "other-job");
        cache.startGracePeriod("other-job");

        assertTrue(cache.hasSegment(activeSegment1), "Active job segment 1 should be protected");
        assertTrue(cache.hasSegment(activeSegment2), "Active job segment 2 should be protected");
    }

    @Test
    void evictionPrefersEntriesWithEarliestGracePeriodStart() throws Exception {
        SegmentCache cache = createCache(10, 3);

        Path oldSegment = segmentPath("old-seg.m4s");
        Path newSegment = segmentPath("new-seg.m4s");
        cache.register(oldSegment, "old-job");
        cache.startGracePeriod("old-job");

        Thread.sleep(10L);

        cache.register(newSegment, "new-job");
        cache.startGracePeriod("new-job");

        Path segment3 = segmentPath("seg3.m4s");
        Path segment4 = segmentPath("seg4.m4s");
        cache.register(segment3, "keeper-job");
        cache.register(segment4, "keeper-job");

        assertTrue(cache.entryCount() <= 3, "Expected at most 3 entries after eviction");
        assertFalse(cache.hasSegment(oldSegment), "Oldest grace period entry should be evicted first");
    }

    @Test
    void entriesWithRefCountGreaterThanZeroAreNotEvicted() {
        SegmentCache cache = createCache(10, 2);

        Path acquiredSegment = segmentPath("acquired-seg.m4s");
        cache.register(acquiredSegment, "job1");
        cache.acquire(acquiredSegment);
        cache.startGracePeriod("job1");

        Path freeSegment = segmentPath("free-seg.m4s");
        cache.register(freeSegment, "job2");
        cache.startGracePeriod("job2");

        Path triggerSegment = segmentPath("trigger-seg.m4s");
        cache.register(triggerSegment, "job3");

        assertTrue(cache.hasSegment(acquiredSegment), "Segment with refCount > 0 should not be evicted");

        cache.release(acquiredSegment);
    }

    @Test
    void noEvictionNeededWhenAtOrBelowMaxEntries() {
        SegmentCache cache = createCache(10, 5);

        Path segment1 = segmentPath("seg1.m4s");
        Path segment2 = segmentPath("seg2.m4s");
        cache.register(segment1, "job1");
        cache.register(segment2, "job2");

        assertEquals(2, cache.entryCount());
        assertTrue(cache.hasSegment(segment1));
        assertTrue(cache.hasSegment(segment2));
    }

    @Test
    void maxEntriesOfOneEvictsImmediatelyOnSecondRegistration() {
        SegmentCache cache = createCache(10, 1);

        Path segment1 = segmentPath("seg1.m4s");
        cache.register(segment1, "job1");
        cache.startGracePeriod("job1");

        Path segment2 = segmentPath("seg2.m4s");
        cache.register(segment2, "job2");

        assertTrue(cache.entryCount() <= 1, "Expected at most 1 entry, got " + cache.entryCount());
        assertTrue(cache.hasSegment(segment2), "Newly registered segment should be present");
        assertFalse(cache.hasSegment(segment1), "Old segment should have been evicted");
    }

    @Test
    void evictionDoesNotEvictMoreThanNecessary() {
        SegmentCache cache = createCache(10, 3);

        Path segment1 = segmentPath("seg1.m4s");
        Path segment2 = segmentPath("seg2.m4s");
        Path segment3 = segmentPath("seg3.m4s");
        cache.register(segment1, "job1");
        cache.register(segment2, "job1");
        cache.register(segment3, "job1");
        cache.startGracePeriod("job1");

        Path segment4 = segmentPath("seg4.m4s");
        cache.register(segment4, "job2");

        assertEquals(3, cache.entryCount(), "Should evict exactly enough to reach maxEntries");
        assertTrue(cache.hasSegment(segment4), "Newly registered segment should be present");
    }

    @Test
    void purgeAllClearsEverythingRegardlessOfMaxEntries() {
        SegmentCache cache = createCache(10, 10);

        Path segment1 = segmentPath("seg1.m4s");
        Path segment2 = segmentPath("seg2.m4s");
        cache.register(segment1, "job1");
        cache.register(segment2, "job2");

        cache.purgeAll();

        assertEquals(0, cache.entryCount());
        assertFalse(cache.hasSegment(segment1));
        assertFalse(cache.hasSegment(segment2));
    }

    @Test
    void multipleJobsWithMixedActiveAndInactiveDuringEviction() {
        SegmentCache cache = createCache(10, 3);

        Path activeSegment = segmentPath("active.m4s");
        cache.register(activeSegment, "active-job");

        Path inactiveSegment1 = segmentPath("inactive1.m4s");
        cache.register(inactiveSegment1, "inactive-job-1");
        cache.startGracePeriod("inactive-job-1");

        Path inactiveSegment2 = segmentPath("inactive2.m4s");
        cache.register(inactiveSegment2, "inactive-job-2");
        cache.startGracePeriod("inactive-job-2");

        Path newSegment = segmentPath("new.m4s");
        cache.register(newSegment, "new-job");

        assertTrue(cache.hasSegment(activeSegment), "Active job segment must be protected from eviction");
        assertTrue(cache.hasSegment(newSegment), "Newly added segment must be present");
        assertTrue(cache.entryCount() <= 3);
    }

    @Test
    void evictionWithOnEvictionCallbackInvokesCallbackForEachEvictedEntry() {
        int[] evictionCount = new int[] { 0 };
        SegmentCache cache = createCache(10, 2, () -> evictionCount[0] += 1);

        Path segment1 = segmentPath("cb-seg001.m4s");
        Path segment2 = segmentPath("cb-seg002.m4s");
        cache.register(segment1, "cb-job");
        cache.register(segment2, "cb-job");
        cache.startGracePeriod("cb-job");

        Path segment3 = segmentPath("cb-seg003.m4s");
        cache.register(segment3, "cb-job2");

        assertTrue(evictionCount[0] >= 1, "Expected onEviction to be called at least once, was " + evictionCount[0]);
    }

    @Test
    void releaseWithRefCountToZeroAfterGracePeriodEnqueuesForEviction() {
        SegmentCache cache = createCache(10, 1);

        Path segment1 = segmentPath("rel-seg1.m4s");
        Path segment2 = segmentPath("rel-seg2.m4s");

        cache.register(segment1, "job1");
        cache.acquire(segment1);
        cache.startGracePeriod("job1");
        cache.release(segment1);

        cache.register(segment2, "job2");

        assertTrue(cache.entryCount() <= 1, "seg1 should have been evicted after release");
        assertTrue(cache.hasSegment(segment2), "seg2 should be present");
    }

    @Test
    void stressTestRegisters12000SegmentsWithLimit10000AndResultsInExactly10000Entries() {
        SegmentCache cache = createCache(10, 10_000);

        for (int index = 0; index < 8_000; index += 1) {
            cache.register(segmentPath("stress-old-" + index + ".m4s"), "old-job");
        }
        cache.startGracePeriod("old-job");

        for (int index = 0; index < 4_000; index += 1) {
            cache.register(segmentPath("stress-new-" + index + ".m4s"), "new-job");
        }

        assertTrue(
            cache.entryCount() <= 10_000,
            "Cache should not exceed maxEntries=10000, got " + cache.entryCount()
        );
    }

    @Test
    void purgeAllAlsoClearsEvictionQueue() {
        SegmentCache cache = createCache(10, 5);

        Path segment1 = segmentPath("pq-seg1.m4s");
        Path segment2 = segmentPath("pq-seg2.m4s");
        cache.register(segment1, "job1");
        cache.register(segment2, "job1");
        cache.startGracePeriod("job1");
        cache.purgeAll();

        assertEquals(0, cache.entryCount());

        Path segment3 = segmentPath("pq-seg3.m4s");
        cache.register(segment3, "job2");
        assertEquals(1, cache.entryCount());
        assertTrue(cache.hasSegment(segment3));
    }

    private SegmentCache createCache(int gracePeriodMinutes, int maxEntries) {
        return track(new SegmentCache(gracePeriodMinutes, maxEntries));
    }

    private SegmentCache createCache(int gracePeriodMinutes, int maxEntries, Runnable onEviction) {
        return track(new SegmentCache(gracePeriodMinutes, maxEntries, onEviction));
    }

    private SegmentCache track(SegmentCache cache) {
        caches.add(cache);
        return cache;
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
