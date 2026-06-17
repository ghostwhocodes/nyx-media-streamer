package com.nyx.ffmpeg;

import com.nyx.concurrent.BlockingStream;
import java.io.BufferedReader;

public final class FFmpegProgressParser {
    private FFmpegProgressParser() {
    }

    public static BlockingStream<TranscodeProgress> parseProgressStream(
        BufferedReader reader,
        long totalDurationUs
    ) {
        return new BlockingStream<>(emit -> {
            long currentFrame = 0L;
            double currentFps = 0.0;
            long currentTotalSize = 0L;
            long currentOutTimeUs = 0L;
            double currentSpeed = 0.0;
            double currentBitrate = 0.0;

            for (String line : reader.lines().toList()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                int eqIdx = trimmed.indexOf('=');
                if (eqIdx < 0) {
                    continue;
                }

                String key = trimmed.substring(0, eqIdx).trim();
                String value = trimmed.substring(eqIdx + 1).trim();

                switch (key) {
                    case "frame" -> currentFrame = parseLong(value, currentFrame);
                    case "fps" -> currentFps = parseDouble(value, currentFps);
                    case "total_size" -> currentTotalSize = parseLong(value, currentTotalSize);
                    case "out_time_us" -> currentOutTimeUs = parseLong(value, currentOutTimeUs);
                    case "speed" -> currentSpeed = parseDouble(removeSuffix(value, "x"), currentSpeed);
                    case "bitrate" -> currentBitrate = parseDouble(removeSuffix(value, "kbits/s").trim(), currentBitrate);
                    case "progress" -> {
                        double percent = totalDurationUs > 0
                            ? clamp((currentOutTimeUs / (double) totalDurationUs) * 100.0, 0.0, 100.0)
                            : 0.0;
                        boolean shouldContinue = emit.emit(
                            new TranscodeProgress(
                                currentFrame,
                                currentFps,
                                currentTotalSize,
                                currentOutTimeUs,
                                currentSpeed,
                                currentBitrate,
                                "end".equals(value) ? 100.0 : percent
                            )
                        );
                        if (!shouldContinue) {
                            return;
                        }
                    }
                    default -> {
                    }
                }
            }
        });
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String removeSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
