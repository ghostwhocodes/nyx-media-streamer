package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.config.DatabaseConfig;
import com.nyx.config.FfmpegConfig;
import com.nyx.config.MediaRootConfig;
import com.nyx.config.ServerConfig;
import com.nyx.config.TranscodeConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import com.nyx.transcode.contracts.TranscodeRequest;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranscodeServiceDeepTest {
    private final List<HikariDataSource> dataSources = new ArrayList<>();
    private final List<TranscodeService> managedServices = new ArrayList<>();

    private Path tempDir;
    private Path mediaDir;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("nyx-ts-deep-test");
        mediaDir = Files.createDirectories(tempDir.resolve("media"));
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int index = managedServices.size() - 1; index >= 0; index -= 1) {
            managedServices.get(index).shutdown();
        }
        for (HikariDataSource dataSource : dataSources) {
            dataSource.close();
        }
        deleteRecursively(tempDir);
    }

    @Test
    void submitCreatesJobWithCorrectUrls() throws Exception {
        Path fakeProbe = createScript(
            "fake-ffprobe.sh",
            """
            echo '{"format":{"format_name":"matroska","duration":"120.0","size":"1000000"},"streams":[{"index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080,"r_frame_rate":"24/1"},{"index":1,"codec_name":"aac","codec_type":"audio","channels":2}]}'
            exit 0
            """
        );

        ServiceResources resources = createService("submit1.db", "ffmpeg", fakeProbe.toString(), null, 10L);
        Path testFile = Files.createFile(mediaDir.resolve("submit-test.mkv"));

        TranscodeJob job = resources.service().submit(
            new TranscodeRequest(
                testFile.toString(),
                null,
                "h264_fast",
                StreamRepresentation.DASH_FMP4,
                null,
                "extract",
                null,
                "all",
                "auto",
                null,
                null
            )
        );

        assertEquals(JobStatus.QUEUED, job.getStatus());
        assertTrue(job.getManifestUrl().contains("manifest.mpd"));
        assertNull(job.getHlsUrl());
        assertTrue(job.getProgressUrl().contains("progress"));
        assertEquals(testFile.toString(), job.getInputPath());
    }

    @Test
    void submitRejectsPathOutsideMediaRoots() throws Exception {
        ServiceResources resources = createService("submit2.db", "ffmpeg", "ffprobe", null, 10L);

        assertThrows(
            Exception.class,
            () -> resources.service().submit(new TranscodeRequest("/etc/passwd"))
        );
    }

    @Test
    void submitRejectsEmptyPath() throws Exception {
        ServiceResources resources = createService("submit3.db", "ffmpeg", "ffprobe", null, 10L);

        assertThrows(
            Exception.class,
            () -> resources.service().submit(new TranscodeRequest(""))
        );
    }

    @Test
    void submitFailsWhenProbeFails() throws Exception {
        ServiceResources resources = createService("submit4.db", "ffmpeg", "ffprobe", null, 10L);
        Path testFile = Files.createFile(mediaDir.resolve("submit-fail.mkv"));

        assertThrows(
            NyxException.class,
            () -> resources.service().submit(new TranscodeRequest(testFile.toString()))
        );
    }

    @Test
    void processJobTransitionsToFailedWhenFfmpegFails() throws Exception {
        Path fakeProbe = createScript(
            "probe-for-process.sh",
            """
            echo '{"format":{"format_name":"matroska","duration":"60.0","size":"500000"},"streams":[{"index":0,"codec_name":"h264","codec_type":"video","width":1280,"height":720,"r_frame_rate":"30/1"},{"index":1,"codec_name":"aac","codec_type":"audio","channels":2}]}'
            exit 0
            """
        );
        Path fakeFfmpeg = createScript(
            "fake-ffmpeg-fail.sh",
            """
            echo "error: fake failure" >&2
            exit 1
            """
        );

        ServiceResources resources = createService("process1.db", fakeFfmpeg.toString(), fakeProbe.toString(), null, 10L);
        Path testFile = Files.createFile(mediaDir.resolve("process-test.mkv"));
        TranscodeJob job = resources.service().submit(
            new TranscodeRequest(
                testFile.toString(),
                null,
                "h264_fast",
                StreamRepresentation.DASH_FMP4,
                null,
                "extract",
                null,
                "all",
                "auto",
                null,
                null
            )
        );

        TranscodeJob updated = null;
        for (int attempt = 0; attempt < 30; attempt += 1) {
            Thread.sleep(500L);
            updated = resources.jobRepository().getById(job.getId());
            if (updated != null && updated.getStatus() == JobStatus.FAILED) {
                break;
            }
        }

        assertNotNull(updated);
        TranscodeJob result = updated;
        assertTrue(
            Set.of(JobStatus.FAILED, JobStatus.COMPLETED, JobStatus.TRANSCODING, JobStatus.RETRYING).contains(result.getStatus())
        );
    }

    @Test
    void getManifestMpdReturnsStaticMpdForCompletedJob() throws Exception {
        ServiceResources resources = createService("mpd-deep1.db", "ffmpeg", "ffprobe", null, 10L);

        resources.jobRepository().create(new TranscodeJob(
            "mpd-deep1",
            JobStatus.QUEUED,
            "/test.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4
        ));
        resources.jobRepository().updateStatus("mpd-deep1", JobStatus.PROBING);
        resources.jobRepository().updateStatus("mpd-deep1", JobStatus.TRANSCODING);
        resources.jobRepository().updateStatus("mpd-deep1", JobStatus.COMPLETED);

        String mpd = resources.service().getManifestMpd("mpd-deep1");
        assertNotNull(mpd);
        assertTrue(mpd.contains("static"));
    }

    @Test
    void getManifestM3u8ReturnsHlsManifestWithRepresentations() throws Exception {
        ServiceResources resources = createService("m3u8-deep1.db", "ffmpeg", "ffprobe", null, 10L);

        List<TranscodeRepresentation> representations = List.of(
            new TranscodeRepresentation(1280, 720, 3000),
            new TranscodeRepresentation(1920, 1080, 6000)
        );

        resources.jobRepository().create(
            new TranscodeJob(
                "hls-deep1",
                JobStatus.QUEUED,
                "/test.mkv",
                "adaptive_h264",
                StreamRepresentation.HLS_FMP4,
                representations,
                null,
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
            )
        );

        String manifest = resources.service().getManifestM3u8("hls-deep1");
        assertNotNull(manifest);
        assertTrue(manifest.contains("#EXTM3U"));
    }

    @Test
    void cancelEmitsErrorEventToEventFlow() throws Exception {
        ServiceResources resources = createService("cancel-deep1.db", "ffmpeg", "ffprobe", null, 10L);

        String jobId = "cancel-ev1";
        resources.jobRepository().create(new TranscodeJob(
            jobId,
            JobStatus.QUEUED,
            "/test.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4
        ));

        ReplayPublisher<JobEvent> eventFlow = new ReplayPublisher<>(resources.service().getAsyncExecutor(), 64);
        resources.service().getJobEvents().put(jobId, eventFlow);

        resources.service().cancel(jobId);

        JobEvent event = eventFlow.first();
        assertTrue(event instanceof JobEvent.Error);
        assertEquals("CANCELLED", ((JobEvent.Error) event).getCode());
    }

    @Test
    void scheduleDeferredCleanupRemovesJobDataAfterGracePeriod() throws Exception {
        Path dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);
        var databaseResources = JobRepository.createDatabase(dbDir);
        dataSources.add(databaseResources.getDataSource());
        ServerConfig config = testServerConfig(
            mediaDir,
            dbDir,
            "ffmpeg",
            "ffprobe",
            2,
            testTranscodeConfig("both", 0, 6, 10_000, 524_288_000L, 3, 2_000L, 5)
        );

        JobRepository jobRepository = new JobRepository(databaseResources.getJdbi());
        TestScheduledExecutorService scheduler = new TestScheduledExecutorService();
        TranscodeService service = new TranscodeService(
            TranscodeEngineConfigMapper.toTranscodeEngineConfig(config),
            new ProbeService(),
            new SegmentCache(),
            new ManifestGenerator(),
            jobRepository,
            new PathSecurity(List.of(mediaDir)),
            scheduler,
            null,
            new InMemorySegmentRegistry(),
            null,
            new TranscodeCommandFactory()
        );
        managedServices.add(service);

        String jobId = "cleanup-test";
        service.getJobEvents().put(jobId, new ReplayPublisher<>(service.getAsyncExecutor(), 1));

        jobRepository.create(new TranscodeJob(
            jobId,
            JobStatus.QUEUED,
            "/test.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4
        ));

        service.cancel(jobId);
        scheduler.runScheduledTask();

        assertNull(service.getJobEvents().get(jobId));
    }

    @Test
    void submitWithAdaptiveH264ProfileCreatesJobWithRepresentations() throws Exception {
        Path fakeProbe = createScript(
            "probe-adaptive.sh",
            """
            echo '{"format":{"format_name":"matroska","duration":"120.0","size":"1000000"},"streams":[{"index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080,"r_frame_rate":"24/1"},{"index":1,"codec_name":"aac","codec_type":"audio","channels":2}]}'
            exit 0
            """
        );

        ServiceResources resources = createService("adaptive1.db", "ffmpeg", fakeProbe.toString(), null, 10L);
        Path testFile = Files.createFile(mediaDir.resolve("adaptive-test.mkv"));

        List<TranscodeRepresentation> representations = List.of(
            new TranscodeRepresentation(1280, 720, 3000),
            new TranscodeRepresentation(1920, 1080, 6000)
        );

        TranscodeJob job = resources.service().submit(
            new TranscodeRequest(
                testFile.toString(),
                null,
                "adaptive_h264",
                StreamRepresentation.HLS_DASH_FMP4,
                representations,
                "extract",
                null,
                "all",
                "auto",
                null,
                null
            )
        );

        assertEquals(JobStatus.QUEUED, job.getStatus());
    }

    @Test
    void submitWithHlsFormatCreatesCorrectUrls() throws Exception {
        Path fakeProbe = createScript(
            "probe-hls.sh",
            """
            echo '{"format":{"format_name":"matroska","duration":"60.0","size":"500000"},"streams":[{"index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080,"r_frame_rate":"24/1"},{"index":1,"codec_name":"aac","codec_type":"audio","channels":2}]}'
            exit 0
            """
        );

        ServiceResources resources = createService("hls1.db", "ffmpeg", fakeProbe.toString(), null, 10L);
        Path testFile = Files.createFile(mediaDir.resolve("hls-test.mkv"));

        TranscodeJob job = resources.service().submit(
            new TranscodeRequest(
                testFile.toString(),
                null,
                "h264_fast",
                StreamRepresentation.HLS_FMP4,
                null,
                "extract",
                null,
                "all",
                "auto",
                null,
                null
            )
        );

        assertNotNull(job.getHlsUrl());
    }

    @Test
    void submitWithHwAccelAndSubtitleOptions() throws Exception {
        Path fakeProbe = createScript(
            "probe-opts.sh",
            """
            echo '{"format":{"format_name":"matroska","duration":"60.0","size":"500000"},"streams":[{"index":0,"codec_name":"h264","codec_type":"video","width":1920,"height":1080,"r_frame_rate":"24/1"},{"index":1,"codec_name":"aac","codec_type":"audio","channels":2}]}'
            exit 0
            """
        );

        ServiceResources resources = createService("opts1.db", "ffmpeg", fakeProbe.toString(), null, 10L);
        Path testFile = Files.createFile(mediaDir.resolve("opts-test.mkv"));

        TranscodeJob job = resources.service().submit(
            new TranscodeRequest(
                testFile.toString(),
                30.0,
                "h264_fast",
                StreamRepresentation.DASH_FMP4,
                null,
                "burn",
                0,
                "all_stereo",
                "none",
                null,
                null
            )
        );

        assertEquals(JobStatus.QUEUED, job.getStatus());
    }

    @Test
    void segmentInfoDataClassHoldsAllFields() {
        SegmentInfo info = new SegmentInfo("seg_001.m4s", "video", 6.0, 0);
        assertEquals("seg_001.m4s", info.getName());
        assertEquals("video", info.getRepresentationId());
        assertEquals(6.0, info.getDurationSecs());
        assertEquals(0, info.getIndex());
    }

    @Test
    void audioTrackInfoDataClassHoldsAllFields() {
        AudioTrackInfo info = new AudioTrackInfo(0, "eng", "English", 6, "aac", true, 192);
        assertEquals(0, info.getTrackIndex());
        assertEquals("eng", info.getLanguage());
        assertEquals(6, info.getChannels());
        assertTrue(info.isHasDownmix());
    }

    @Test
    void subtitleTrackInfoDataClassHoldsAllFields() {
        SubtitleTrackInfo info = new SubtitleTrackInfo(0, "eng", "English");
        assertEquals(0, info.getTrackIndex());
        assertEquals("eng", info.getLanguage());
    }

    private ServiceResources createService(
        String dbName,
        String ffmpegPath,
        String ffprobePath,
        ScheduledExecutorService cleanupScheduler,
        long retryBackoffMs
    ) throws Exception {
        Path dbDir = tempDir.resolve("db");
        Files.createDirectories(dbDir);
        var databaseResources = JobRepository.createDatabase(dbDir);
        dataSources.add(databaseResources.getDataSource());
        ServerConfig config = testConfig(ffmpegPath, ffprobePath, retryBackoffMs);
        JobRepository jobRepository = new JobRepository(databaseResources.getJdbi());
        TranscodeService service = cleanupScheduler == null
            ? new TranscodeService(
                TranscodeEngineConfigMapper.toTranscodeEngineConfig(config),
                new ProbeService(ffprobePath),
                new SegmentCache(),
                new ManifestGenerator(),
                jobRepository,
                new PathSecurity(List.of(mediaDir))
            )
            : new TranscodeService(
                TranscodeEngineConfigMapper.toTranscodeEngineConfig(config),
                new ProbeService(ffprobePath),
                new SegmentCache(),
                new ManifestGenerator(),
                jobRepository,
                new PathSecurity(List.of(mediaDir)),
                cleanupScheduler,
                null,
                new InMemorySegmentRegistry(),
                null,
                new TranscodeCommandFactory()
            );
        managedServices.add(service);
        return new ServiceResources(service, jobRepository);
    }

    private ServerConfig testConfig(String ffmpegPath, String ffprobePath, long retryBackoffMs) {
        return testServerConfig(
            mediaDir,
            tempDir.resolve("db"),
            ffmpegPath,
            ffprobePath,
            2,
            testTranscodeConfig("both", 1, 6, 10_000, 524_288_000L, 3, retryBackoffMs, 5)
        );
    }

    private TranscodeConfig testTranscodeConfig(
        String defaultFormat,
        int segmentCacheGracePeriodMinutes,
        int segmentDurationSteadyStateSecs,
        int segmentCacheMaxEntries,
        long minFreeDiskBytes,
        int maxRetries,
        long retryBackoffMs,
        int circuitBreakerThreshold
    ) {
        return new TranscodeConfig(
            defaultFormat,
            segmentCacheGracePeriodMinutes,
            segmentDurationSteadyStateSecs,
            segmentCacheMaxEntries,
            minFreeDiskBytes,
            maxRetries,
            retryBackoffMs,
            circuitBreakerThreshold
        );
    }

    private ServerConfig testServerConfig(
        Path mediaRoot,
        Path dbDir,
        String ffmpegPath,
        String ffprobePath,
        int maxConcurrentJobs,
        TranscodeConfig transcodeConfig
    ) {
        return new ServerConfig(
            "0.0.0.0",
            8080,
            List.of("*"),
            List.of(new MediaRootConfig(mediaRoot, "local")),
            new FfmpegConfig(ffmpegPath, ffprobePath, "6.0", maxConcurrentJobs),
            transcodeConfig,
            new DatabaseConfig(dbDir)
        );
    }

    private Path createScript(String name, String content) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/bash\n" + content.stripIndent() + "\n");
        Files.setPosixFilePermissions(
            script,
            Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            )
        );
        return script;
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

    private record ServiceResources(TranscodeService service, JobRepository jobRepository) {}
}
