package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.ffmpeg.model.TranscodeProfiles;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TranscodeServiceReadModelTest extends AbstractTranscodeServiceTestSupport {
    @Test
    void retryFlowUsesCorrectStateTransitions() {
        assertTrue(com.nyx.transcode.contracts.TranscodeContracts.canTransitionTo(JobStatus.QUEUED, JobStatus.PROBING));
        assertTrue(com.nyx.transcode.contracts.TranscodeContracts.canTransitionTo(JobStatus.PROBING, JobStatus.TRANSCODING));
        assertTrue(com.nyx.transcode.contracts.TranscodeContracts.canTransitionTo(JobStatus.TRANSCODING, JobStatus.RETRYING));
        assertTrue(com.nyx.transcode.contracts.TranscodeContracts.canTransitionTo(JobStatus.RETRYING, JobStatus.TRANSCODING));
        assertTrue(com.nyx.transcode.contracts.TranscodeContracts.canTransitionTo(JobStatus.TRANSCODING, JobStatus.FAILED));
    }

    @Test
    void getLogsReturnsEmptyStringForNonExistentJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("logs1.db");
        assertEquals("", resources.service().getLogs("nonexistent"));
    }

    @Test
    void getLogsReturnsInitialStderrWhenPresent() throws Exception {
        ServiceResources resources = createServiceWithRepo("logs2.db");
        resources.jobRepository().create(job("log-test1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
        resources.jobRepository().storeStderr("log-test1", "Initial error output", null);

        String logs = resources.service().getLogs("log-test1");
        assertTrue(logs.contains("Initial Attempt"));
        assertTrue(logs.contains("Initial error output"));
    }

    @Test
    void getLogsReturnsFallbackStderrWhenPresent() throws Exception {
        ServiceResources resources = createServiceWithRepo("logs3.db");
        resources.jobRepository().create(job("log-test2", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
        resources.jobRepository().storeStderr("log-test2", null, "Fallback error output");

        String logs = resources.service().getLogs("log-test2");
        assertTrue(logs.contains("Fallback Attempt"));
        assertTrue(logs.contains("Fallback error output"));
    }

    @Test
    void getLogsReturnsBothInitialAndFallbackStderr() throws Exception {
        ServiceResources resources = createServiceWithRepo("logs4.db");
        resources.jobRepository().create(job("log-test3", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
        resources.jobRepository().storeStderr("log-test3", "First attempt", "Second attempt");

        String logs = resources.service().getLogs("log-test3");
        assertTrue(logs.contains("Initial Attempt"));
        assertTrue(logs.contains("First attempt"));
        assertTrue(logs.contains("Fallback Attempt"));
        assertTrue(logs.contains("Second attempt"));
    }

    @Test
    void getLogsReturnsEmptyWhenJobExistsButNoStderrStored() throws Exception {
        ServiceResources resources = createServiceWithRepo("logs5.db");
        resources.jobRepository().create(job("log-test4", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

        assertTrue(resources.service().getLogs("log-test4").isEmpty());
    }

    @Test
    void getManifestMpdReturnsNullForNonExistentJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("mpd1.db");
        assertNull(resources.service().getManifestMpd("nonexistent"));
    }

    @Test
    void getManifestMpdReturnsMpdForExistingJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("mpd2.db");
        resources.jobRepository().create(job("mpd-test1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

        String mpd = resources.service().getManifestMpd("mpd-test1");
        assertNotNull(mpd);
        assertTrue(mpd.contains("<?xml"));
        assertTrue(mpd.contains("MPD"));
        assertTrue(mpd.contains("static"));
    }

    @Test
    void getManifestMpdReturnsDynamicMpdForInProgressJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("mpd3.db");
        resources.jobRepository().create(job("mpd-test2", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

        String mpd = resources.service().getManifestMpd("mpd-test2");
        assertNotNull(mpd);
        assertTrue(mpd.contains("dynamic"));
    }

    @Test
    void getManifestM3u8ReturnsNullForNonExistentJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("m3u81.db");
        assertNull(resources.service().getManifestM3u8("nonexistent"));
    }

    @Test
    void getManifestM3u8ReturnsHlsForExistingJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("m3u82.db");
        resources.jobRepository().create(job("hls-test1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "hls"));

        String m3u8 = resources.service().getManifestM3u8("hls-test1");
        assertNotNull(m3u8);
        assertTrue(m3u8.contains("#EXTM3U"));
    }

    @Test
    void listJobsReturnsAllRecentJobs() throws Exception {
        ServiceResources resources = createServiceWithRepo("list1.db");
        resources.jobRepository().create(job("list1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));
        resources.jobRepository().create(job("list2", JobStatus.COMPLETED, "/b.mkv", "h264_fast", "hls"));

        TranscodeJobListing listing = resources.service().listJobs(1, 50, null);
        assertTrue(listing.getJobs().size() >= 2);
    }

    @Test
    void getJobReturnsNullForNonExistentJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("get1.db");
        assertNull(resources.service().getJob("nonexistent"));
    }

    @Test
    void getJobReturnsJobWhenExists() throws Exception {
        ServiceResources resources = createServiceWithRepo("get2.db");
        resources.jobRepository().create(job("get-test", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

        TranscodeJob job = resources.service().getJob("get-test");
        assertNotNull(job);
        assertEquals("get-test", job.getId());
    }

    @Test
    void cancelIsIdempotentForNonExistentJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("cancel1.db");
        assertDoesNotThrow(() -> resources.service().cancel("nonexistent-job"));
    }

    @Test
    void cancelOnJobAlreadyInTerminalStateIsNoOp() throws Exception {
        ServiceResources resources = createServiceWithRepo("cancel2.db");
        resources.jobRepository().create(job("cancel-test", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
        resources.jobRepository().updateStatus("cancel-test", JobStatus.PROBING);
        resources.jobRepository().updateStatus("cancel-test", JobStatus.FAILED);

        assertDoesNotThrow(() -> resources.service().cancel("cancel-test"));
    }

    @Test
    void getSegmentOutputDirReturnsNullForUnknownJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("seg1.db");
        assertNull(resources.service().getSegmentOutputDir("nonexistent"));
    }

    @Test
    void jobEventProgressHoldsAllFields() {
        JobEvent.Progress event = new JobEvent.Progress("job1", 50.0, 1.5, 30.0);
        assertEquals("job1", event.getJobId());
        assertEquals(50.0, event.getPercent());
        assertEquals(1.5, event.getSpeed());
        assertEquals(30.0, event.getFps());
    }

    @Test
    void jobEventSegmentHoldsAllFields() {
        JobEvent.Segment event = new JobEvent.Segment("job1", "seg_001.m4s", "video", 6.0);
        assertEquals("job1", event.getJobId());
        assertEquals("seg_001.m4s", event.getName());
        assertEquals("video", event.getRepresentationId());
        assertEquals(6.0, event.getDurationSecs());
    }

    @Test
    void jobEventCompleteHoldsAllFields() {
        JobEvent.Complete event = new JobEvent.Complete("job1", 120.0, 20);
        assertEquals("job1", event.getJobId());
        assertEquals(120.0, event.getDurationSecs());
        assertEquals(20, event.getSegmentsTotal());
    }

    @Test
    void jobEventRetryHoldsAllFields() {
        JobEvent.Retry event = new JobEvent.Retry("job1", 2, "FFmpeg crashed");
        assertEquals("job1", event.getJobId());
        assertEquals(2, event.getAttempt());
        assertEquals("FFmpeg crashed", event.getReason());
    }

    @Test
    void jobEventErrorHoldsAllFields() {
        JobEvent.Error event = new JobEvent.Error("job1", "TRANSCODE_FAILED", "Fatal error");
        assertEquals("job1", event.getJobId());
        assertEquals("TRANSCODE_FAILED", event.getCode());
        assertEquals("Fatal error", event.getMessage());
    }

    @Test
    void getManifestMpdReturnsNullForCachedNonExistentJob() throws Exception {
        TranscodeService service = createService("test-manifest-mpd.db");
        assertNull(service.getManifestMpd("no-such-job"));
    }

    @Test
    void getManifestM3u8ReturnsNullForCachedNonExistentJob() throws Exception {
        TranscodeService service = createService("test-manifest-m3u8.db");
        assertNull(service.getManifestM3u8("no-such-job"));
    }

    @Test
    void getManifestMpdReturnsValidMpdForExistingJobWithNoSegments() throws Exception {
        ServiceResources resources = createServiceWithRepo("test-manifest-empty.db");
        Path mediaFile = mediaDir.resolve("test.mkv");
        Files.createFile(mediaFile);
        resources.jobRepository().create(job("cache-test-job", JobStatus.QUEUED, mediaFile.toString(), "h264_fast", "both"));

        TranscodeService secondService = trackService(
            new TranscodeService(
                TranscodeEngineConfigMapper.toTranscodeEngineConfig(testServerConfig()),
                new com.nyx.ffmpeg.ProbeService(),
                new SegmentCache(),
                new ManifestGenerator(),
                resources.jobRepository(),
                new com.nyx.common.PathSecurity(List.of(mediaDir))
            )
        );

        String mpd1 = secondService.getManifestMpd("cache-test-job");
        String mpd2 = secondService.getManifestMpd("cache-test-job");
        assertNotNull(mpd1);
        assertNotNull(mpd2);
        assertEquals(mpd1, mpd2);
    }

    @Test
    void getManifestM3u8ReturnsConsistentResultOnRepeatedCalls() throws Exception {
        ServiceResources resources = createServiceWithRepo("test-m3u8-cache.db");
        Path mediaFile = mediaDir.resolve("audio.mp4");
        Files.createFile(mediaFile);
        resources.jobRepository().create(job("m3u8-cache-job", JobStatus.QUEUED, mediaFile.toString(), "h264_fast", "hls"));

        String hls1 = resources.service().getManifestM3u8("m3u8-cache-job");
        String hls2 = resources.service().getManifestM3u8("m3u8-cache-job");
        assertNotNull(hls1);
        assertEquals(hls1, hls2);
    }

    @Test
    void getManifestMpdIncludesSubtitleTracksFromProbeResult() throws Exception {
        ServiceResources resources = createServiceWithRepo("sub-mpd1.db");
        resources.jobRepository().create(job("sub-mpd-test", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "dash"));

        ProbeResult probeResult = new ProbeResult(
            "/test.mkv",
            "matroska",
            120.0,
            1_000_000L,
            new ProbeStreams(
                List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                List.of(new AudioStream(1, "aac", 2, null, null, "eng", "English")),
                List.of(
                    new SubtitleStream(2, "subrip", "eng", "English"),
                    new SubtitleStream(3, "subrip", "fra", "French")
                )
            )
        );
        resources.service().getJobProbeResults().put("sub-mpd-test", probeResult);

        String mpd = resources.service().getManifestMpd("sub-mpd-test");
        assertNotNull(mpd);
        assertTrue(mpd.contains("contentType=\"text\""));
        assertTrue(mpd.contains("/api/v1/transcode/jobs/sub-mpd-test/subtitles/2"));
        assertTrue(mpd.contains("/api/v1/transcode/jobs/sub-mpd-test/subtitles/3"));
    }

    @Test
    void getSubtitlePlaylistReturnsNullForUnknownJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("sub-playlist1.db");
        assertNull(resources.service().getSubtitlePlaylist("nonexistent-job", 0));
    }

    @Test
    void getSubtitlePlaylistReturnsHlsPlaylistForKnownJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("sub-playlist2.db");
        resources.jobRepository().create(job("sub-pl-test", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "hls"));

        String playlist = resources.service().getSubtitlePlaylist("sub-pl-test", 1);
        assertNotNull(playlist);
        assertTrue(playlist.contains("#EXTM3U"));
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:99999"));
        assertTrue(playlist.contains("/api/v1/transcode/jobs/sub-pl-test/subtitles/1"));
        assertTrue(playlist.contains("#EXT-X-ENDLIST"));
    }

    @Test
    void cmafProfilePresetsAreRegisteredInTranscodeProfiles() {
        var fast = TranscodeProfiles.findByName("cmaf_h264_fast");
        var quality = TranscodeProfiles.findByName("cmaf_h265_quality");
        assertNotNull(fast);
        assertNotNull(quality);
        assertTrue(fast instanceof com.nyx.ffmpeg.model.CmafProfile);
        assertTrue(quality instanceof com.nyx.ffmpeg.model.CmafProfile);
        assertEquals("cmaf_h264_fast", fast.getName());
        assertEquals("cmaf_h265_quality", quality.getName());
    }

    @Test
    void cmafFormatStringMapsToOutputFormatCmafInBuildCommand() throws Exception {
        ServiceResources resources = createServiceWithRepo("cmaf-format-map.db");
        TranscodeJob job = job("cmaf-map", JobStatus.QUEUED, "/test.mkv", "cmaf_h264_fast", "cmaf");
        ProbeResult probeResult = sampleProbeResult();
        Path outputDir = Files.createTempDirectory(tempDir, "cmaf-out");

        var command = resources.service().buildCommand(job, TranscodeProfiles.CMAF_H264_FAST, probeResult, outputDir, null);
        assertEquals(OutputFormat.Cmaf, command.getOutputFormat());
    }

    @Test
    void getHlsMediaPlaylistReturnsNullForUnknownJob() throws Exception {
        ServiceResources resources = createServiceWithRepo("hls-media-pl2.db");
        assertNull(resources.service().getHlsMediaPlaylist("nonexistent", "video"));
    }

    @Test
    void getHlsMediaPlaylistReturnsPlaylistForVideoRepresentation() throws Exception {
        ServiceResources resources = createServiceWithRepo("hls-media-pl1.db");
        resources.jobRepository().create(job("hls-media-j1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "hls"));

        String playlist = resources.service().getHlsMediaPlaylist("hls-media-j1", "video");
        assertNotNull(playlist);
        assertTrue(playlist.contains("#EXTM3U"));
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"segments/init.mp4\""));
    }

    @Test
    void getHlsMediaPlaylistForCmafJobUsesInit0Segment() throws Exception {
        ServiceResources resources = createServiceWithRepo("hls-cmaf-j1.db");
        resources.jobRepository().create(job("cmaf-media-j1", JobStatus.COMPLETED, "/test.mkv", "cmaf_h264_fast", "cmaf"));

        String playlist = resources.service().getHlsMediaPlaylist("cmaf-media-j1", "video");
        assertNotNull(playlist);
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"segments/init_0.mp4\""));
    }

    @Test
    void cmafVideoRepresentationIdFiltersToStream0SegmentsOnly() throws Exception {
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        registry.register("p2-cmaf-j1", new SegmentInfo("chunk_0_001.m4s", "0", 6.0, 0));
        registry.register("p2-cmaf-j1", new SegmentInfo("chunk_1_001.m4s", "1", 6.0, 1));
        ServiceResources resources = createServiceWithRepo("p2-cmaf-seg.db", registry);
        resources.jobRepository().create(job("p2-cmaf-j1", JobStatus.TRANSCODING, "/test.mkv", "cmaf_h264_fast", "cmaf"));

        String playlist = resources.service().getHlsMediaPlaylist("p2-cmaf-j1", "video");
        assertNotNull(playlist);
        assertTrue(playlist.contains("chunk_0_001.m4s"));
        assertFalse(playlist.contains("chunk_1_001.m4s"));
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"segments/init_0.mp4\""));
    }

    @Test
    void cmafAudioRepresentationIdMapsToStream1() throws Exception {
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        registry.register("p1-cmaf-j2", new SegmentInfo("chunk_0_001.m4s", "0", 6.0, 0));
        registry.register("p1-cmaf-j2", new SegmentInfo("chunk_1_001.m4s", "1", 6.0, 1));
        ServiceResources resources = createServiceWithRepo("p1-cmaf-audio.db", registry);
        resources.jobRepository().create(job("p1-cmaf-j2", JobStatus.TRANSCODING, "/test.mkv", "cmaf_h264_fast", "cmaf"));

        String playlist = resources.service().getHlsMediaPlaylist("p1-cmaf-j2", "audio");
        assertNotNull(playlist);
        assertTrue(playlist.contains("chunk_1_001.m4s"));
        assertFalse(playlist.contains("chunk_0_001.m4s"));
        assertTrue(playlist.contains("#EXT-X-MAP:URI=\"segments/init_1.mp4\""));
    }

    @Test
    void unknownRepresentationIdReturnsEmptyPlaylistForCmafJob() throws Exception {
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        registry.register("p1-cmaf-j3", new SegmentInfo("chunk_0_001.m4s", "0", 6.0, 0));
        ServiceResources resources = createServiceWithRepo("p1-cmaf-bogus.db", registry);
        resources.jobRepository().create(job("p1-cmaf-j3", JobStatus.TRANSCODING, "/test.mkv", "cmaf_h264_fast", "cmaf"));

        String playlist = resources.service().getHlsMediaPlaylist("p1-cmaf-j3", "bogus");
        assertNotNull(playlist);
        assertTrue(playlist.contains("#EXTM3U"));
        assertFalse(playlist.contains("chunk_0_001.m4s"));
    }

    @Test
    void targetDurationInHlsMediaPlaylistComesFromConfig() throws Exception {
        ServiceResources resources = createServiceWithRepo("p3-target-dur.db");
        resources.jobRepository().create(job("p3-dur-j1", JobStatus.COMPLETED, "/test.mkv", "h264_fast", "hls"));

        String playlist = resources.service().getHlsMediaPlaylist("p3-dur-j1", "video");
        assertNotNull(playlist);
        assertTrue(playlist.contains("#EXT-X-TARGETDURATION:6"));
    }

    @Test
    void inMemorySegmentRegistryReturnsEmptyListForUnknownJob() {
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        assertEquals(List.of(), registry.getSegments("unknown"));
        assertEquals(0, registry.count("unknown"));
    }

    @Test
    void inMemorySegmentRegistryRegisterAndCount() {
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        registry.register("job1", new SegmentInfo("seg_0.m4s", "video", 6.0, 0));
        registry.register("job1", new SegmentInfo("seg_1.m4s", "video", 6.0, 1));
        assertEquals(2, registry.count("job1"));
        assertEquals(2, registry.getSegments("job1").size());
    }

    @Test
    void inMemorySegmentRegistryClearResetsSegmentsToEmpty() {
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        registry.register("job1", new SegmentInfo("seg_0.m4s", "video", 6.0, 0));
        registry.clear("job1");
        assertEquals(0, registry.count("job1"));
        assertEquals(List.of(), registry.getSegments("job1"));
    }

    @Test
    void inMemorySegmentRegistryRemoveDropsJobEntirely() {
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        registry.register("job1", new SegmentInfo("seg_0.m4s", "video", 6.0, 0));
        registry.remove("job1");
        assertEquals(0, registry.count("job1"));
    }

    @Test
    void inMemorySegmentRegistryIsThreadSafeUnderConcurrentWritesAndReads() throws Exception {
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        String jobId = "concurrent-job";
        int segmentsPerWriter = 500;
        int writerCount = 4;
        var executor = Executors.newFixedThreadPool(writerCount + 1);

        try {
            var writers = java.util.stream.IntStream.range(0, writerCount)
                .mapToObj(writerIndex -> executor.submit(() -> {
                    for (int index = 0; index < segmentsPerWriter; index += 1) {
                        registry.register(jobId, new SegmentInfo("seg_" + writerIndex + "_" + index + ".m4s", "video", 6.0, index));
                    }
                }))
                .toList();

            var reader = executor.submit(() -> {
                for (int iteration = 0; iteration < 1_000; iteration += 1) {
                    registry.getSegments(jobId).size();
                    registry.count(jobId);
                }
            });

            for (var writer : writers) {
                writer.get(5, TimeUnit.SECONDS);
            }
            reader.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertEquals(writerCount * segmentsPerWriter, registry.count(jobId));
    }

    private static ProbeResult sampleProbeResult() {
        return new ProbeResult(
            "/test.mkv",
            "matroska",
            120.0,
            1_000_000L,
            new ProbeStreams(
                List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                List.of(new AudioStream(1, "aac", 2, null, null, "eng", "English")),
                List.of()
            )
        );
    }

    private static TranscodeJob job(String id, JobStatus status, String inputPath, String profile, String format) {
        return new TranscodeJob(
            id,
            status,
            inputPath,
            profile,
            StreamRepresentationPolicy.defaultPolicy().normalizeExternalName(format),
            List.of(),
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null,
            0,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0L
        );
    }
}
