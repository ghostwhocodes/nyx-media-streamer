package com.nyx.transcode;

import static com.nyx.transcode.contracts.TranscodeContracts.canTransitionTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.ffmpeg.FFmpegProgressParser;
import com.nyx.ffmpeg.TranscodeProgress;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.json.NyxJson;
import com.nyx.transcode.contracts.BatchCancelRequest;
import com.nyx.transcode.contracts.BatchCancelResponse;
import com.nyx.transcode.contracts.JobEvent;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeJobListing;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import org.junit.jupiter.api.Test;

class TranscodeJobTest {
    private final ObjectMapper json = NyxJson.newMapper();

    @Test
    void queuedCanTransitionToProbing() {
        assertTrue(canTransitionTo(JobStatus.QUEUED, JobStatus.PROBING));
    }

    @Test
    void queuedCannotTransitionToTranscoding() {
        assertFalse(canTransitionTo(JobStatus.QUEUED, JobStatus.TRANSCODING));
    }

    @Test
    void probingCanTransitionToTranscodingOrFailed() {
        assertTrue(canTransitionTo(JobStatus.PROBING, JobStatus.TRANSCODING));
        assertTrue(canTransitionTo(JobStatus.PROBING, JobStatus.FAILED));
        assertFalse(canTransitionTo(JobStatus.PROBING, JobStatus.COMPLETED));
    }

    @Test
    void transcodingCanTransitionToCompletedFailedCancelledRetrying() {
        assertTrue(canTransitionTo(JobStatus.TRANSCODING, JobStatus.COMPLETED));
        assertTrue(canTransitionTo(JobStatus.TRANSCODING, JobStatus.FAILED));
        assertTrue(canTransitionTo(JobStatus.TRANSCODING, JobStatus.CANCELLED));
        assertTrue(canTransitionTo(JobStatus.TRANSCODING, JobStatus.RETRYING));
    }

    @Test
    void retryingCanTransitionToTranscodingFailedCancelled() {
        assertTrue(canTransitionTo(JobStatus.RETRYING, JobStatus.TRANSCODING));
        assertTrue(canTransitionTo(JobStatus.RETRYING, JobStatus.FAILED));
        assertTrue(canTransitionTo(JobStatus.RETRYING, JobStatus.CANCELLED));
        assertFalse(canTransitionTo(JobStatus.RETRYING, JobStatus.COMPLETED));
    }

    @Test
    void terminalStatesCannotTransition() {
        for (JobStatus status : List.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED)) {
            for (JobStatus next : JobStatus.values()) {
                assertFalse(canTransitionTo(status, next), status + " should not transition to " + next);
            }
        }
    }

    @Test
    void transcodeProgressProperties() {
        TranscodeProgress progress = new TranscodeProgress(100L, 24.0, 50_000L, 5_000_000L, 2.5, 1500.0, 50.0);
        assertEquals(100L, progress.frame());
        assertEquals(24.0, progress.fps());
        assertEquals(50_000L, progress.totalSize());
        assertEquals(5_000_000L, progress.outTimeUs());
        assertEquals(2.5, progress.speed());
        assertEquals(1500.0, progress.bitrate());
        assertEquals(50.0, progress.progressPercent());

        TranscodeProgress copy = new TranscodeProgress(
            200L,
            progress.fps(),
            progress.totalSize(),
            progress.outTimeUs(),
            progress.speed(),
            progress.bitrate(),
            progress.progressPercent()
        );
        assertEquals(200L, copy.frame());
        assertNotEquals(progress, copy);
    }

    @Test
    void transcodeProgressDefaultValues() {
        TranscodeProgress progress = new TranscodeProgress();
        assertEquals(0L, progress.frame());
        assertEquals(0.0, progress.fps());
        assertEquals(0L, progress.totalSize());
        assertEquals(0L, progress.outTimeUs());
        assertEquals(0.0, progress.speed());
        assertEquals(0.0, progress.bitrate());
        assertEquals(0.0, progress.progressPercent());
    }

    @Test
    void parseProgressStreamHandlesBitrateWithKbitsSuffix() {
        List<TranscodeProgress> results = parseProgress("""
            frame=100
            fps=24.0
            total_size=50000
            out_time_us=5000000
            speed=2.5x
            bitrate=1500.0kbits/s
            progress=continue
            """, 10_000_000L);

        assertEquals(1, results.size());
        assertEquals(1500.0, results.get(0).bitrate());
        assertEquals(50.0, results.get(0).progressPercent(), 0.1);
    }

    @Test
    void parseProgressStreamHandlesProgressEnd() {
        List<TranscodeProgress> results = parseProgress("""
            frame=200
            fps=24.0
            out_time_us=10000000
            speed=1.0x
            progress=end
            """, 10_000_000L);

        assertEquals(1, results.size());
        assertEquals(100.0, results.get(0).progressPercent());
    }

    @Test
    void parseProgressStreamHandlesZeroTotalDuration() {
        List<TranscodeProgress> results = parseProgress("""
            frame=50
            out_time_us=5000000
            progress=continue
            """, 0L);

        assertEquals(1, results.size());
        assertEquals(0.0, results.get(0).progressPercent());
    }

    @Test
    void parseProgressStreamHandlesInvalidValuesGracefully() {
        List<TranscodeProgress> results = parseProgress("""
            frame=notanumber
            fps=bad
            total_size=invalid
            out_time_us=xxx
            speed=abc
            bitrate=nope
            progress=continue
            """, 10_000_000L);

        assertEquals(1, results.size());
        assertEquals(0L, results.get(0).frame());
        assertEquals(0.0, results.get(0).fps());
    }

    @Test
    void parseProgressStreamSkipsEmptyLinesAndLinesWithoutEquals() {
        List<TranscodeProgress> results = parseProgress("""

            no-equals-here
            frame=100
            progress=continue
            """, 10_000_000L);

        assertEquals(1, results.size());
        assertEquals(100L, results.get(0).frame());
    }

    @Test
    void jobEventPropertiesRemainStable() {
        JobEvent.Progress progress = new JobEvent.Progress("job1", 50.0, 2.5, 24.0);
        assertEquals("job1", progress.getJobId());
        assertEquals(50.0, progress.getPercent());
        assertEquals(2.5, progress.getSpeed());
        assertEquals(24.0, progress.getFps());

        JobEvent.Segment segment = new JobEvent.Segment("job1", "seg001.m4s", "video", 4.0);
        assertEquals("job1", segment.getJobId());
        assertEquals("seg001.m4s", segment.getName());
        assertEquals("video", segment.getRepresentationId());
        assertEquals(4.0, segment.getDurationSecs());

        JobEvent.Complete complete = new JobEvent.Complete("job1", 120.0, 30);
        assertEquals("job1", complete.getJobId());
        assertEquals(120.0, complete.getDurationSecs());
        assertEquals(30, complete.getSegmentsTotal());

        JobEvent.Retry retry = new JobEvent.Retry("job1", 2, "Fallback");
        assertEquals("job1", retry.getJobId());
        assertEquals(2, retry.getAttempt());
        assertEquals("Fallback", retry.getReason());

        JobEvent.Error error = new JobEvent.Error("job1", "TRANSCODE_FAILED", "FFmpeg crashed");
        assertEquals("job1", error.getJobId());
        assertEquals("TRANSCODE_FAILED", error.getCode());
        assertEquals("FFmpeg crashed", error.getMessage());
    }

    @Test
    void segmentAndAudioTrackInfoDataClassesRemainStable() {
        SegmentInfo segmentInfo = new SegmentInfo("seg001.m4s", "video", 4.0, 0);
        assertEquals("seg001.m4s", segmentInfo.getName());
        assertEquals("video", segmentInfo.getRepresentationId());
        assertEquals(4.0, segmentInfo.getDurationSecs());
        assertEquals(0, segmentInfo.getIndex());

        AudioTrackInfo audioTrackInfo = new AudioTrackInfo(0, "eng", "Stereo", 2, "aac", false, 128);
        assertEquals(0, audioTrackInfo.getTrackIndex());
        assertEquals("aac", audioTrackInfo.getCodec());
        assertEquals(2, audioTrackInfo.getChannels());
        assertEquals("eng", audioTrackInfo.getLanguage());
        assertEquals("Stereo", audioTrackInfo.getTitle());
        assertEquals(128, audioTrackInfo.getBitrateKbps());
        assertFalse(audioTrackInfo.isHasDownmix());
    }

    @Test
    void transcodeJobListingAndBatchContractsRemainStable() {
        TranscodeJobListing listing = new TranscodeJobListing(List.of(), 0, 1, 50);
        assertEquals(0, listing.total());
        assertEquals(1, listing.page());
        assertEquals(50, listing.limit());

        BatchCancelRequest request = new BatchCancelRequest(List.of("j1", "j2"));
        assertEquals(2, request.jobIds().size());

        BatchCancelResponse response = new BatchCancelResponse(List.of("j1"), List.of("j2"));
        assertEquals(1, response.cancelled().size());
        assertEquals(1, response.notFound().size());

        TranscodeJobListing filtered = new TranscodeJobListing(List.of(), 0, 1, 50, JobStatus.QUEUED, 60);
        assertEquals(JobStatus.QUEUED, filtered.statusFilter());
        assertEquals(60, filtered.sinceMinutes());
    }

    @Test
    void eventAndProgressModelsSerializeWithJackson() throws Exception {
        JobEvent.Progress progressEvent = new JobEvent.Progress("job-1", 42.5, 1.25, 29.97);
        JobEvent.Segment segmentEvent = new JobEvent.Segment("job-1", "seg_001.m4s", "video", 6.0);
        JobEvent.Complete completeEvent = new JobEvent.Complete("job-1", 3600.0, 600);
        JobEvent.Retry retryEvent = new JobEvent.Retry("job-1", 2, "fallback profile");
        JobEvent.Error errorEvent = new JobEvent.Error("job-1", "TRANSCODE_FAILED", "ffmpeg exited 1");
        TranscodeProgress progress = new TranscodeProgress(12L, 24.0, 3456L, 7890L, 1.0, 128.0, 33.3);

        assertEquals(progressEvent, json.readValue(json.writeValueAsString(progressEvent), JobEvent.Progress.class));
        assertEquals(segmentEvent, json.readValue(json.writeValueAsString(segmentEvent), JobEvent.Segment.class));
        assertEquals(completeEvent, json.readValue(json.writeValueAsString(completeEvent), JobEvent.Complete.class));
        assertEquals(retryEvent, json.readValue(json.writeValueAsString(retryEvent), JobEvent.Retry.class));
        assertEquals(errorEvent, json.readValue(json.writeValueAsString(errorEvent), JobEvent.Error.class));
        assertEquals(progress, json.readValue(json.writeValueAsString(progress), TranscodeProgress.class));
    }

    @Test
    void ffmpegStreamModelsUseDefaultOptionalParameters() {
        VideoStream videoStream = new VideoStream(0, "h264", 1920, 1080, 30.0);
        assertNull(videoStream.bitrateKbps());

        AudioStream audioStream = new AudioStream(0, "aac", 2);
        assertNull(audioStream.language());
        assertNull(audioStream.title());

        SubtitleStream subtitleStream = new SubtitleStream(0, "srt");
        assertNull(subtitleStream.language());
        assertNull(subtitleStream.title());
    }

    private static List<TranscodeProgress> parseProgress(String input, long totalDurationUs) {
        BufferedReader reader = new BufferedReader(new StringReader(input.stripIndent()));
        return FFmpegProgressParser.parseProgressStream(reader, totalDurationUs).toList();
    }
}
