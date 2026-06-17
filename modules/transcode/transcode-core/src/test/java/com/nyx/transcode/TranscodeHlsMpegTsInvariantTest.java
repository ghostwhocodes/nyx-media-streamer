package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import com.nyx.transcode.contracts.TranscodeJobStore;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranscodeHlsMpegTsInvariantTest {
    @TempDir
    Path tempDir;

    @Test
    void submitConsumesTypedHlsMpegTsWithoutRepeatingPlaybackConstraintValidation() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path input = Files.writeString(mediaRoot.resolve("movie.mkv"), "not a real movie");
        InMemoryJobStore jobStore = new InMemoryJobStore();
        TranscodeApplicationCoreService service = service(mediaRoot, jobStore, new InMemorySegmentRegistry());
        service.attachRuntime(enqueuingRuntime());

        TranscodeJob job = service.submit(new TranscodeRequest(
            input.toString(),
            null,
            "h264_fast",
            StreamRepresentation.HLS_MPEG_TS,
            List.of(
                new TranscodeRepresentation(854, 480, 1_500),
                new TranscodeRepresentation(1280, 720, 3_000)
            ),
            "extract",
            null,
            "all",
            "auto",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        ));

        assertEquals(StreamRepresentation.HLS_MPEG_TS, job.getRepresentation());
        assertEquals(2, job.getRepresentations().size());
        assertEquals(1, jobStore.createdCount());
    }

    @Test
    void hlsTsJobsPreserveSingleRequestedVariantMetadata() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path input = Files.writeString(mediaRoot.resolve("movie.mkv"), "not a real movie");
        InMemoryJobStore jobStore = new InMemoryJobStore();
        InMemorySegmentRegistry registry = new InMemorySegmentRegistry();
        registry.register("job-hls-ts", new SegmentInfo("chunk_0_001.ts", "video", 6.0, 0));
        registry.register("job-hls-ts", new SegmentInfo("chunk_0_002.ts", "video", 6.0, 1));
        jobStore.create(job(
            "job-hls-ts",
            JobStatus.TRANSCODING,
            input.toString(),
            StreamRepresentation.HLS_MPEG_TS,
            List.of(
                new TranscodeRepresentation(854, 480, 1_500)
            )
        ));
        TranscodeApplicationCoreService service = service(mediaRoot, jobStore, registry);

        String master = service.getManifestM3u8("job-hls-ts");
        assertNotNull(master);
        assertTrue(master.contains("#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=854x480"));
        assertTrue(master.contains("854x480.m3u8"));
        assertFalse(master.contains("video.m3u8"));
        assertFalse(master.contains("BANDWIDTH=0"));

        String videoPlaylist = service.getHlsMediaPlaylist("job-hls-ts", "video");
        String variantPlaylist = service.getHlsMediaPlaylist("job-hls-ts", "854x480");
        assertNotNull(videoPlaylist);
        assertEquals(videoPlaylist, variantPlaylist);
        assertTrue(variantPlaylist.contains("chunk_0_001.ts"));
        assertTrue(variantPlaylist.contains("chunk_0_002.ts"));
        assertFalse(variantPlaylist.contains("#EXT-X-MAP"));
    }

    @Test
    void hlsTsJobsDoNotAdvertiseOrServeDashManifestArtifacts() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path input = Files.writeString(mediaRoot.resolve("movie.mkv"), "not a real movie");
        InMemoryJobStore jobStore = new InMemoryJobStore();
        TranscodeApplicationCoreService service = service(mediaRoot, jobStore, new InMemorySegmentRegistry());
        service.attachRuntime(enqueuingRuntime());

        TranscodeJob job = service.submit(new TranscodeRequest(
            input.toString(),
            null,
            "h264_fast",
            StreamRepresentation.HLS_MPEG_TS,
            List.of(new TranscodeRepresentation(854, 480, 1_500)),
            "extract",
            null,
            "all",
            "auto",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        ));

        assertNull(job.getManifestUrl());
        assertNotNull(job.getHlsUrl());
        assertNull(service.getManifestMpd(job.getId()));
    }

    @Test
    void submitRejectsDirectFileRepresentationBeforeCreatingJob() throws Exception {
        Path mediaRoot = Files.createDirectories(tempDir.resolve("media"));
        Path input = Files.writeString(mediaRoot.resolve("movie.mkv"), "not a real movie");
        InMemoryJobStore jobStore = new InMemoryJobStore();
        TranscodeApplicationCoreService service = service(mediaRoot, jobStore, new InMemorySegmentRegistry());

        NyxException exception = assertThrows(NyxException.class, () -> service.submit(new TranscodeRequest(
            input.toString(),
            null,
            "h264_fast",
            StreamRepresentation.DIRECT_FILE,
            null,
            "extract",
            null,
            "all",
            "auto",
            TranscodeExecutionMode.VIDEO_TRANSCODE,
            null
        )));

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("direct-play playback delivery"));
        assertEquals(0, jobStore.createdCount());
    }

    private TranscodeApplicationCoreService service(
        Path mediaRoot,
        InMemoryJobStore jobStore,
        InMemorySegmentRegistry registry
    ) {
        return new TranscodeApplicationCoreService(
            new TranscodeEngineConfig(
                new TranscodeEngineConfig.FfmpegRuntimeConfig("ffmpeg", 2, 10, "poll", 100L),
                new TranscodeEngineConfig.RuntimeConfig(5, 6, 0L, 3, 2_000L, 10),
                tempDir.resolve("db")
            ),
            new StaticMediaProber(),
            null,
            new ManifestGenerator(),
            jobStore,
            new PathSecurity(List.of(mediaRoot)),
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "transcode-hls-ts-test");
                thread.setDaemon(true);
                return thread;
            }),
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "transcode-hls-ts-cleanup-test");
                thread.setDaemon(true);
                return thread;
            }),
            null,
            registry,
            null,
            new TranscodeCommandFactory(),
            null,
            null
        );
    }

    private static TranscodeRuntimeController enqueuingRuntime() {
        return new TranscodeRuntimeController() {
            @Override
            public boolean enqueue(TranscodeJob job) {
                return true;
            }

            @Override
            public void cancel(String jobId) {
            }

            @Override
            public String activeStderr(String jobId) {
                return null;
            }
        };
    }

    private static TranscodeJob job(
        String id,
        JobStatus status,
        String inputPath,
        StreamRepresentation representation,
        List<TranscodeRepresentation> representations
    ) {
        return new TranscodeJob(
            id,
            status,
            inputPath,
            "h264_fast",
            representation,
            representations,
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

    private static ProbeResult sampleProbeResult() {
        return new ProbeResult(
            "/media/test.mkv",
            "matroska",
            120.0,
            1_000_000L,
            new ProbeStreams(
                List.of(new VideoStream(0, "h264", 1920, 1080, 24.0)),
                List.of(new AudioStream(1, "aac", 2, 192)),
                List.of()
            )
        );
    }

    private static final class StaticMediaProber implements MediaProber {
        @Override
        public ProbeResult probe(Path path) {
            return sampleProbeResult();
        }

        @Override
        public ProbeResult probeCached(Path path) {
            return sampleProbeResult();
        }

        @Override
        public void clearCache() {
        }
    }

    private static final class InMemoryJobStore implements TranscodeJobStore {
        private final Map<String, TranscodeJob> jobs = new HashMap<>();
        private int createdCount;

        int createdCount() {
            return createdCount;
        }

        @Override
        public TranscodeJob create(TranscodeJob job) {
            createdCount++;
            jobs.put(job.getId(), job);
            return job;
        }

        @Override
        public TranscodeJob createWithQuotaCheck(TranscodeJob job, int maxConcurrent, Long maxStorageBytes) {
            return create(job);
        }

        @Override
        public TranscodeJob getById(String id) {
            return jobs.get(id);
        }

        @Override
        public void updateStatus(String id, JobStatus newStatus) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateProgress(String id, int segmentsProduced) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateRetryCount(String id, int count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void storeStderr(String id, String initial, String fallback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TranscodeJob findActiveBySpecKey(String specKey) {
            return null;
        }

        @Override
        public List<TranscodeJob> listFiltered(JobStatus status, Integer sinceMinutes, int limit, long offset, String owner) {
            return List.of();
        }

        @Override
        public int countFiltered(JobStatus status, Integer sinceMinutes, String owner) {
            return 0;
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
            throw new UnsupportedOperationException();
        }

        @Override
        public long sumStorageByOwner(String ownerId) {
            return 0L;
        }

        @Override
        public int countAll(String owner) {
            return jobs.size();
        }

        @Override
        public List<TranscodeJob> listRecent(int limit, long offset, String owner) {
            return List.copyOf(jobs.values());
        }
    }
}
