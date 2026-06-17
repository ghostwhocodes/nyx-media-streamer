package com.nyx.transcode.contracts;

import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscodeContractDefaultsCoverageTest {
    @Test
    void migratedTranscodeDtosKeepDefaultsGettersAndDefensiveCopies() {
        TranscodeRepresentation representation = new TranscodeRepresentation(1280, 720, 3_000);
        TranscodeRequest request = new TranscodeRequest("/media/movie.mkv");
        TranscodeJob job = new TranscodeJob(
            "job-1",
            JobStatus.QUEUED,
            "/media/movie.mkv",
            "h264_fast",
            StreamRepresentation.HLS_FMP4
        );
        TranscodeJobListing listing = new TranscodeJobListing(new ArrayList<>(List.of(job)), 1, 2, 25);
        BatchStatusResponse batchStatus = new BatchStatusResponse("batch-1", 2, 1, 0, 1, 0, 0, null);
        BatchSubmitResponse batchSubmit = new BatchSubmitResponse("batch-1", new ArrayList<>(List.of(job)));
        BatchCancelRequest cancelRequest = new BatchCancelRequest(new ArrayList<>(List.of("job-1", "job-2")));
        BatchCancelResponse cancelResponse = new BatchCancelResponse(new ArrayList<>(List.of("job-1")), null);
        BatchJobError batchError = new BatchJobError("/media/movie.mkv", "quota exceeded");

        JobEvent.Progress progress = new JobEvent.Progress("job-1", 25.0, 1.5, 30.0);
        JobEvent.Segment segment = new JobEvent.Segment("job-1", "seg-1.ts", "720p", 6.0);
        JobEvent.Complete complete = new JobEvent.Complete("job-1", 120.0, 20);
        JobEvent.Retry retry = new JobEvent.Retry("job-1", 2, "temporary network failure");
        JobEvent.Error error = new JobEvent.Error("job-1", "ENCODER", "ffmpeg failed");

        assertEquals(1280, representation.getWidth());
        assertEquals(720, representation.getHeight());
        assertEquals(3_000, representation.getBitrateKbps());

        assertEquals("/media/movie.mkv", request.getInputPath());
        assertEquals("h264_fast", request.getProfile());
        assertEquals(StreamRepresentation.HLS_DASH_FMP4, request.getRepresentation());
        assertNull(request.getRepresentations());
        assertEquals("extract", request.getSubtitleMode());
        assertEquals("all", request.getAudioTracks());
        assertEquals("auto", request.getHwaccel());
        assertEquals(TranscodeExecutionMode.VIDEO_TRANSCODE, request.getExecutionMode());
        assertNotNull(request.getSpecKey());

        assertEquals("job-1", job.getId());
        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertEquals("/media/movie.mkv", job.getInputPath());
        assertEquals("h264_fast", job.getProfile());
        assertEquals(StreamRepresentation.HLS_FMP4, job.getRepresentation());
        assertTrue(job.getRepresentations().isEmpty());
        assertEquals(TranscodeExecutionMode.VIDEO_TRANSCODE, job.getExecutionMode());
        assertNotNull(job.getSpecKey());
        assertEquals(0, job.getSegmentsProduced());
        assertEquals(0, job.getRetryCount());
        assertNull(job.getStderrInitial());
        assertNull(job.getStderrFallback());
        assertNull(job.getCreatedAt());
        assertNull(job.getUpdatedAt());
        assertNull(job.getCompletedAt());
        assertNull(job.getManifestUrl());
        assertNull(job.getHlsUrl());
        assertNull(job.getProgressUrl());
        assertNull(job.getBatchId());
        assertNull(job.getOwner());
        assertEquals(0L, job.getOutputSizeBytes());

        assertEquals(1, listing.getJobs().size());
        assertEquals(1, listing.getTotal());
        assertEquals(2, listing.getPage());
        assertEquals(25, listing.getLimit());
        assertNull(listing.getStatusFilter());
        assertNull(listing.getSinceMinutes());

        assertEquals("batch-1", batchStatus.getBatchId());
        assertEquals(2, batchStatus.getTotal());
        assertEquals(1, batchStatus.getPending());
        assertEquals(0, batchStatus.getRunning());
        assertEquals(1, batchStatus.getCompleted());
        assertEquals(0, batchStatus.getFailed());
        assertEquals(0, batchStatus.getCancelled());
        assertTrue(batchStatus.getFailedJobs().isEmpty());

        assertEquals("batch-1", batchSubmit.getBatchId());
        assertEquals(1, batchSubmit.getJobs().size());
        assertTrue(batchSubmit.getErrors().isEmpty());

        assertEquals(List.of("job-1", "job-2"), cancelRequest.getJobIds());
        assertEquals(List.of("job-1"), cancelResponse.getCancelled());
        assertTrue(cancelResponse.getNotFound().isEmpty());
        assertEquals("/media/movie.mkv", batchError.getInputPath());
        assertEquals("quota exceeded", batchError.getError());

        assertEquals("job-1", progress.getJobId());
        assertEquals(25.0, progress.getPercent(), 0.0);
        assertEquals(1.5, progress.getSpeed(), 0.0);
        assertEquals(30.0, progress.getFps(), 0.0);
        assertEquals("seg-1.ts", segment.getName());
        assertEquals("720p", segment.getRepresentationId());
        assertEquals(6.0, segment.getDurationSecs(), 0.0);
        assertEquals(120.0, complete.getDurationSecs(), 0.0);
        assertEquals(20, complete.getSegmentsTotal());
        assertEquals(2, retry.getAttempt());
        assertEquals("temporary network failure", retry.getReason());
        assertEquals("ENCODER", error.getCode());
        assertEquals("ffmpeg failed", error.getMessage());

        assertThrows(UnsupportedOperationException.class, () -> listing.getJobs().add(job));
        assertThrows(UnsupportedOperationException.class, () -> batchSubmit.getJobs().add(job));
        assertThrows(UnsupportedOperationException.class, () -> cancelRequest.getJobIds().add("job-3"));
        assertThrows(UnsupportedOperationException.class, () -> cancelResponse.getCancelled().add("job-2"));
        assertThrows(UnsupportedOperationException.class, () -> job.getRepresentations().add(representation));
    }

    @Test
    void defaultForwardersOnServicesAndStoresPreserveHistoricalArguments() {
        RecordingTranscodeApplicationService applicationService = new RecordingTranscodeApplicationService();
        RecordingTranscodeJobStore jobStore = new RecordingTranscodeJobStore();
        TranscodeRequest request = new TranscodeRequest("/media/movie.mkv");
        PlaybackRequest playbackRequest = new PlaybackRequest(new MediaSourceRef("/media/movie.mkv"));
        PlaybackDecision decision = new PlaybackDecision(
            PlaybackMode.VIDEO_TRANSCODE,
            new StreamDescriptor(StreamingProtocol.HLS)
        );

        applicationService.submit(request);
        applicationService.submit(request, "batch-1");
        applicationService.submit(playbackRequest, decision);
        applicationService.submit(playbackRequest, decision, "batch-3");
        applicationService.submitBatch(List.of(request));
        applicationService.cancel("job-1");
        applicationService.cancelBatch("batch-4");
        applicationService.getBatchStatus("batch-5");
        applicationService.listJobs();
        applicationService.listJobs(3, 40);
        applicationService.listJobsFiltered(JobStatus.QUEUED, 15);
        applicationService.listJobsFiltered(JobStatus.FAILED, 30, 2, 10);

        assertNull(applicationService.lastTranscodeOwner);
        assertNull(applicationService.lastDecisionOwner);
        assertNull(applicationService.lastBatchOwner);
        assertNull(applicationService.lastCancelOwner);
        assertNull(applicationService.lastCancelledBatchOwner);
        assertNull(applicationService.lastStatusOwner);
        assertNull(applicationService.lastListOwner);
        assertNull(applicationService.lastFilteredOwner);
        assertEquals(3, applicationService.lastListPage);
        assertEquals(40, applicationService.lastListLimit);
        assertEquals(2, applicationService.lastFilteredPage);
        assertEquals(10, applicationService.lastFilteredLimit);

        TranscodeJob job = sampleJob();
        jobStore.createWithQuotaCheck(job, 5);
        jobStore.listFiltered(JobStatus.QUEUED, 15);
        jobStore.listFiltered(JobStatus.FAILED, 30, 20, 5L);
        jobStore.countFiltered(JobStatus.RETRYING, 45);
        jobStore.countAll();
        jobStore.listRecent();
        jobStore.listRecent(10);
        jobStore.listRecent(15, 5L);

        assertNull(jobStore.lastQuotaOwner);
        assertEquals(5, jobStore.lastQuotaMaxConcurrent);
        assertNull(jobStore.lastFilteredOwner);
        assertEquals(20, jobStore.lastFilteredLimit);
        assertEquals(5L, jobStore.lastFilteredOffset);
        assertNull(jobStore.lastCountOwner);
        assertNull(jobStore.lastCountAllOwner);
        assertNull(jobStore.lastRecentOwner);
        assertEquals(15, jobStore.lastRecentLimit);
        assertEquals(5L, jobStore.lastRecentOffset);
    }

    private static TranscodeJob sampleJob() {
        return new TranscodeJob(
            "job-1",
            JobStatus.TRANSCODING,
            "/media/movie.mkv",
            "h264_fast",
            StreamRepresentation.HLS_DASH_FMP4,
            List.of(new TranscodeRepresentation(1280, 720, 3_000)),
            null,
            null,
            3,
            1,
            "stderr-initial",
            "stderr-fallback",
            Instant.parse("2026-05-01T10:00:00Z"),
            Instant.parse("2026-05-01T10:05:00Z"),
            null,
            "/manifest.mpd",
            "/master.m3u8",
            "/progress",
            "batch-1",
            "owner-1",
            42_000L
        );
    }

    private static final class RecordingTranscodeApplicationService implements TranscodeApplicationService {
        private Consumer<JobEvent> onJobEvent = event -> {
        };
        private String lastTranscodeOwner;
        private String lastDecisionOwner;
        private String lastBatchOwner;
        private String lastCancelOwner;
        private String lastCancelledBatchOwner;
        private String lastStatusOwner;
        private String lastListOwner;
        private int lastListPage;
        private int lastListLimit;
        private String lastFilteredOwner;
        private int lastFilteredPage;
        private int lastFilteredLimit;

        @Override
        public boolean getCircuitBreakerOpen() {
            return false;
        }

        @Override
        public Consumer<JobEvent> getOnJobEvent() {
            return onJobEvent;
        }

        @Override
        public void setOnJobEvent(Consumer<? super JobEvent> onJobEvent) {
            this.onJobEvent = event -> onJobEvent.accept(event);
        }

        @Override
        public Flow.Publisher<JobEvent> eventFlow(String jobId) {
            return subscriber -> { };
        }

        @Override
        public TranscodeJob submit(TranscodeRequest request, String batchId, String owner) {
            lastTranscodeOwner = owner;
            return sampleJob();
        }

        @Override
        public TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner) {
            lastDecisionOwner = owner;
            return sampleJob();
        }

        @Override
        public BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner) {
            lastBatchOwner = owner;
            return new BatchSubmitResponse("batch", List.of(sampleJob()));
        }

        @Override
        public void cancel(String jobId, String owner) {
            lastCancelOwner = owner;
        }

        @Override
        public BatchCancelResponse cancelBatch(String batchId, String owner) {
            lastCancelledBatchOwner = owner;
            return new BatchCancelResponse(List.of(batchId), List.of());
        }

        @Override
        public BatchStatusResponse getBatchStatus(String batchId, String owner) {
            lastStatusOwner = owner;
            return new BatchStatusResponse(batchId, 1, 0, 1, 0, 0, 0, List.of());
        }

        @Override
        public TranscodeJob getJob(String jobId) {
            return sampleJob();
        }

        @Override
        public TranscodeJobListing listJobs(int page, int limit, String owner) {
            lastListOwner = owner;
            lastListPage = page;
            lastListLimit = limit;
            return new TranscodeJobListing(List.of(sampleJob()), 1, page, limit);
        }

        @Override
        public TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes, int page, int limit, String owner) {
            lastFilteredOwner = owner;
            lastFilteredPage = page;
            lastFilteredLimit = limit;
            return new TranscodeJobListing(List.of(sampleJob()), 1, page, limit, status, sinceMinutes);
        }

        @Override
        public String getLogs(String jobId) {
            return "";
        }

        @Override
        public String getManifestMpd(String jobId) {
            return "";
        }

        @Override
        public String getManifestM3u8(String jobId) {
            return "";
        }

        @Override
        public String getSubtitlePlaylist(String jobId, int trackIndex) {
            return "";
        }

        @Override
        public String getHlsMediaPlaylist(String jobId, String representationId) {
            return "";
        }

        @Override
        public Path getSegmentOutputDir(String jobId) {
            return Path.of("/tmp", jobId);
        }
    }

    private static final class RecordingTranscodeJobStore implements TranscodeJobStore {
        private String lastQuotaOwner;
        private int lastQuotaMaxConcurrent;
        private String lastFilteredOwner;
        private int lastFilteredLimit;
        private long lastFilteredOffset;
        private String lastCountOwner;
        private String lastCountAllOwner;
        private String lastRecentOwner;
        private int lastRecentLimit;
        private long lastRecentOffset;

        @Override
        public TranscodeJob create(TranscodeJob job) {
            return job;
        }

        @Override
        public TranscodeJob createWithQuotaCheck(TranscodeJob job, int maxConcurrent, Long maxStorageBytes) {
            lastQuotaMaxConcurrent = maxConcurrent;
            lastQuotaOwner = maxStorageBytes == null ? null : maxStorageBytes.toString();
            return job;
        }

        @Override
        public TranscodeJob getById(String id) {
            return sampleJob();
        }

        @Override
        public void updateStatus(String id, JobStatus newStatus) {
        }

        @Override
        public void updateProgress(String id, int segmentsProduced) {
        }

        @Override
        public void updateRetryCount(String id, int count) {
        }

        @Override
        public void storeStderr(String id, String initial, String fallback) {
        }

        @Override
        public TranscodeJob findActiveBySpecKey(String specKey) {
            return null;
        }

        @Override
        public List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes, int limit, long offset, String owner) {
            lastFilteredOwner = owner;
            lastFilteredLimit = limit;
            lastFilteredOffset = offset;
            return List.of(sampleJob());
        }

        @Override
        public int countFiltered(JobStatus status, Integer sinceMinutes, String owner) {
            lastCountOwner = owner;
            return 1;
        }

        @Override
        public List<TranscodeJob> listActive() {
            return List.of();
        }

        @Override
        public List<TranscodeJob> listByBatchId(String batchId) {
            return List.of();
        }

        @Override
        public int countActiveByOwner(String ownerId) {
            return 0;
        }

        @Override
        public void updateOutputSize(String id, long sizeBytes) {
        }

        @Override
        public long sumStorageByOwner(String ownerId) {
            return 0L;
        }

        @Override
        public int countAll(String owner) {
            lastCountAllOwner = owner;
            return 0;
        }

        @Override
        public List<TranscodeJob> listRecent(int limit, long offset, String owner) {
            lastRecentOwner = owner;
            lastRecentLimit = limit;
            lastRecentOffset = offset;
            return List.of(sampleJob());
        }
    }
}
