package com.nyx.ffmpeg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class SubtitleExtractor {
    private final String ffmpegPath;

    public SubtitleExtractor() {
        this("ffmpeg");
    }

    public SubtitleExtractor(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public Path extractWebVtt(Path sourcePath, int trackIndex, Path outputDir) {
        try {
            Path outputFile = outputDir.resolve("subtitle_" + trackIndex + ".vtt");
            List<String> args = List.of(
                ffmpegPath,
                "-i", sourcePath.toString(),
                "-map", "0:s:" + trackIndex,
                "-c:s", "webvtt",
                "-y",
                outputFile.toString()
            );

            Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();

            process.getInputStream().readAllBytes();
            int exitCode = waitFor(process);
            if (exitCode != 0) {
                throw new IOException("Failed to extract subtitle track " + trackIndex + ", exit code: " + exitCode);
            }

            return outputFile;
        } catch (Throwable throwable) {
            return UncheckedThrow.sneakyThrow(throwable);
        }
    }

    public static String buildBurnInFilter(Path inputPath, int trackIndex, boolean isBitmap) {
        if (isBitmap) {
            return "[0:v][0:s:" + trackIndex + "]overlay";
        }
        return "subtitles=" + inputPath + ":si=" + trackIndex;
    }

    private static int waitFor(Process process) {
        try {
            return process.waitFor();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return UncheckedThrow.sneakyThrow(exception);
        }
    }
}
