package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackDecision;
import com.nyx.playback.contracts.PlaybackMode;
import com.nyx.playback.contracts.PlaybackOutputPreferences;
import com.nyx.playback.contracts.PlaybackRequest;
import com.nyx.playback.contracts.StreamDescriptor;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import com.nyx.playback.contracts.TranscodePreferences;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import com.nyx.transcode.contracts.TranscodeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TranscodeServiceSubmissionTest extends AbstractTranscodeServiceTestSupport {
    @Test
    void submitThrowsProbeFailedWhenFfprobeScriptReturnsError() throws Exception {
        Path failingProbe = createScript(
            "fail-ffprobe.sh",
            """
            echo "error" >&2
            exit 1
            """
        );
        ServiceResources resources = createServiceResources("probefail2.db", "ffmpeg", failingProbe.toString(), 2, 10, null, 2_000L);
        Path testFile = Files.createFile(mediaDir.resolve("probe-fail2-test.mkv"));

        NyxException exception = assertThrows(
            NyxException.class,
            () -> resources.service().submit(new TranscodeRequest(testFile.toString()))
        );
        assertEquals(ErrorCode.PROBE_FAILED, exception.getErrorCode());
    }

    @Test
    void submitPopulatesJobEventsMap() throws Exception {
        Path fakeProbe = fakeProbeScript("probe-maps.sh");
        ServiceResources resources = createServiceResources("maps1.db", "ffmpeg", fakeProbe.toString(), 2, 10, null, 2_000L);
        Path testFile = Files.createFile(mediaDir.resolve("maps-test.mkv"));

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

        assertNotNull(resources.service().getJobEvents().get(job.getId()));
    }

    @Test
    void submitAcceptsKnownProfiles() throws Exception {
        Path fakeProbe = fakeProbeScript("probe-known-profiles.sh");
        ServiceResources resources = createServiceResources("known-profiles.db", "ffmpeg", fakeProbe.toString(), 2, 10, null, 2_000L);

        for (String profile : List.of("h264_fast", "h264_balanced", "h265_quality", "av1_balanced")) {
            Path testFile = Files.createFile(mediaDir.resolve(profile + ".mkv"));
            TranscodeJob job = resources.service().submit(
                new TranscodeRequest(
                    testFile.toString(),
                    null,
                    profile,
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
            assertEquals(profile, job.getProfile());
        }
    }

    @Test
    void submitPlaybackRemuxRequestSuppressesAdaptiveRepresentationsInManifests() throws Exception {
        Path fakeProbe = fakeProbeScript("probe-remux-manifest.sh");
        ServiceResources resources = createServiceResources("remux-manifest.db", "ffmpeg", fakeProbe.toString(), 2, 10, null, 2_000L);
        Path testFile = Files.createFile(mediaDir.resolve("remux-manifest.mkv"));

        TranscodeJob job = resources.service().submit(
            new PlaybackRequest(
                new MediaSourceRef(testFile.toString()),
                0L,
                new PlaybackOutputPreferences(
                    Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH),
                    null,
                    true,
                    StreamRepresentation.HLS_DASH_FMP4
                ),
                null,
                null,
                null,
                null,
                new TranscodePreferences("adaptive_h264", null, List.of())
            ),
            new PlaybackDecision(
                PlaybackMode.REMUX,
                new StreamDescriptor(StreamRepresentation.HLS_DASH_FMP4),
                Set.of(),
                true,
                true,
                null,
                null
            )
        );

        assertTrue(job.getRepresentations().isEmpty());
        String master = resources.service().getManifestM3u8(job.getId());
        assertNotNull(master);
        assertTrue(master.contains("video.m3u8"));
        assertFalse(master.contains("854x480.m3u8"));
        String mpd = resources.service().getManifestMpd(job.getId());
        assertNotNull(mpd);
        assertTrue(mpd.contains("Representation id=\"video\""));
        assertFalse(mpd.contains("Representation id=\"854x480\""));
    }

    @Test
    void submitPlaybackAudioTranscodeRequestSuppressesAdaptiveRepresentationsInManifests() throws Exception {
        Path fakeProbe = fakeProbeScript("probe-audio-manifest.sh");
        ServiceResources resources = createServiceResources("audio-manifest.db", "ffmpeg", fakeProbe.toString(), 2, 10, null, 2_000L);
        Path testFile = Files.createFile(mediaDir.resolve("audio-manifest.mkv"));

        TranscodeJob job = resources.service().submit(
            new PlaybackRequest(
                new MediaSourceRef(testFile.toString()),
                0L,
                new PlaybackOutputPreferences(
                    Set.of(StreamingProtocol.HLS, StreamingProtocol.DASH),
                    null,
                    true,
                    StreamRepresentation.HLS_DASH_FMP4
                ),
                null,
                null,
                null,
                null,
                new TranscodePreferences("adaptive_h264", null, List.of())
            ),
            new PlaybackDecision(
                PlaybackMode.AUDIO_TRANSCODE,
                new StreamDescriptor(StreamRepresentation.HLS_DASH_FMP4),
                Set.of(),
                true,
                false,
                null,
                null
            )
        );

        assertTrue(job.getRepresentations().isEmpty());
        String master = resources.service().getManifestM3u8(job.getId());
        assertNotNull(master);
        assertTrue(master.contains("video.m3u8"));
        assertFalse(master.contains("1280x720.m3u8"));
        String mpd = resources.service().getManifestMpd(job.getId());
        assertNotNull(mpd);
        assertTrue(mpd.contains("Representation id=\"video\""));
        assertFalse(mpd.contains("Representation id=\"1920x1080\""));
    }

    @Test
    void submitDoesNotReuseActivePlaybackJobWhenResolvedExecutionModeDiffers() throws Exception {
        Path fakeProbe = fakeProbeScript("probe-dedupe-mode.sh");
        ServiceResources resources = createServiceResources("dedupe-mode.db", "ffmpeg", fakeProbe.toString(), 2, 10, null, 2_000L);
        Path testFile = Files.createFile(mediaDir.resolve("dedupe-mode.mkv"));
        PlaybackRequest request = new PlaybackRequest(
            new MediaSourceRef(testFile.toString()),
            0L,
            new PlaybackOutputPreferences(Set.of(StreamingProtocol.HLS), StreamingProtocol.HLS),
            null,
            null,
            null,
            null,
            new TranscodePreferences("adaptive_h264", null, List.of())
        );

        TranscodeJob remuxJob = resources.service().submit(
            request,
            new PlaybackDecision(
                PlaybackMode.REMUX,
                new StreamDescriptor(StreamingProtocol.HLS, "fmp4", true),
                Set.of(),
                true,
                true,
                null,
                null
            )
        );
        TranscodeJob videoJob = resources.service().submit(
            request,
            new PlaybackDecision(
                PlaybackMode.VIDEO_TRANSCODE,
                new StreamDescriptor(StreamingProtocol.HLS, "fmp4", true),
                Set.of(),
                false,
                false,
                null,
                null
            )
        );

        assertNotEquals(remuxJob.getId(), videoJob.getId());
        assertEquals(TranscodeExecutionMode.REMUX, remuxJob.getExecutionMode());
        assertEquals(TranscodeExecutionMode.VIDEO_TRANSCODE, videoJob.getExecutionMode());
    }

    @Test
    void cancelEmitsCancelledErrorEventAndTransitionsStatus() throws Exception {
        ServiceResources resources = createServiceResources("cancel1.db", "ffmpeg", "ffprobe", 2, 10, null, 2_000L);
        String jobId = "cancel-deep2";
        resources.jobRepository().create(job(jobId, JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));
        resources.jobRepository().updateStatus(jobId, JobStatus.PROBING);
        resources.jobRepository().updateStatus(jobId, JobStatus.TRANSCODING);

        ReplayPublisher<JobEvent> eventFlow = new ReplayPublisher<>(resources.service().getAsyncExecutor(), 64);
        resources.service().getJobEvents().put(jobId, eventFlow);

        resources.service().cancel(jobId);

        JobEvent.Error event = assertInstanceOf(JobEvent.Error.class, eventFlow.first());
        assertEquals("CANCELLED", event.getCode());
        assertEquals("Job cancelled by user", event.getMessage());

        TranscodeJob updated = resources.jobRepository().getById(jobId);
        assertNotNull(updated);
        assertEquals(JobStatus.CANCELLED, updated.getStatus());
    }

    @Test
    void deferredCleanupRemovesMapsAfterGracePeriod() throws Exception {
        TestScheduledExecutorService scheduler = new TestScheduledExecutorService();
        ServiceResources resources = createServiceResources("cleanup1.db", "ffmpeg", "ffprobe", 2, 0, scheduler, 2_000L);
        String jobId = "cleanup-test2";
        resources.service().getJobEvents().put(jobId, new ReplayPublisher<>(resources.service().getAsyncExecutor(), 1));
        resources.jobRepository().create(job(jobId, JobStatus.QUEUED, "/test.mkv", "h264_fast", "dash"));

        resources.service().cancel(jobId);
        scheduler.runScheduledTask();

        assertNull(resources.service().getJobEvents().get(jobId));
    }

    @Test
    void processJobEndsFailedWhenFfmpegFailsTwice() throws Exception {
        Path fakeProbe = fakeProbeScript("probe-dblfail.sh");
        Path fakeFfmpeg = createScript(
            "fake-ffmpeg-fail.sh",
            """
            echo "Conversion failed" >&2
            exit 1
            """
        );
        ServiceResources resources = createServiceResources("dblfail.db", fakeFfmpeg.toString(), fakeProbe.toString(), 1, 10, null, 10L);
        Path testFile = Files.createFile(mediaDir.resolve("dblfail.mkv"));

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
        assertEquals(JobStatus.FAILED, updated.getStatus());
    }

    @Test
    void getManifestM3u8ReturnsMasterPlaylistWithRepresentations() throws Exception {
        ServiceResources resources = createServiceResources("m3u81.db", "ffmpeg", "ffprobe", 2, 10, null, 2_000L);
        List<TranscodeRepresentation> representations = List.of(
            new TranscodeRepresentation(854, 480, 1500),
            new TranscodeRepresentation(1280, 720, 3000)
        );
        resources.jobRepository().create(
            new TranscodeJob(
                "hls-reps",
                JobStatus.QUEUED,
                "/test.mkv",
                "adaptive_h264",
                StreamRepresentation.HLS_FMP4,
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
            )
        );

        String m3u8 = resources.service().getManifestM3u8("hls-reps");
        assertNotNull(m3u8);
        assertTrue(m3u8.contains("#EXTM3U"));
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
