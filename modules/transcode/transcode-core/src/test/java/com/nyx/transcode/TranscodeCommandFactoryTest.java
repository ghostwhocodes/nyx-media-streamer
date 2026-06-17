package com.nyx.transcode;

import com.nyx.ffmpeg.model.AudioCodec;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.TranscodeProfiles;
import com.nyx.ffmpeg.model.VideoCodec;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.transcode.contracts.JobStatus;
import com.nyx.transcode.contracts.TranscodeExecutionMode;
import com.nyx.transcode.contracts.TranscodeJob;
import com.nyx.transcode.contracts.TranscodeRequest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TranscodeCommandFactoryTest {
    private final TranscodeCommandFactory factory = new TranscodeCommandFactory();
    private final ProbeResult probeResult = new ProbeResult(
        "/media/test.mkv",
        "matroska",
        120.0,
        1_000_000L,
        new ProbeStreams(
            List.of(new VideoStream(0, "h264", 1920, 1080, 24.0, 4_000)),
            List.of(new AudioStream(1, "aac", 2, 192)),
            List.of()
        )
    );
    private final Path outputDir = Path.of("/tmp/nyx-remux-command-test");

    @Test
    void buildRemuxCommandPreservesSourceStreams() {
        TranscodeJob job = new TranscodeJob(
            "job-remux",
            JobStatus.QUEUED,
            "/media/test.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4
        );

        var command = factory.buildRemuxCommand(
            job,
            probeResult,
            outputDir,
            new TranscodeRequest(
                job.inputPath(),
                12.5,
                "h264_fast",
                StreamRepresentation.DASH_FMP4,
                null,
                "extract",
                null,
                "all",
                "auto",
                TranscodeExecutionMode.VIDEO_TRANSCODE,
                null
            )
        );

        assertInstanceOf(VideoCodec.Copy.class, command.videoCodec());
        assertInstanceOf(AudioCodec.Copy.class, command.audioCodec());
        assertInstanceOf(OutputFormat.Dash.class, command.outputFormat());
        assertEquals(12.5, command.seekTimeSecs());
    }

    @Test
    void buildAudioTranscodeCommandPreservesSourceVideoWhileConvertingAudio() {
        TranscodeJob job = new TranscodeJob(
            "job-audio-only",
            JobStatus.QUEUED,
            "/media/test.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4
        );

        var command = factory.buildAudioTranscodeCommand(
            job,
            TranscodeProfiles.H264_FAST,
            probeResult,
            outputDir,
            new TranscodeRequest(
                job.inputPath(),
                7.25,
                "h264_fast",
                StreamRepresentation.DASH_FMP4,
                null,
                "extract",
                null,
                "all",
                "auto",
                TranscodeExecutionMode.VIDEO_TRANSCODE,
                null
            )
        );

        assertInstanceOf(VideoCodec.Copy.class, command.videoCodec());
        assertInstanceOf(AudioCodec.AAC.class, command.audioCodec());
        assertEquals(List.of(), command.representations());
        assertInstanceOf(OutputFormat.Dash.class, command.outputFormat());
        assertEquals(7.25, command.seekTimeSecs());
    }

    @Test
    void buildPrimaryCommandStillUsesProfileCodecs() {
        TranscodeJob job = new TranscodeJob(
            "job-transcode",
            JobStatus.QUEUED,
            "/media/test.mkv",
            "h264_fast",
            StreamRepresentation.DASH_FMP4
        );

        var command = factory.buildPrimaryCommand(job, TranscodeProfiles.H264_FAST, probeResult, outputDir);

        assertInstanceOf(VideoCodec.H264.class, command.videoCodec());
        assertInstanceOf(AudioCodec.AAC.class, command.audioCodec());
    }

    @Test
    void hlsMpegTsRepresentationMapsToMpegTsOutputFormat() {
        TranscodeJob job = new TranscodeJob(
            "job-hls-mpeg-ts",
            JobStatus.QUEUED,
            "/media/test.mkv",
            "h264_fast",
            StreamRepresentation.HLS_MPEG_TS
        );

        var primaryCommand = factory.buildPrimaryCommand(job, TranscodeProfiles.H264_FAST, probeResult, outputDir);
        var fallbackCommand = factory.buildFallbackCommand(job, probeResult, outputDir);

        assertInstanceOf(OutputFormat.HlsMpegTs.class, primaryCommand.outputFormat());
        assertInstanceOf(OutputFormat.HlsMpegTs.class, fallbackCommand.outputFormat());
    }

    @Test
    void representationCommandOutputsMapToFfmpegOutputFormats() {
        assertPrimaryOutputFormat(StreamRepresentation.HLS_FMP4, OutputFormat.Hls.class);
        assertPrimaryOutputFormat(StreamRepresentation.DASH_FMP4, OutputFormat.Dash.class);
        assertPrimaryOutputFormat(StreamRepresentation.HLS_DASH_FMP4, OutputFormat.Both.class);
        assertPrimaryOutputFormat(StreamRepresentation.CMAF, OutputFormat.Cmaf.class);
    }

    @Test
    void directFileRepresentationFailsFastForCommandGeneration() {
        TranscodeJob job = new TranscodeJob(
            "job-direct-file",
            JobStatus.QUEUED,
            "/media/test.mkv",
            "h264_fast",
            StreamRepresentation.DIRECT_FILE
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> factory.buildPrimaryCommand(job, TranscodeProfiles.H264_FAST, probeResult, outputDir)
        );
    }

    private void assertPrimaryOutputFormat(
        StreamRepresentation representation,
        Class<? extends OutputFormat> expectedType
    ) {
        TranscodeJob job = new TranscodeJob(
            "job-" + representation.name(),
            JobStatus.QUEUED,
            "/media/test.mkv",
            "h264_fast",
            representation
        );

        var command = factory.buildPrimaryCommand(job, TranscodeProfiles.H264_FAST, probeResult, outputDir);

        assertInstanceOf(expectedType, command.outputFormat(), representation.name());
    }
}
