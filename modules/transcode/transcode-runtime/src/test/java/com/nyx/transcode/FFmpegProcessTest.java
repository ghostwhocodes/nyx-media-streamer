package com.nyx.transcode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.ffmpeg.FFmpegCommand;
import com.nyx.ffmpeg.model.AudioCodec;
import com.nyx.ffmpeg.model.AudioTrackMode;
import com.nyx.ffmpeg.model.H264Preset;
import com.nyx.ffmpeg.model.H264Profile;
import com.nyx.ffmpeg.model.HwAccel;
import com.nyx.ffmpeg.model.OutputFormat;
import com.nyx.ffmpeg.model.SegmentDuration;
import com.nyx.ffmpeg.model.SubtitleMode;
import com.nyx.ffmpeg.model.VideoCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FFmpegProcessTest {
    private Path tempDir;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("nyx-ffmpeg-proc-test");
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        deleteRecursively(tempDir);
    }

    @Test
    void initialStateIsIdle() {
        FFmpegCommand command = new FFmpegCommand(
            Path.of("/test/input.mkv"),
            Path.of("/test/output.mpd"),
            new VideoCodec.H264(H264Preset.VERYFAST, 23, H264Profile.HIGH),
            new AudioCodec.AAC(128_000),
            OutputFormat.Dash
        );
        FFmpegProcess process = new FFmpegProcess(command, "ffmpeg", executor);

        assertEquals(ProcessState.IDLE, process.getState().getValue());
    }

    @Test
    void stderrCaptureReturnsEmptyStringBeforeStart() {
        FFmpegCommand command = new FFmpegCommand(
            Path.of("/test/input.mkv"),
            Path.of("/test/output.mpd"),
            new VideoCodec.H264(H264Preset.VERYFAST, 23, H264Profile.HIGH),
            new AudioCodec.AAC(128_000),
            OutputFormat.Dash
        );
        FFmpegProcess process = new FFmpegProcess(command, "ffmpeg", executor);

        assertEquals("", process.stderrCapture());
    }

    @Test
    void startTransitionsToRunningAndThenCompletedOnSuccess() throws Exception {
        Path script = createScript("success.sh", """
            echo "frame=100" >&1
            echo "progress=continue" >&1
            echo "stderr output" >&2
            exit 0
            """);

        FFmpegProcess process = new FFmpegProcess(testCommand(), script.toString(), executor);
        assertEquals(ProcessState.IDLE, process.getState().getValue());

        process.start();
        assertEquals(ProcessState.RUNNING, process.getState().getValue());

        process.getState().first(state -> state != ProcessState.IDLE && state != ProcessState.RUNNING);
        assertEquals(ProcessState.COMPLETED, process.getState().getValue());
    }

    @Test
    void startTransitionsToFailedOnNonZeroExit() throws Exception {
        Path script = createScript("fail.sh", """
            echo "error info" >&2
            exit 1
            """);

        FFmpegProcess process = new FFmpegProcess(testCommand(), script.toString(), executor);
        process.start();

        process.getState().first(state -> state != ProcessState.IDLE && state != ProcessState.RUNNING);
        assertEquals(ProcessState.FAILED, process.getState().getValue());
    }

    @Test
    void startFailsIfProcessIsNotInIdleState() throws Exception {
        Path script = createScript("double-start.sh", "sleep 5");

        FFmpegProcess process = new FFmpegProcess(testCommand(), script.toString(), executor);
        process.start();

        assertThrows(IllegalStateException.class, process::start);
        process.cancel();
    }

    @Test
    void stderrCaptureReturnsCapturedStderrOutput() throws Exception {
        Path script = createScript("stderr.sh", """
            echo "error line 1" >&2
            echo "error line 2" >&2
            exit 0
            """);

        FFmpegProcess process = new FFmpegProcess(testCommand(), script.toString(), executor);
        process.start();

        process.getState().first(state -> state != ProcessState.IDLE && state != ProcessState.RUNNING);

        String stderr = process.stderrCapture();
        assertTrue(stderr.contains("error line 1"));
        assertTrue(stderr.contains("error line 2"));
    }

    @Test
    void cancelTransitionsToCancelledForRunningProcess() throws Exception {
        Path script = createScript("long-running.sh", "sleep 30");

        FFmpegProcess process = new FFmpegProcess(testCommand(), script.toString(), executor);
        process.start();

        Thread.sleep(100L);
        assertEquals(ProcessState.RUNNING, process.getState().getValue());

        process.cancel();
        assertEquals(ProcessState.CANCELLED, process.getState().getValue());
    }

    @Test
    void cancelIsNoOpWhenProcessHasNotBeenStarted() {
        FFmpegProcess process = new FFmpegProcess(testCommand(), "ffmpeg", executor);
        assertDoesNotThrow(process::cancel);
        assertEquals(ProcessState.IDLE, process.getState().getValue());
    }

    @Test
    void cancelIsNoOpWhenProcessAlreadyExited() throws Exception {
        Path script = createScript("quick-exit.sh", "exit 0");

        FFmpegProcess process = new FFmpegProcess(testCommand(), script.toString(), executor);
        process.start();

        process.getState().first(state -> state != ProcessState.IDLE && state != ProcessState.RUNNING);
        assertDoesNotThrow(process::cancel);
    }

    @Test
    void cancelForceKillsProcessThatIgnoresSigterm() throws Exception {
        Path script = createScript("ignores-term.sh", """
            trap '' TERM
            sleep 30
            """);

        FFmpegProcess process = new FFmpegProcess(testCommand(), script.toString(), executor, 200L);
        process.start();

        Thread.sleep(200L);
        assertEquals(ProcessState.RUNNING, process.getState().getValue());

        process.cancel();
        assertEquals(ProcessState.CANCELLED, process.getState().getValue());
    }

    @Test
    void progressFlowThrowsIfProcessNotStarted() {
        FFmpegProcess process = new FFmpegProcess(testCommand(), "ffmpeg", executor);
        assertThrows(IllegalStateException.class, process::progressFlow);
    }

    @Test
    void progressFlowReadsProgressFromStdout() throws Exception {
        Path script = createScript("progress.sh", """
            echo "frame=50"
            echo "fps=30.0"
            echo "out_time_us=30000000"
            echo "speed=2.0x"
            echo "bitrate=1500kbits/s"
            echo "progress=continue"
            echo "progress=end"
            exit 0
            """);

        FFmpegProcess process = new FFmpegProcess(testCommand(), script.toString(), executor);
        process.start();

        var progressList = process.progressFlow().toList();
        assertFalse(progressList.isEmpty());

        var first = progressList.get(0);
        assertEquals(50L, first.frame());
        assertEquals(30.0, first.fps());
        assertEquals(30_000_000L, first.outTimeUs());

        var last = progressList.get(progressList.size() - 1);
        assertEquals(100.0, last.progressPercent());
    }

    private Path createScript(String name, String body) throws IOException {
        Path script = tempDir.resolve(name);
        Files.writeString(script, "#!/bin/bash\n" + body.stripIndent() + "\n");
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

    private FFmpegCommand testCommand() {
        return testCommand(Path.of("/test/input.mkv"));
    }

    private FFmpegCommand testCommand(Path inputPath) {
        return new FFmpegCommand(
            inputPath,
            tempDir.resolve("output.mpd"),
            new VideoCodec.H264(H264Preset.VERYFAST, 23, H264Profile.HIGH),
            new AudioCodec.AAC(128_000),
            OutputFormat.Dash,
            SubtitleMode.Extract,
            AudioTrackMode.All,
            HwAccel.None,
            null,
            java.util.List.of(),
            SegmentDuration.ADAPTIVE,
            60_000_000L,
            24.0
        );
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete " + path, exception);
                }
            });
        }
    }
}
