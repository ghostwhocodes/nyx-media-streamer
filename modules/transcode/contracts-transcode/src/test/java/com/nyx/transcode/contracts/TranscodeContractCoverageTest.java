package com.nyx.transcode.contracts;

import com.nyx.playback.contracts.AudioTrackSelectionMode;
import com.nyx.playback.contracts.HardwareAccelerationPreference;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.json.NyxJson;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.playback.contracts.SubtitleSelectionMode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.function.Consumer;

import static com.nyx.transcode.contracts.ContractFactories.audioTrackSelection;
import static com.nyx.transcode.contracts.ContractFactories.canTransitionTo;
import static com.nyx.transcode.contracts.ContractFactories.mediaSourceRef;
import static com.nyx.transcode.contracts.ContractFactories.playbackDecision;
import static com.nyx.transcode.contracts.ContractFactories.playbackOutputPreferences;
import static com.nyx.transcode.contracts.ContractFactories.playbackRequest;
import static com.nyx.transcode.contracts.ContractFactories.playbackSelection;
import static com.nyx.transcode.contracts.ContractFactories.representationConstraint;
import static com.nyx.transcode.contracts.ContractFactories.streamDescriptor;
import static com.nyx.transcode.contracts.ContractFactories.subtitleSelection;
import static com.nyx.transcode.contracts.ContractFactories.toPlaybackRequest;
import static com.nyx.transcode.contracts.ContractFactories.toTranscodeRequest;
import static com.nyx.transcode.contracts.ContractFactories.transcodePreferences;
import static com.nyx.transcode.contracts.ContractFactories.transcodeRepresentation;
import static com.nyx.transcode.contracts.ContractFactories.transcodeRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscodeContractCoverageTest {
    @Test
    void jobStatusesOnlyAllowTheSupportedTransitions() {
        assertTrue(canTransitionTo(JobStatus.QUEUED, JobStatus.PROBING));
        assertTrue(canTransitionTo(JobStatus.PROBING, JobStatus.TRANSCODING));
        assertTrue(canTransitionTo(JobStatus.PROBING, JobStatus.FAILED));
        assertTrue(canTransitionTo(JobStatus.TRANSCODING, JobStatus.RETRYING));
        assertTrue(canTransitionTo(JobStatus.TRANSCODING, JobStatus.CANCELLED));
        assertTrue(canTransitionTo(JobStatus.RETRYING, JobStatus.TRANSCODING));
        assertTrue(canTransitionTo(JobStatus.RETRYING, JobStatus.FAILED));
        assertTrue(canTransitionTo(JobStatus.RETRYING, JobStatus.CANCELLED));
        assertFalse(canTransitionTo(JobStatus.QUEUED, JobStatus.COMPLETED));
        assertFalse(canTransitionTo(JobStatus.COMPLETED, JobStatus.FAILED));
        assertFalse(canTransitionTo(JobStatus.CANCELLED, JobStatus.QUEUED));
    }

    @Test
    void transcodeRequestsMapIntoPlaybackRequestsAcrossRepresentationAndSelectionModes() {
        TranscodeRequest burnRequest = transcodeRequest(
            "/media/movie.mkv",
            12.5,
            "h264_fast",
            StreamRepresentation.HLS_FMP4,
            List.of(transcodeRepresentation(1920, 1080, 8_000)),
            "burn",
            2,
            "0,2",
            "none",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );
        TranscodeRequest disabledRequest = transcodeRequest(
            "/media/movie.mkv",
            null,
            "h264_fast",
            StreamRepresentation.DASH_FMP4,
            null,
            "disabled",
            null,
            "default",
            "auto",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );
        TranscodeRequest fallbackRequest = transcodeRequest(
            "/media/movie.mkv",
            null,
            "h264_fast",
            StreamRepresentation.HLS_DASH_FMP4,
            null,
            "extract",
            null,
            "garbage",
            "cuda",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );

        PlaybackRequest burnPlayback = toPlaybackRequest(burnRequest);
        PlaybackRequest disabledPlayback = toPlaybackRequest(disabledRequest);
        PlaybackRequest fallbackPlayback = toPlaybackRequest(fallbackRequest);

        assertEquals(12_500L, burnPlayback.startPositionMillis());
        assertEquals(java.util.Set.of(StreamingProtocol.HLS), burnPlayback.output().allowedProtocols());
        assertEquals(StreamingProtocol.HLS, burnPlayback.output().preferredProtocol());
        assertTrue(burnPlayback.output().allowAdaptiveStreaming());
        assertEquals(SubtitleSelectionMode.BURN_IN, burnPlayback.selection().subtitles().mode());
        assertEquals(2, burnPlayback.selection().subtitles().trackIndex());
        assertEquals(AudioTrackSelectionMode.SPECIFIC, burnPlayback.selection().audio().mode());
        assertEquals(List.of(0, 2), burnPlayback.selection().audio().trackIndices());
        assertEquals(HardwareAccelerationPreference.DISABLED, burnPlayback.transcode().hardwareAcceleration());
        assertEquals(1, burnPlayback.transcode().explicitRepresentations().size());

        assertEquals(java.util.Set.of(StreamingProtocol.DASH), disabledPlayback.output().allowedProtocols());
        assertEquals(StreamingProtocol.DASH, disabledPlayback.output().preferredProtocol());
        assertEquals(SubtitleSelectionMode.DISABLE, disabledPlayback.selection().subtitles().mode());
        assertEquals(AudioTrackSelectionMode.DEFAULT, disabledPlayback.selection().audio().mode());
        assertEquals(HardwareAccelerationPreference.AUTO, disabledPlayback.transcode().hardwareAcceleration());

        assertEquals(java.util.Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH), fallbackPlayback.output().allowedProtocols());
        assertNull(fallbackPlayback.output().preferredProtocol());
        assertEquals(SubtitleSelectionMode.EXTRACT, fallbackPlayback.selection().subtitles().mode());
        assertEquals(AudioTrackSelectionMode.ALL, fallbackPlayback.selection().audio().mode());
        assertEquals(HardwareAccelerationPreference.REQUIRED, fallbackPlayback.transcode().hardwareAcceleration());
    }

    @Test
    void transcodeJsonRequestPreservesUnknownExternalFormatFallback() throws Exception {
        TranscodeRequest request = NyxJson.newMapper().readValue(
            """
            {"input_path":"/media/movie.mkv","format":"mystery"}
            """,
            TranscodeRequest.class
        );

        PlaybackRequest playbackRequest = toPlaybackRequest(request);

        assertEquals(StreamRepresentation.HLS_DASH_FMP4, request.representation());
        assertEquals(java.util.Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH), playbackRequest.output().allowedProtocols());
        assertNull(playbackRequest.output().preferredProtocol());
    }

    @Test
    void playbackRequestsMapBackIntoTranscodeRequestsForExecutionDecisions() {
        PlaybackRequest playbackRequest = playbackRequest(
            mediaSourceRef("/media/movie.mkv"),
            15_000,
            playbackOutputPreferences(java.util.Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH), StreamingProtocol.DASH),
            playbackSelection(
                audioTrackSelection(AudioTrackSelectionMode.SPECIFIC, List.of(1, 3)),
                subtitleSelection(SubtitleSelectionMode.BURN_IN, 4)
            ),
            transcodePreferences(
                "hevc_main",
                HardwareAccelerationPreference.REQUIRED,
                List.of(representationConstraint(1280, 720, 4_000))
            )
        );

        TranscodeRequest defaultRequest = toTranscodeRequest(playbackRequest);
        TranscodeRequest remuxRequest = toTranscodeRequest(
            playbackRequest,
            playbackDecision(PlaybackMode.REMUX, streamDescriptor(StreamingProtocol.HLS, true))
        );
        TranscodeRequest audioRequest = toTranscodeRequest(
            playbackRequest,
            playbackDecision(PlaybackMode.AUDIO_TRANSCODE, streamDescriptor(StreamingProtocol.HLS))
        );
        TranscodeRequest videoRequest = toTranscodeRequest(
            playbackRequest,
            playbackDecision(PlaybackMode.VIDEO_TRANSCODE, streamDescriptor(StreamingProtocol.DASH, true))
        );

        assertEquals(StreamRepresentation.HLS_DASH_FMP4, defaultRequest.representation());
        assertEquals("burn", defaultRequest.subtitleMode());
        assertEquals(4, defaultRequest.burnSubtitleTrack());
        assertEquals("1,3", defaultRequest.audioTracks());
        assertEquals("auto", defaultRequest.hwaccel());
        assertEquals("hevc_main", defaultRequest.profile());
        assertEquals(1, defaultRequest.representations().size());

        assertEquals(TranscodeExecutionMode.REMUX, remuxRequest.executionMode());
        assertNull(remuxRequest.representations());
        assertEquals(TranscodeExecutionMode.AUDIO_TRANSCODE, audioRequest.executionMode());
        assertNull(audioRequest.representations());
        assertEquals(TranscodeExecutionMode.VIDEO_TRANSCODE, videoRequest.executionMode());
        assertEquals(1280, videoRequest.representations().get(0).getWidth());
        assertNotEquals(remuxRequest.specKey(), videoRequest.specKey());
        assertThrows(
            IllegalStateException.class,
            () -> toTranscodeRequest(
                playbackRequest,
                playbackDecision(PlaybackMode.DIRECT_PLAY, streamDescriptor(StreamingProtocol.FILE))
            )
        );
    }

    @Test
    void transcodeContractDataTypesPreserveDerivedKeysAndDefaults() {
        TranscodeRequest request = transcodeRequest(
            "/media/movie.mkv",
            1.5000,
            "h264_fast",
            StreamRepresentation.HLS_DASH_FMP4,
            List.of(
                transcodeRepresentation(1920, 1080, 8_000),
                transcodeRepresentation(1280, 720, 4_000)
            ),
            "extract",
            null,
            "all",
            "auto",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        );
        String sameKey = ContractFactories.buildTranscodeSpecKey(
            "/media/movie.mkv",
            1.5,
            "h264_fast",
            StreamRepresentation.HLS_DASH_FMP4,
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            List.of(
                transcodeRepresentation(1280, 720, 4_000),
                transcodeRepresentation(1920, 1080, 8_000)
            ),
            "extract",
            null,
            "all",
            "auto"
        );
        TranscodeJob job = sampleJob();
        TranscodeJobListing listing = new TranscodeJobListing(List.of(job), 1, 2, 25, JobStatus.RETRYING, 15);
        BatchTranscodeRequest batchRequest = new BatchTranscodeRequest(List.of(request));
        BatchJobError batchError = new BatchJobError(request.inputPath(), "quota exceeded");
        BatchSubmitResponse batchSubmit = new BatchSubmitResponse("batch-1", List.of(job), List.of(batchError));
        BatchStatusResponse batchStatus = new BatchStatusResponse("batch-1", 1, 0, 0, 1, 0, 0, List.of());
        BatchCancelRequest batchCancelRequest = new BatchCancelRequest(List.of("job-1", "job-2"));
        BatchCancelResponse batchCancelResponse = new BatchCancelResponse(List.of("job-1"), List.of("job-2"));

        assertEquals(sameKey, request.specKey());
        assertEquals(TranscodeExecutionMode.VIDEO_TRANSCODE, request.executionMode());
        assertEquals("job-1", job.id());
        assertTrue(job.specKey().contains("\"representation\":\"sr_hls_dash_fmp4\""));
        assertTrue(job.specKey().contains("\"executionMode\":\"VIDEO_TRANSCODE\""));
        assertTrue(job.representations().isEmpty());
        assertEquals(JobStatus.RETRYING, listing.statusFilter());
        assertEquals(15, listing.sinceMinutes());
        assertEquals(1, batchRequest.requests().size());
        assertEquals("quota exceeded", batchSubmit.errors().get(0).error());
        assertEquals(1, batchStatus.completed());
        assertEquals(List.of("job-2"), batchCancelResponse.notFound());
        assertEquals(List.of("job-1", "job-2"), batchCancelRequest.jobIds());
    }

    @Test
    void applicationAndStoreContractDefaultsForwardOmittedArguments() {
        TranscodeRequest request = transcodeRequest("/media/movie.mkv");
        PlaybackRequest playbackRequest = playbackRequest(mediaSourceRef("/media/movie.mkv"));
        PlaybackDecision decision = playbackDecision(
            PlaybackMode.SUBTITLE_BURN_IN,
            streamDescriptor(StreamingProtocol.HLS)
        );
        RecordingTranscodeApplicationService recordingService = new RecordingTranscodeApplicationService(sampleJob());
        ManagedTranscodeApplicationService service = recordingService;
        RecordingTranscodeJobStore recordingStore = new RecordingTranscodeJobStore(sampleJob());
        TranscodeJobStore store = recordingStore;
        RecordingSegmentCacheService recordingSegmentCache = new RecordingSegmentCacheService();
        SegmentCacheService segmentCache = recordingSegmentCache;

        JobEvent.Progress progress = new JobEvent.Progress("job-1", 25.0, 1.5, 30.0);
        JobEvent.Segment segment = new JobEvent.Segment("job-1", "seg-1.ts", "720p", 6.0);
        JobEvent.Complete complete = new JobEvent.Complete("job-1", 90.0, 15);
        JobEvent.Retry retry = new JobEvent.Retry("job-1", 2, "temporary network error");
        JobEvent.Error error = new JobEvent.Error("job-1", "ENCODER", "ffmpeg failed");

        assertEquals("job-1", progress.jobId());
        assertEquals(25.0, progress.percent(), 0.0);
        assertEquals("720p", segment.representationId());
        assertEquals(15, complete.segmentsTotal());
        assertEquals("temporary network error", retry.reason());
        assertEquals("ENCODER", error.code());

        service.setOnJobEvent(event -> {
        });
        service.eventFlow("job-1");
        service.submit(request);
        service.submit(playbackRequest, decision);
        service.submitBatch(List.of(request));
        service.cancel("job-1");
        service.cancelBatch("batch-1");
        service.getBatchStatus("batch-1");
        service.getJob("job-1");
        service.listJobs();
        service.listJobsFiltered(JobStatus.QUEUED, 5);
        service.getLogs("job-1");
        service.getManifestMpd("job-1");
        service.getManifestM3u8("job-1");
        service.getSubtitlePlaylist("job-1", 0);
        service.getHlsMediaPlaylist("job-1", "720p");
        service.getSegmentOutputDir("job-1");
        service.shutdown();

        assertEquals("job-1", recordingService.lastCancelledJobId);
        assertNull(recordingService.lastSubmitOwner);
        assertEquals(1, recordingService.lastListPage);
        assertEquals(50, recordingService.lastListLimit);
        assertEquals(TranscodeExecutionMode.SUBTITLE_BURN_IN, recordingService.lastDecisionMode);
        assertTrue(recordingService.shutdownCalled);

        store.create(sampleJob());
        store.createWithQuotaCheck(sampleJob(), 2);
        store.getById("job-1");
        store.updateStatus("job-1", JobStatus.COMPLETED);
        store.updateProgress("job-1", 4);
        store.updateRetryCount("job-1", 2);
        store.storeStderr("job-1", "initial", "fallback");
        store.findActiveBySpecKey("spec-key");
        store.listFiltered(JobStatus.QUEUED, 10);
        store.countFiltered(JobStatus.QUEUED, 10);
        store.listActive();
        store.listByBatchId("batch-1");
        store.countActiveByOwner("owner-1");
        store.updateOutputSize("job-1", 1_024);
        store.sumStorageByOwner("owner-1");
        store.countAll();
        store.listRecent();

        assertNull(recordingStore.lastQuotaStorageBytes);
        assertEquals(50, recordingStore.lastListFilteredLimit);
        assertEquals(0L, recordingStore.lastListFilteredOffset);
        assertNull(recordingStore.lastListFilteredOwner);
        assertNull(recordingStore.lastCountAllOwner);
        assertEquals(50, recordingStore.lastRecentLimit);
        assertEquals(0L, recordingStore.lastRecentOffset);

        Path segmentPath = Path.of("/tmp/segment.ts");
        segmentCache.register(segmentPath, "job-1");
        assertEquals(segmentPath, segmentCache.acquire(segmentPath));
        segmentCache.release(segmentPath);
        segmentCache.startGracePeriod("job-1");
        assertEquals(0, recordingSegmentCache.entryCount());
        segmentCache.purgeAll();
    }

    private static TranscodeJob sampleJob() {
        return new TranscodeJob(
            "job-1",
            JobStatus.QUEUED,
            "/media/movie.mkv",
            "h264_fast",
            StreamRepresentation.HLS_DASH_FMP4
        );
    }

    private static final class RecordingTranscodeApplicationService implements ManagedTranscodeApplicationService {
        private final TranscodeJob job;

        private Consumer<? super JobEvent> onJobEventHandler;

        private String lastSubmitOwner;
        private String lastCancelledJobId;
        private Integer lastListPage;
        private Integer lastListLimit;
        private TranscodeExecutionMode lastDecisionMode;
        private boolean shutdownCalled;

        private RecordingTranscodeApplicationService(TranscodeJob job) {
            this.job = job;
        }

        @Override
        public boolean getCircuitBreakerOpen() {
            return false;
        }

        @Override
        public Consumer<JobEvent> getOnJobEvent() {
            @SuppressWarnings("unchecked")
            Consumer<JobEvent> handler = (Consumer<JobEvent>) onJobEventHandler;
            return handler;
        }

        @Override
        public void setOnJobEvent(Consumer<? super JobEvent> onJobEvent) {
            this.onJobEventHandler = onJobEvent;
        }

        @Override
        public Flow.Publisher<JobEvent> eventFlow(String jobId) {
            return null;
        }

        @Override
        public TranscodeJob submit(TranscodeRequest request, String batchId, String owner) {
            lastSubmitOwner = owner;
            return job;
        }

        @Override
        public TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner) {
            lastSubmitOwner = owner;
            lastDecisionMode = switch (decision.mode()) {
                case REMUX -> TranscodeExecutionMode.REMUX;
                case AUDIO_TRANSCODE -> TranscodeExecutionMode.AUDIO_TRANSCODE;
                case VIDEO_TRANSCODE -> TranscodeExecutionMode.VIDEO_TRANSCODE;
                case SUBTITLE_BURN_IN -> TranscodeExecutionMode.SUBTITLE_BURN_IN;
                case DIRECT_PLAY -> null;
            };
            return job;
        }

        @Override
        public BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner) {
            return new BatchSubmitResponse("batch-1", List.of(job));
        }

        @Override
        public void cancel(String jobId, String owner) {
            lastCancelledJobId = jobId;
        }

        @Override
        public BatchCancelResponse cancelBatch(String batchId, String owner) {
            return new BatchCancelResponse(List.of(job.id()), List.of());
        }

        @Override
        public BatchStatusResponse getBatchStatus(String batchId, String owner) {
            return new BatchStatusResponse(batchId, 1, 1, 0, 0, 0, 0, List.of());
        }

        @Override
        public TranscodeJob getJob(String jobId) {
            return job;
        }

        @Override
        public TranscodeJobListing listJobs(int page, int limit, String owner) {
            lastListPage = page;
            lastListLimit = limit;
            return new TranscodeJobListing(List.of(job), 1, page, limit);
        }

        @Override
        public TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes, int page, int limit, String owner) {
            lastListPage = page;
            lastListLimit = limit;
            return new TranscodeJobListing(List.of(job), 1, page, limit, status, sinceMinutes);
        }

        @Override
        public String getLogs(String jobId) {
            return "logs";
        }

        @Override
        public String getManifestMpd(String jobId) {
            return "manifest.mpd";
        }

        @Override
        public String getManifestM3u8(String jobId) {
            return "master.m3u8";
        }

        @Override
        public String getSubtitlePlaylist(String jobId, int trackIndex) {
            return "subtitles.m3u8";
        }

        @Override
        public String getHlsMediaPlaylist(String jobId, String representationId) {
            return representationId + ".m3u8";
        }

        @Override
        public Path getSegmentOutputDir(String jobId) {
            return Path.of("/tmp/" + jobId);
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }
    }

    private static final class RecordingTranscodeJobStore implements TranscodeJobStore {
        private final TranscodeJob job;

        private Long lastQuotaStorageBytes;
        private Integer lastListFilteredLimit;
        private Long lastListFilteredOffset;
        private String lastListFilteredOwner;
        private String lastCountAllOwner;
        private Integer lastRecentLimit;
        private Long lastRecentOffset;

        private RecordingTranscodeJobStore(TranscodeJob job) {
            this.job = job;
        }

        @Override
        public TranscodeJob create(TranscodeJob job) {
            return job;
        }

        @Override
        public TranscodeJob createWithQuotaCheck(TranscodeJob job, int maxConcurrent, Long maxStorageBytes) {
            lastQuotaStorageBytes = maxStorageBytes;
            return job;
        }

        @Override
        public TranscodeJob getById(String id) {
            return job;
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
            return job;
        }

        @Override
        public List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes, int limit, long offset, String owner) {
            lastListFilteredLimit = limit;
            lastListFilteredOffset = offset;
            lastListFilteredOwner = owner;
            return List.of(job);
        }

        @Override
        public int countFiltered(JobStatus status, Integer sinceMinutes, String owner) {
            return 1;
        }

        @Override
        public List<TranscodeJob> listActive() {
            return List.of(job);
        }

        @Override
        public List<TranscodeJob> listByBatchId(String batchId) {
            return List.of(job);
        }

        @Override
        public int countActiveByOwner(String ownerId) {
            return 1;
        }

        @Override
        public void updateOutputSize(String id, long sizeBytes) {
        }

        @Override
        public long sumStorageByOwner(String ownerId) {
            return 1_024L;
        }

        @Override
        public int countAll(String owner) {
            lastCountAllOwner = owner;
            return 1;
        }

        @Override
        public List<TranscodeJob> listRecent(int limit, long offset, String owner) {
            lastRecentLimit = limit;
            lastRecentOffset = offset;
            return List.of(job);
        }
    }

    private static final class RecordingSegmentCacheService implements SegmentCacheService {
        private final java.util.Map<Path, String> entries = new java.util.LinkedHashMap<>();

        @Override
        public void register(Path segmentPath, String jobId) {
            entries.put(segmentPath, jobId);
        }

        @Override
        public Path acquire(Path segmentPath) {
            return entries.containsKey(segmentPath) ? segmentPath : null;
        }

        @Override
        public void release(Path segmentPath) {
            entries.remove(segmentPath);
        }

        @Override
        public void startGracePeriod(String jobId) {
        }

        @Override
        public void purgeAll() {
            entries.clear();
        }

        @Override
        public int entryCount() {
            return entries.size();
        }
    }
}
