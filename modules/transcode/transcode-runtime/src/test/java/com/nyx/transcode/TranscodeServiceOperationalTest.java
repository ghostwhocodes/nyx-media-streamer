package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.DatabaseResources;
import com.nyx.common.ErrorCode;
import com.nyx.common.PathSecurity;
import com.nyx.common.QuotaService;
import com.nyx.common.RecordingMetricsCollector;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.ffmpeg.model.AdaptiveProfile;
import com.nyx.ffmpeg.model.AudioCodec;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.AudioTrackMode;
import com.nyx.ffmpeg.model.CmafProfile;
import com.nyx.ffmpeg.model.H264Preset;
import com.nyx.ffmpeg.model.HwAccel;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.ffmpeg.model.SubtitleMode;
import com.nyx.ffmpeg.model.TranscodeProfile;
import com.nyx.ffmpeg.model.TranscodeProfiles;
import com.nyx.ffmpeg.model.VideoCodec;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.transcode.contracts.BatchStatusResponse;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TranscodeServiceOperationalTest extends AbstractTranscodeServiceTestSupport {
    @Test
    void recordingMetricsCollectorAccumulatesJobLifecycleCounters() {
        RecordingMetricsCollector collector = new RecordingMetricsCollector();
        collector.jobStarted();
        collector.jobStarted();
        collector.jobFinished();

        assertEquals(2, collector.getJobsStarted());
        assertEquals(1, collector.getJobsFinished());
    }

    @Test
    void recordingMetricsCollectorTracksFfmpegDurationsAndFailures() {
        RecordingMetricsCollector collector = new RecordingMetricsCollector();
        collector.recordFfmpegProcessDuration(1_000L, true);
        collector.recordFfmpegProcessDuration(2_000L, false);

        assertEquals(2, collector.getFfmpegDurations().size());
        assertEquals(1, collector.getFfmpegFailures());
    }

    @Test
    void recordingMetricsCollectorTracksProbeCacheHitsAndMisses() {
        RecordingMetricsCollector collector = new RecordingMetricsCollector();
        collector.recordProbeCacheHit();
        collector.recordProbeCacheHit();
        collector.recordProbeCacheHit();
        collector.recordProbeCacheMiss();

        assertEquals(3, collector.getProbeCacheHits());
        assertEquals(1, collector.getProbeCacheMisses());
    }

    @Test
    void recordingMetricsCollectorTracksThumbnailStats() {
        RecordingMetricsCollector collector = new RecordingMetricsCollector();
        collector.recordThumbnailCacheHit();
        collector.recordThumbnailCacheMiss();
        collector.recordThumbnailGenerationDuration(500L);

        assertEquals(1, collector.getThumbnailCacheHits());
        assertEquals(1, collector.getThumbnailCacheMisses());
        assertEquals(List.of(500L), collector.getThumbnailDurations());
    }

    @Test
    void transcodeServiceConstructedWithRecordingMetricsCollectorDoesNotThrow() throws Exception {
        RecordingMetricsCollector collector = new RecordingMetricsCollector();
        Path dbDir = tempDir.resolve("metrics-ctor-db");
        Files.createDirectories(dbDir);

        DatabaseResources databaseResources = JobRepository.createDatabase(dbDir);
        TranscodeService service;
        try {
            service = trackService(
                new TranscodeService(
                    TranscodeEngineConfigMapper.toTranscodeEngineConfig(testServerConfig(mediaDir, dbDir)),
                    new ProbeService(),
                    new SegmentCache(),
                    new ManifestGenerator(),
                    new JobRepository(databaseResources.getJdbi()),
                    new PathSecurity(List.of(mediaDir)),
                    null,
                    collector,
                    new InMemorySegmentRegistry(),
                    null,
                    new TranscodeCommandFactory()
                )
            );
        } finally {
            databaseResources.getDataSource().close();
        }

        assertNotNull(service);
    }

    @Test
    void transcodeProfileDefaultOutputFormatIsBoth() {
        assertEquals(OutputFormat.Both, TranscodeProfiles.H264_FAST.getDefaultOutputFormat());
    }

    @Test
    void adaptiveProfileDefaultOutputFormatIsBoth() {
        assertEquals(OutputFormat.Both, TranscodeProfiles.ADAPTIVE_H264.getDefaultOutputFormat());
    }

    @Test
    void cmafProfileDefaultOutputFormatIsCmaf() {
        assertEquals(OutputFormat.Cmaf, TranscodeProfiles.CMAF_H264_FAST.getDefaultOutputFormat());
        assertEquals(OutputFormat.Cmaf, TranscodeProfiles.CMAF_H265_QUALITY.getDefaultOutputFormat());
    }

    @Test
    void buildCommandUsesTypedRepresentationOutputFormatInsteadOfProfileDefault() throws Exception {
        ServiceResources resources = createServiceWithRepo("cmaf-dflt-fmt.db");
        ProbeResult probeResult = sampleProbeResult();
        Path outputDir = Files.createTempDirectory(tempDir, "dflt-fmt-out");

        TranscodeJob cmafProfileDualJob = job(
            "cmaf-dflt",
            JobStatus.QUEUED,
            "/test.mkv",
            "cmaf_h264_fast",
            StreamRepresentation.HLS_DASH_FMP4
        );
        var cmafProfileDualCommand = resources.service()
            .buildCommand(cmafProfileDualJob, TranscodeProfiles.CMAF_H264_FAST, probeResult, outputDir, null);
        assertEquals(OutputFormat.Both, cmafProfileDualCommand.getOutputFormat());

        TranscodeJob plainProfileCmafJob = job(
            "plain-dflt",
            JobStatus.QUEUED,
            "/test.mkv",
            "h264_fast",
            StreamRepresentation.CMAF
        );
        var plainProfileCmafCommand = resources.service()
            .buildCommand(plainProfileCmafJob, TranscodeProfiles.H264_FAST, probeResult, outputDir, null);
        assertEquals(OutputFormat.Cmaf, plainProfileCmafCommand.getOutputFormat());
    }

    @Test
    void circuitBreakerStartsClosed() throws Exception {
        TranscodeService service = createService("cb-start.db");
        assertFalse(service.getCircuitBreakerOpen());
        assertEquals(0, service.getConsecutiveFailures().get());
    }

    @Test
    void circuitBreakerOpensAfterThresholdConsecutiveFailures() throws Exception {
        TranscodeService service = createService("cb-open.db");
        int threshold = testServerConfig().transcode().circuitBreakerThreshold();

        for (int index = 0; index < threshold; index += 1) {
            service.getConsecutiveFailures().incrementAndGet();
        }

        assertTrue(service.getCircuitBreakerOpen());
    }

    @Test
    void circuitBreakerStaysClosedBelowThreshold() throws Exception {
        TranscodeService service = createService("cb-below.db");
        int threshold = testServerConfig().transcode().circuitBreakerThreshold();

        for (int index = 0; index < threshold - 1; index += 1) {
            service.getConsecutiveFailures().incrementAndGet();
        }

        assertFalse(service.getCircuitBreakerOpen());
    }

    @Test
    void circuitBreakerResetsToClosedOnSuccess() throws Exception {
        TranscodeService service = createService("cb-reset.db");
        int threshold = testServerConfig().transcode().circuitBreakerThreshold();

        for (int index = 0; index < threshold; index += 1) {
            service.getConsecutiveFailures().incrementAndGet();
        }
        assertTrue(service.getCircuitBreakerOpen());

        service.getConsecutiveFailures().set(0);
        assertFalse(service.getCircuitBreakerOpen());
    }

    @Test
    void retryCountFieldIsZeroOnNewTranscodeJob() {
        TranscodeJob job = job("retry-default", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash");
        assertEquals(0, job.getRetryCount());
    }

    @Test
    void retryCountIsPreservedWhenJobStatusChanges() {
        TranscodeJob job = new TranscodeJob(
            "retry-copy",
            JobStatus.RETRYING,
            "/test.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4,
            List.of(),
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null,
            0,
            2,
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
        TranscodeJob updated = new TranscodeJob(
            job.getId(),
            JobStatus.TRANSCODING,
            job.getInputPath(),
            job.getProfile(),
            job.getRepresentation(),
            job.getRepresentations(),
            job.getExecutionMode(),
            job.getSpecKey(),
            job.getSegmentsProduced(),
            job.getRetryCount(),
            job.getStderrInitial(),
            job.getStderrFallback(),
            job.getCreatedAt(),
            job.getUpdatedAt(),
            job.getCompletedAt(),
            job.getManifestUrl(),
            job.getHlsUrl(),
            job.getProgressUrl(),
            job.getBatchId(),
            job.getOwner(),
            job.getOutputSizeBytes()
        );

        assertEquals(2, updated.getRetryCount());
    }

    @Test
    void maxRetriesConfigDefaultsTo3() {
        assertEquals(3, testServerConfig().transcode().maxRetries());
    }

    @Test
    void retryBackoffMsConfigDefaultsTo2000() {
        assertEquals(2_000L, testServerConfig().transcode().retryBackoffMs());
    }

    @Test
    void circuitBreakerThresholdConfigDefaultsTo5() {
        assertEquals(5, testServerConfig().transcode().circuitBreakerThreshold());
    }

    @Test
    void submitThrowsImmediatelyForUnknownProfileName() throws Exception {
        ServiceResources resources = createServiceWithRepo("bad-profile-submit.db");
        Path testFile = Files.createFile(mediaDir.resolve("bad-profile-test.mkv"));

        var exception = assertThrows(
            com.nyx.common.NyxException.class,
            () -> resources.service().submit(
                new TranscodeRequest(
                    testFile.toString(),
                    null,
                    "nonexistent_profile",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                )
            )
        );
        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
    }

    @Test
    void adaptiveProfileHasNonEmptyRepresentationsForSubmitFallback() {
        assertFalse(
            TranscodeProfiles.ADAPTIVE_H264.getRepresentations().isEmpty(),
            "ADAPTIVE_H264.representations must be non-empty; submit() uses it when the request omits representations"
        );
    }

    @Test
    void quotaCancelledJobsSetPreventsRetryAndMarksJobCancelled() throws Exception {
        Path dbDir = tempDir.resolve("db-quota-cancel");
        Files.createDirectories(dbDir);
        DatabaseResources databaseResources = JobRepository.createDatabase(dbDir);
        try {
            JobRepository jobRepository = new JobRepository(databaseResources.getJdbi());
            QuotaService quotaService = new QuotaService(
                testQuotaConfig(true, 10, 100, 1L, Map.of()),
                jobRepository::countActiveByOwner,
                jobRepository::sumStorageByOwner
            );
            TranscodeService service = trackService(
                new TranscodeService(
                    TranscodeEngineConfigMapper.toTranscodeEngineConfig(testServerConfig(mediaDir, dbDir)),
                    new ProbeService(),
                    new SegmentCache(),
                    new ManifestGenerator(),
                    jobRepository,
                    new PathSecurity(List.of(mediaDir)),
                    null,
                    null,
                    new InMemorySegmentRegistry(),
                    quotaService,
                    new TranscodeCommandFactory()
                )
            );

            assertTrue(service.getQuotaCancelledJobs().isEmpty());

            String testJobId = "quota-cancel-test";
            service.getQuotaCancelledJobs().add(testJobId);
            assertTrue(service.getQuotaCancelledJobs().contains(testJobId));

            service.getQuotaCancelledJobs().remove(testJobId);
            assertFalse(service.getQuotaCancelledJobs().contains(testJobId));
        } finally {
            databaseResources.getDataSource().close();
        }
    }

    @Test
    void quotaCancelDoesNotIncrementConsecutiveFailures() throws Exception {
        Path dbDir = tempDir.resolve("db-quota-cb");
        Files.createDirectories(dbDir);
        DatabaseResources databaseResources = JobRepository.createDatabase(dbDir);
        try {
            JobRepository jobRepository = new JobRepository(databaseResources.getJdbi());
            QuotaService quotaService = new QuotaService(
                testQuotaConfig(true, 10, 100, 1L, Map.of()),
                jobRepository::countActiveByOwner,
                jobRepository::sumStorageByOwner
            );
            TranscodeService service = trackService(
                new TranscodeService(
                    TranscodeEngineConfigMapper.toTranscodeEngineConfig(testServerConfig(mediaDir, dbDir)),
                    new ProbeService(),
                    new SegmentCache(),
                    new ManifestGenerator(),
                    jobRepository,
                    new PathSecurity(List.of(mediaDir)),
                    null,
                    null,
                    new InMemorySegmentRegistry(),
                    quotaService,
                    new TranscodeCommandFactory()
                )
            );

            assertEquals(0, service.getConsecutiveFailures().get());

            service.getQuotaCancelledJobs().add("some-job");
            assertEquals(0, service.getConsecutiveFailures().get());
        } finally {
            databaseResources.getDataSource().close();
        }
    }

    @Test
    void quotaCancelEmitsQuotaExceededEvent() throws Exception {
        Path dbDir = tempDir.resolve("db-quota-event");
        Files.createDirectories(dbDir);
        DatabaseResources databaseResources = JobRepository.createDatabase(dbDir);
        try {
            JobRepository jobRepository = new JobRepository(databaseResources.getJdbi());
            TranscodeService service = trackService(
                new TranscodeService(
                    TranscodeEngineConfigMapper.toTranscodeEngineConfig(testServerConfig(mediaDir, dbDir)),
                    new ProbeService(),
                    new SegmentCache(),
                    new ManifestGenerator(),
                    jobRepository,
                    new PathSecurity(List.of(mediaDir))
                )
            );

            jobRepository.create(
                new TranscodeJob(
                    "quota-evt-test",
                    JobStatus.QUEUED,
                    "/test.mkv",
                    "h264_fast",
                    StreamRepresentation.DASH_FMP4,
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
                    "user1",
                    0L
                )
            );
            jobRepository.updateStatus("quota-evt-test", JobStatus.PROBING);
            jobRepository.updateStatus("quota-evt-test", JobStatus.TRANSCODING);

            JobEvent.Error event = new JobEvent.Error(
                "quota-evt-test",
                "QUOTA_EXCEEDED",
                "Per-user storage quota exceeded"
            );
            assertEquals("quota-evt-test", event.getJobId());
            assertEquals("QUOTA_EXCEEDED", event.getCode());
            assertEquals("Per-user storage quota exceeded", event.getMessage());
            assertNotNull(service);
        } finally {
            databaseResources.getDataSource().close();
        }
    }

    @Test
    void shutdownClosesJobChannelAndCancelsRuntimeScope() throws Exception {
        ServiceResources resources = createServiceAndRepo("shutdown-1");
        resources.service().getJobEvents().put("shutdown-job", new ReplayPublisher<>(resources.service().getAsyncExecutor(), 1));
        resources.service().getSegmentOutputDirs().put("shutdown-job", tempDir.resolve("segments"));
        resources.service().getQuotaCancelledJobs().add("shutdown-job");

        resources.service().shutdown();

        assertTrue(resources.service().getActiveProcesses().isEmpty());
        assertTrue(resources.service().getJobEvents().isEmpty());
        assertTrue(resources.service().getSegmentOutputDirs().isEmpty());
        assertTrue(resources.service().getQuotaCancelledJobs().isEmpty());
    }

    @Test
    void shutdownCancelsActiveProcesses() throws Exception {
        ServiceResources resources = createServiceAndRepo("shutdown-2");
        resources.jobRepository().create(job("shutdown-proc-1", JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

        assertTrue(resources.service().getActiveProcesses().isEmpty());
        resources.service().shutdown();
        assertTrue(resources.service().getActiveProcesses().isEmpty());
    }

    @Test
    void shutdownIsIdempotent() throws Exception {
        ServiceResources resources = createServiceAndRepo("shutdown-3");

        resources.service().shutdown();
        assertTrue(resources.service().getActiveProcesses().isEmpty());

        assertDoesNotThrow(resources.service()::shutdown);
    }

    @Test
    void listJobsFilteredWithOnlyStatusCoversDefaultParameterVariant() throws Exception {
        ServiceResources resources = createServiceAndRepo("filter-default-1");
        resources.jobRepository().create(job("flt-d1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));
        resources.jobRepository().create(job("flt-d2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "dash"));
        resources.jobRepository().updateStatus("flt-d2", JobStatus.PROBING);
        resources.jobRepository().updateStatus("flt-d2", JobStatus.TRANSCODING);
        resources.jobRepository().updateStatus("flt-d2", JobStatus.COMPLETED);

        TranscodeJobListing listing = resources.service().listJobsFiltered(JobStatus.COMPLETED, null);
        assertEquals(1, listing.getTotal());
        assertEquals(1, listing.getPage());
        assertEquals(50, listing.getLimit());
        assertEquals(JobStatus.COMPLETED, listing.getStatusFilter());
        assertNull(listing.getSinceMinutes());
        assertTrue(listing.getJobs().stream().allMatch(job -> job.getStatus() == JobStatus.COMPLETED));
    }

    @Test
    void listJobsFilteredWithSinceMinutesCoversDefaultParameterVariant() throws Exception {
        ServiceResources resources = createServiceAndRepo("filter-default-2");
        resources.jobRepository().create(job("flt-s1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash"));

        TranscodeJobListing listing = resources.service().listJobsFiltered(null, 60);
        assertTrue(listing.getTotal() >= 1);
        assertEquals(1, listing.getPage());
        assertEquals(50, listing.getLimit());
        assertEquals(60, listing.getSinceMinutes());
    }

    @Test
    void listJobsFilteredWithExplicitPageAndLimitOverridesDefaults() throws Exception {
        ServiceResources resources = createServiceAndRepo("filter-explicit-1");
        for (int index = 1; index <= 5; index += 1) {
            resources.jobRepository().create(job("flt-e" + index, JobStatus.QUEUED, "/test" + index + ".mkv", "h264_fast", "dash"));
        }

        TranscodeJobListing listing = resources.service().listJobsFiltered(JobStatus.QUEUED, null, 2, 2);
        assertEquals(5, listing.getTotal());
        assertEquals(2, listing.getPage());
        assertEquals(2, listing.getLimit());
    }

    @Test
    void listJobsFilteredWithOwnerFiltersByOwner() throws Exception {
        ServiceResources resources = createServiceAndRepo("filter-owner-1");
        resources.jobRepository().create(job("flt-o1", JobStatus.QUEUED, "/a.mkv", "h264_fast", "dash", null, "alice", 0));
        resources.jobRepository().create(job("flt-o2", JobStatus.QUEUED, "/b.mkv", "h264_fast", "dash", null, "bob", 0));

        TranscodeJobListing listing = resources.service().listJobsFiltered(null, null, 1, 50, "alice");
        assertTrue(listing.getJobs().stream().allMatch(job -> "alice".equals(job.getOwner())));
    }

    @Test
    void parseHwAccelMapsValuesCorrectlyFromImprovements() throws Exception {
        TranscodeService service = createService("parse-improv.db");

        assertEquals(HwAccel.None, service.parseHwAccel("none"));
        assertInstanceOf(HwAccel.Vaapi.class, service.parseHwAccel("vaapi"));
        assertEquals(HwAccel.Nvenc, service.parseHwAccel("nvenc"));
        assertEquals(HwAccel.Qsv, service.parseHwAccel("qsv"));
        assertEquals(HwAccel.Auto, service.parseHwAccel("auto"));
        assertEquals(HwAccel.Auto, service.parseHwAccel("unknown"));

        SubtitleMode.BurnIn burnMode = assertInstanceOf(SubtitleMode.BurnIn.class, service.parseSubtitleMode("burn", 2));
        assertEquals(2, burnMode.getTrackIndex());

        SubtitleMode.BurnIn burnModeDefault = assertInstanceOf(SubtitleMode.BurnIn.class, service.parseSubtitleMode("burn", null));
        assertEquals(0, burnModeDefault.getTrackIndex());

        assertEquals(SubtitleMode.Extract, service.parseSubtitleMode("extract", null));
        assertEquals(SubtitleMode.Extract, service.parseSubtitleMode("other", null));

        assertEquals(AudioTrackMode.All, service.parseAudioTrackMode("all"));
        assertEquals(AudioTrackMode.AllWithStereoDownmix, service.parseAudioTrackMode("all_stereo"));
        AudioTrackMode.Single single = assertInstanceOf(AudioTrackMode.Single.class, service.parseAudioTrackMode("2"));
        assertEquals(2, single.getIndex());
        assertEquals(AudioTrackMode.All, service.parseAudioTrackMode("invalid"));
    }

    @Test
    void getBatchStatusWithoutOwnerReturnsAllJobsInBatch() throws Exception {
        ServiceResources resources = createServiceWithRepo("batch-owner-1");
        resources.jobRepository().create(job("bo1", JobStatus.COMPLETED, "/a.mkv", "h264_fast", "dash", "b1", "alice", 0));
        resources.jobRepository().create(job("bo2", JobStatus.FAILED, "/b.mkv", "h264_fast", "dash", "b1", "bob", 0));

        BatchStatusResponse status = resources.service().getBatchStatus("b1");
        assertNotNull(status);
        assertEquals(2, status.getTotal());
    }

    @Test
    void getBatchStatusWithOwnerFiltersToThatOwner() throws Exception {
        ServiceResources resources = createServiceWithRepo("batch-owner-2");
        resources.jobRepository().create(job("bo3", JobStatus.COMPLETED, "/a.mkv", "h264_fast", "dash", "b2", "alice", 0));
        resources.jobRepository().create(job("bo4", JobStatus.FAILED, "/b.mkv", "h264_fast", "dash", "b2", "bob", 0));

        BatchStatusResponse aliceStatus = resources.service().getBatchStatus("b2", "alice");
        assertNotNull(aliceStatus);
        assertEquals(1, aliceStatus.getTotal());
        assertEquals(1, aliceStatus.getCompleted());

        BatchStatusResponse bobStatus = resources.service().getBatchStatus("b2", "bob");
        assertNotNull(bobStatus);
        assertEquals(1, bobStatus.getTotal());
        assertEquals(1, bobStatus.getFailed());
    }

    @Test
    void getBatchStatusWithOwnerReturnsNullWhenNoJobsMatch() throws Exception {
        ServiceResources resources = createServiceWithRepo("batch-owner-3");
        resources.jobRepository().create(job("bo5", JobStatus.COMPLETED, "/a.mkv", "h264_fast", "dash", "b3", "alice", 0));

        assertNull(resources.service().getBatchStatus("b3", "charlie"));
    }

    private static ProbeResult sampleProbeResult() {
        return new ProbeResult(
            "/test.mkv",
            "matroska",
            60.0,
            1_000_000L,
            new ProbeStreams(
                List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                List.of(new AudioStream(1, "aac", 2, null, null, "eng", "English")),
                List.of()
            )
        );
    }

    private static TranscodeJob job(
        String id,
        JobStatus status,
        String inputPath,
        String profile,
        String format
    ) {
        return job(id, status, inputPath, profile, StreamRepresentationPolicy.defaultPolicy().normalizeExternalName(format), null, null, 0);
    }

    private static TranscodeJob job(
        String id,
        JobStatus status,
        String inputPath,
        String profile,
        StreamRepresentation representation
    ) {
        return job(id, status, inputPath, profile, representation, null, null, 0);
    }

    private static TranscodeJob job(
        String id,
        JobStatus status,
        String inputPath,
        String profile,
        String format,
        String batchId,
        String owner,
        int retryCount
    ) {
        return job(
            id,
            status,
            inputPath,
            profile,
            StreamRepresentationPolicy.defaultPolicy().normalizeExternalName(format),
            batchId,
            owner,
            retryCount
        );
    }

    private static TranscodeJob job(
        String id,
        JobStatus status,
        String inputPath,
        String profile,
        StreamRepresentation representation,
        String batchId,
        String owner,
        int retryCount
    ) {
        return new TranscodeJob(
            id,
            status,
            inputPath,
            profile,
            representation,
            List.of(),
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null,
            0,
            retryCount,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            batchId,
            owner,
            0L
        );
    }
}
