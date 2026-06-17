package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.ManagedService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ManagedServiceTest {
    @Test
    void segmentCacheImplementsManagedService() {
        SegmentCache cache = new SegmentCache(10, 100);
        assertTrue(cache instanceof ManagedService, "SegmentCache should implement ManagedService");
    }

    @Test
    void segmentCacheShutdownCallsPurgeAll() {
        SegmentCache cache = new SegmentCache(10, 100);

        Path first = Path.of("/tmp/managed-seg1.m4s");
        Path second = Path.of("/tmp/managed-seg2.m4s");
        cache.register(first, "job1");
        cache.register(second, "job2");
        assertEquals(2, cache.entryCount());

        cache.shutdown();

        assertEquals(0, cache.entryCount(), "shutdown() should purge all entries");
        assertFalse(cache.hasSegment(first));
        assertFalse(cache.hasSegment(second));
    }

    @Test
    void segmentCacheShutdownIsIdempotent() {
        SegmentCache cache = new SegmentCache(10, 100);
        Path path = Path.of("/tmp/managed-seg.m4s");
        cache.register(path, "job1");

        cache.shutdown();
        assertEquals(0, cache.entryCount());

        cache.shutdown();
        assertEquals(0, cache.entryCount());
    }

    @Test
    void transcodeServiceImplementsManagedService() {
        assertTrue(
            ManagedService.class.isAssignableFrom(TranscodeService.class),
            "TranscodeService should implement ManagedService"
        );
    }

    @Test
    void managedServiceStartHasDefaultEmptyImplementation() {
        SegmentCache cache = new SegmentCache(10, 100);
        cache.start();
    }

    @Test
    void segmentCacheShutdownClearsActiveJobsTracking() {
        SegmentCache cache = new SegmentCache(10, 100);
        Path path = Path.of("/tmp/managed-seg.m4s");
        cache.register(path, "job1");
        assertTrue(cache.isJobActive("job1"), "Job should be active after register");

        cache.shutdown();

        assertFalse(cache.isJobActive("job1"), "Job should no longer be active after shutdown");
    }

    @Test
    void managedServiceDefaultStartIsNoOpFromImprovements() {
        ManagedService service = new ManagedService() {
            @Override
            public void shutdown() {
            }
        };
        service.start();
    }

    @Test
    void managedServiceDefaultStartRunsWithoutErrorViaSegmentCacheFromImprovements() {
        SegmentCache cache = new SegmentCache();
        cache.start();
    }
}
