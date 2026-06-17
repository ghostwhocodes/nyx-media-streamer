package com.nyx.transcode;

import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public interface TranscodeRuntimeContext {
    TranscodeEngineConfig getConfig();

    SegmentCacheService getSegmentCache();

    TranscodeJobOrchestrator getJobOrchestrator();

    ConcurrentHashMap<String, TranscodeRequest> getJobRequests();

    ConcurrentHashMap<String, ProbeResult> getJobProbeResults();

    ConcurrentHashMap<String, Path> getSegmentOutputDirs();

    ConcurrentHashMap<String, AtomicLong> getJobOutputSizes();

    Set<String> getQuotaCancelledJobs();

    AtomicInteger getConsecutiveFailures();

    void invalidateManifest(String jobId);

    void scheduleDeferredCleanup(String jobId);

    void emitRuntimeEvent(String jobId, JobEvent event);
}
