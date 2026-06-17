package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.config.MediaRootConfig;
import com.nyx.ffmpeg.ProbeService;
import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackOutputSummary;
import com.nyx.playback.contracts.PlaybackReason;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.PlaybackSession;
import com.nyx.playback.contracts.PlaybackSessionArtifacts;
import com.nyx.playback.contracts.PlaybackSessionService;
import com.nyx.playback.contracts.PlaybackSessionState;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.SubtitleDelivery;
import com.nyx.transcode.contracts.BatchCancelResponse;
import com.nyx.transcode.contracts.BatchStatusResponse;
import com.nyx.transcode.contracts.BatchSubmitResponse;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.SegmentCacheService;
import com.nyx.transcode.contracts.TranscodeApplicationService;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeJobListing;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

class TranscodeCompatibilityRoutesTest {
    @Test
    void legacyTranscodeSubmitDelegatesThroughPlaybackSessions() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-transcode-compat-test");
        try {
            Path mediaRoot = Files.createDirectories(tempDir.resolve("movies"));
            Files.write(mediaRoot.resolve("compat.mkv"), new byte[128]);

            PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(
                List.of(new MediaRootConfig(mediaRoot, "local", "movies"))
            );
            StubTranscodeApplicationService transcodeService = new StubTranscodeApplicationService(
                new TranscodeJob(
                    "compat-job",
                    JobStatus.QUEUED,
                    "/media/compat.mkv",
                    "h264_fast",
                    StreamRepresentation.DASH_FMP4
                )
            );
            StubPlaybackCompatibilitySessionService playbackSessionService = new StubPlaybackCompatibilitySessionService(
                transcodeService.createdJob,
                new PlaybackDecision(
                    PlaybackMode.VIDEO_TRANSCODE,
                    new StreamDescriptor(StreamingProtocol.DASH, "fmp4", true),
                    Set.of(PlaybackReason.ADAPTIVE_STREAMING_REQUESTED),
                    false,
                    false,
                    SubtitleDelivery.NONE,
                    new PlaybackOutputSummary()
                ),
                new PlaybackSessionArtifacts(
                    StreamingProtocol.DASH,
                    "/api/v1/playback/sessions/compat-session/manifest.mpd",
                    null,
                    null,
                    "/api/v1/playback/sessions/compat-session/manifest.mpd"
                )
            );

            TranscodeHttpTestSupport.testApplication(app -> {
                app.routing(route -> TranscodeRoutes.transcodeRoutes(
                    route,
                    transcodeService,
                    new NoopSegmentCacheService(),
                    new ProbeService(),
                    pathSecurity,
                    List.of(),
                    null,
                    virtualPathResolver,
                    playbackSessionService,
                    null,
                    null
                ));

                try (Response response = app.client().post(
                    "/api/v1/transcode",
                    request -> {
                        request.header(HttpHeaders.ContentType, ContentType.Application.Json.toString());
                        request.setBody("""
                            {"input_path":"movies/compat.mkv","profile":"h264_fast","format":"dash"}
                            """);
                    }
                )) {
                    assertEquals(HttpStatusCode.Companion.getCreated(), TranscodeHttpTestSupport.status(response));
                    assertEquals(1, playbackSessionService.openRequests.size());
                    assertEquals(0, transcodeService.playbackSubmitCalls);
                    assertEquals(List.of("compat-job"), transcodeService.jobLookupIds);
                    assertEquals(
                        mediaRoot.resolve("compat.mkv").toString(),
                        playbackSessionService.openRequests.get(0).source().path()
                    );
                    assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("\"id\":\"compat-job\""));
                }
            });
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void legacyTranscodeSubmitDelegatesAudioTranscodeOutcomesThroughPlaybackSessions() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-transcode-audio-compat-test");
        try {
            Path mediaRoot = Files.createDirectories(tempDir.resolve("movies"));
            Files.write(mediaRoot.resolve("compat-audio.mkv"), new byte[128]);

            PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(
                List.of(new MediaRootConfig(mediaRoot, "local", "movies"))
            );
            StubTranscodeApplicationService transcodeService = new StubTranscodeApplicationService(
                new TranscodeJob(
                    "compat-audio-job",
                    JobStatus.QUEUED,
                    "/media/compat-audio.mkv",
                    "h264_fast",
                    StreamRepresentation.HLS_FMP4
                )
            );
            StubPlaybackCompatibilitySessionService playbackSessionService = new StubPlaybackCompatibilitySessionService(
                transcodeService.createdJob,
                new PlaybackDecision(
                    PlaybackMode.AUDIO_TRANSCODE,
                    new StreamDescriptor(StreamingProtocol.HLS, "fmp4", true),
                    Set.of(PlaybackReason.AUDIO_CODEC_UNSUPPORTED),
                    true,
                    false,
                    SubtitleDelivery.NONE,
                    new PlaybackOutputSummary()
                ),
                new PlaybackSessionArtifacts(
                    StreamingProtocol.HLS,
                    "/api/v1/playback/sessions/compat-session/master.m3u8",
                    null,
                    "/api/v1/playback/sessions/compat-session/master.m3u8",
                    null
                )
            );

            TranscodeHttpTestSupport.testApplication(app -> {
                app.routing(route -> TranscodeRoutes.transcodeRoutes(
                    route,
                    transcodeService,
                    new NoopSegmentCacheService(),
                    new ProbeService(),
                    pathSecurity,
                    List.of(),
                    null,
                    virtualPathResolver,
                    playbackSessionService,
                    null,
                    null
                ));

                try (Response response = app.client().post(
                    "/api/v1/transcode",
                    request -> {
                        request.header(HttpHeaders.ContentType, ContentType.Application.Json.toString());
                        request.setBody("""
                            {"input_path":"movies/compat-audio.mkv","profile":"h264_fast","format":"hls"}
                            """);
                    }
                )) {
                    assertEquals(HttpStatusCode.Companion.getCreated(), TranscodeHttpTestSupport.status(response));
                    assertEquals(1, playbackSessionService.openRequests.size());
                    assertEquals(
                        Set.of(StreamingProtocol.HLS),
                        playbackSessionService.openRequests.get(0).output().allowedProtocols()
                    );
                    assertEquals(0, transcodeService.playbackSubmitCalls);
                    assertEquals(List.of("compat-audio-job"), transcodeService.jobLookupIds);
                    assertEquals(
                        mediaRoot.resolve("compat-audio.mkv").toString(),
                        playbackSessionService.openRequests.get(0).source().path()
                    );
                    assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("\"id\":\"compat-audio-job\""));
                }
            });
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    void legacyTranscodeSubmitRejectsDirectFileBeforeOpeningPlaybackSession() throws Exception {
        Path tempDir = Files.createTempDirectory("nyx-transcode-direct-file-compat-test");
        try {
            Path mediaRoot = Files.createDirectories(tempDir.resolve("movies"));
            Files.write(mediaRoot.resolve("direct-file.mkv"), new byte[128]);

            PathSecurity pathSecurity = new PathSecurity(List.of(mediaRoot));
            VirtualPathResolver virtualPathResolver = new VirtualPathResolver(
                List.of(new MediaRootConfig(mediaRoot, "local", "movies"))
            );
            StubTranscodeApplicationService transcodeService = new StubTranscodeApplicationService(
                new TranscodeJob(
                    "direct-file-job",
                    JobStatus.QUEUED,
                    "/media/direct-file.mkv",
                    "h264_fast",
                    StreamRepresentation.HLS_FMP4
                )
            );
            StubPlaybackCompatibilitySessionService playbackSessionService = new StubPlaybackCompatibilitySessionService(
                transcodeService.createdJob,
                new PlaybackDecision(
                    PlaybackMode.DIRECT_PLAY,
                    new StreamDescriptor(StreamRepresentation.DIRECT_FILE),
                    Set.of(),
                    true,
                    true,
                    SubtitleDelivery.NONE,
                    new PlaybackOutputSummary()
                ),
                new PlaybackSessionArtifacts(
                    StreamingProtocol.FILE,
                    null,
                    null,
                    null,
                    null
                )
            );

            TranscodeHttpTestSupport.testApplication(app -> {
                app.routing(route -> TranscodeRoutes.transcodeRoutes(
                    route,
                    transcodeService,
                    new NoopSegmentCacheService(),
                    new ProbeService(),
                    pathSecurity,
                    List.of(),
                    null,
                    virtualPathResolver,
                    playbackSessionService,
                    null,
                    null
                ));

                try (Response response = app.client().post(
                    "/api/v1/transcode",
                    request -> {
                        request.header(HttpHeaders.ContentType, ContentType.Application.Json.toString());
                        request.setBody("""
                            {"input_path":"movies/direct-file.mkv","profile":"h264_fast","format":"direct-file"}
                            """);
                    }
                )) {
                    assertEquals(HttpStatusCode.Companion.getBadRequest(), TranscodeHttpTestSupport.status(response));
                    assertTrue(TranscodeHttpTestSupport.bodyAsText(response).contains("INVALID_REQUEST"));
                    assertEquals(0, playbackSessionService.openRequests.size());
                    assertEquals(0, transcodeService.playbackSubmitCalls);
                    assertTrue(transcodeService.jobLookupIds.isEmpty());
                }
            });
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || Files.notExists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
        }
    }

    private static final class StubPlaybackCompatibilitySessionService implements PlaybackSessionService {
        private final TranscodeJob job;
        private final PlaybackDecision decision;
        private final PlaybackSessionArtifacts artifacts;
        private final List<PlaybackRequest> openRequests = new ArrayList<>();

        private StubPlaybackCompatibilitySessionService(
            TranscodeJob job,
            PlaybackDecision decision,
            PlaybackSessionArtifacts artifacts
        ) {
            this.job = job;
            this.decision = decision;
            this.artifacts = artifacts;
        }

        @Override
        public PlaybackSession openSession(PlaybackRequest request, String owner) {
            openRequests.add(request);
            return new PlaybackSession(
                "compat-session",
                null,
                null,
                PlaybackSessionState.READY,
                decision,
                artifacts,
                null,
                null,
                null,
                "2026-04-09T12:00:00Z",
                null
            );
        }

        @Override
        public PlaybackSession getSession(String sessionId, String owner) {
            return null;
        }

        @Override
        public PlaybackSession reportPlayback(String sessionId, MediaSessionPlaybackReport report, String owner) {
            throw new UnsupportedOperationException("Not used in compatibility route test");
        }

        @Override
        public String getSessionJobId(String sessionId, String owner) {
            return job.id();
        }

        @Override
        public void closeSession(String sessionId, String owner) {
        }

        @Override
        public String getHlsManifest(String sessionId, String owner) {
            return null;
        }

        @Override
        public String getDashManifest(String sessionId, String owner) {
            return null;
        }

        @Override
        public Path getDirectContentPath(String sessionId, String owner) {
            return null;
        }
    }

    private static final class StubTranscodeApplicationService implements TranscodeApplicationService {
        private Consumer<JobEvent> onJobEvent;
        private final TranscodeJob createdJob;
        private int playbackSubmitCalls = 0;
        private final List<String> jobLookupIds = new ArrayList<>();

        private StubTranscodeApplicationService(TranscodeJob createdJob) {
            this.createdJob = createdJob;
        }

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
            this.onJobEvent = onJobEvent == null ? null : onJobEvent::accept;
        }

        @Override
        public Flow.Publisher<JobEvent> eventFlow(String jobId) {
            return null;
        }

        @Override
        public TranscodeJob submit(TranscodeRequest request, String batchId, String owner) {
            throw new UnsupportedOperationException("Not used in compatibility route test");
        }

        @Override
        public TranscodeJob submit(PlaybackRequest request, PlaybackDecision decision, String batchId, String owner) {
            playbackSubmitCalls += 1;
            return createdJob;
        }

        @Override
        public BatchSubmitResponse submitBatch(List<TranscodeRequest> requests, String owner) {
            throw new UnsupportedOperationException("Not used in compatibility route test");
        }

        @Override
        public void cancel(String jobId, String owner) {
        }

        @Override
        public BatchCancelResponse cancelBatch(String batchId, String owner) {
            return null;
        }

        @Override
        public BatchStatusResponse getBatchStatus(String batchId, String owner) {
            return null;
        }

        @Override
        public TranscodeJob getJob(String jobId) {
            jobLookupIds.add(jobId);
            return createdJob.id().equals(jobId) ? createdJob : null;
        }

        @Override
        public TranscodeJobListing listJobs(int page, int limit, String owner) {
            return new TranscodeJobListing(List.of(createdJob), 1, page, limit);
        }

        @Override
        public TranscodeJobListing listJobsFiltered(JobStatus status, Integer sinceMinutes, int page, int limit, String owner) {
            return new TranscodeJobListing(List.of(createdJob), 1, page, limit);
        }

        @Override
        public String getLogs(String jobId) {
            return "";
        }

        @Override
        public String getManifestMpd(String jobId) {
            return null;
        }

        @Override
        public String getManifestM3u8(String jobId) {
            return null;
        }

        @Override
        public String getSubtitlePlaylist(String jobId, int trackIndex) {
            return null;
        }

        @Override
        public String getHlsMediaPlaylist(String jobId, String representationId) {
            return null;
        }

        @Override
        public Path getSegmentOutputDir(String jobId) {
            return null;
        }
    }

    private static final class NoopSegmentCacheService implements SegmentCacheService {
        @Override
        public void register(Path segmentPath, String jobId) {
        }

        @Override
        public Path acquire(Path segmentPath) {
            return null;
        }

        @Override
        public void release(Path segmentPath) {
        }

        @Override
        public void startGracePeriod(String jobId) {
        }

        @Override
        public void purgeAll() {
        }

        @Override
        public int entryCount() {
            return 0;
        }
    }
}
