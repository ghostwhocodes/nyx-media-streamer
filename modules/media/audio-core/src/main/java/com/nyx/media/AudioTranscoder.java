package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.HealthMonitor;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.config.AudioConfig;
import com.nyx.playback.contracts.AudioFormatDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class AudioTranscoder {
    public static final int BUFFER_SIZE = 8192;

    private static final Logger LOG = LoggerFactory.getLogger(AudioTranscoder.class);

    private final String ffmpegPath;
    private final Semaphore ffmpegSemaphore;
    private final HealthMonitor healthService;
    private final AudioConfig audioConfig;
    private final List<TranscodeTarget> supportedTargets;

    public AudioTranscoder() {
        this("ffmpeg", null, null, new AudioConfig());
    }

    public AudioTranscoder(String ffmpegPath, Semaphore ffmpegSemaphore, HealthMonitor healthService, AudioConfig audioConfig) {
        this.ffmpegPath = ffmpegPath == null ? "ffmpeg" : ffmpegPath;
        this.ffmpegSemaphore = ffmpegSemaphore;
        this.healthService = healthService;
        this.audioConfig = audioConfig == null ? new AudioConfig() : audioConfig;
        this.supportedTargets = List.of(
            new TranscodeTarget(MediaTypes.AUDIO_AAC, "adts", "aac", this.audioConfig.getAacBitrate(), "aac"),
            new TranscodeTarget("audio/opus", "opus", "libopus", this.audioConfig.getOpusBitrate(), "opus"),
            new TranscodeTarget(MediaTypes.AUDIO_MP3, "mp3", "libmp3lame", this.audioConfig.getMp3Bitrate(), "mp3")
        );
    }

    public List<TranscodeTarget> availableTargets() {
        return List.copyOf(supportedTargets);
    }

    public TranscodeTarget resolveTranscodeTarget(AudioFormatDescriptor output) {
        if (output == null) {
            return null;
        }
        TranscodeTarget baseTarget = supportedTargets.stream()
            .filter(target ->
                matches(target.mimeType(), output.mimeType()) &&
                    matches(target.format(), output.container()) &&
                    matches(target.outputCodec(), output.codec()))
            .findFirst()
            .orElse(null);
        if (baseTarget == null) {
            return null;
        }
        return new TranscodeTarget(
            baseTarget.mimeType(),
            baseTarget.format(),
            baseTarget.codec(),
            output.bitrateKbps() == null ? baseTarget.bitrate() : output.bitrateKbps() + "k",
            baseTarget.outputCodec(),
            output.channels(),
            output.sampleRateHz()
        );
    }

    public TranscodeTarget negotiateFormat(String acceptHeader, String sourceMimeType) {
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return null;
        }
        List<String> acceptedTypes = parseAcceptHeader(acceptHeader);
        if (acceptedTypes.stream().anyMatch(type -> "*/*".equals(type) || type.equals(sourceMimeType))) {
            return null;
        }
        return supportedTargets.stream()
            .filter(target -> acceptedTypes.stream().anyMatch(type -> type.equals(target.mimeType())))
            .findFirst()
            .orElse(null);
    }

    public void transcode(Path sourcePath, TranscodeTarget target, OutputStream output) {
        transcode(sourcePath, target, output, 0L);
    }

    public void transcode(Path sourcePath, TranscodeTarget target, OutputStream output, long startPositionMillis) {
        if (healthService != null && !healthService.isFfmpegAvailable()) {
            sneakyThrow(new NyxException(ErrorCode.FFMPEG_UNAVAILABLE, "FFmpeg is not available", Map.of(), null));
        }
        List<String> command = buildTranscodeCommand(sourcePath, target, startPositionMillis);
        LOG.debug("Audio transcode: {}", String.join(" ", command));

        Runnable action = () -> doTranscode(command, output);
        if (ffmpegSemaphore == null) {
            action.run();
            return;
        }
        ffmpegSemaphore.acquireUninterruptibly();
        try {
            action.run();
        } finally {
            ffmpegSemaphore.release();
        }
    }

    public List<String> buildTranscodeCommand(Path sourcePath, TranscodeTarget target) {
        return buildTranscodeCommand(sourcePath, target, 0L);
    }

    public List<String> buildTranscodeCommand(Path sourcePath, TranscodeTarget target, long startPositionMillis) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        if (startPositionMillis > 0) {
            command.add("-ss");
            command.add(formatSeekTime(startPositionMillis));
        }
        command.add("-i");
        command.add(sourcePath.toString());
        command.add("-c:a");
        command.add(target.codec());
        if (target.channels() != null) {
            command.add("-ac");
            command.add(target.channels().toString());
        }
        if (target.sampleRateHz() != null) {
            command.add("-ar");
            command.add(target.sampleRateHz().toString());
        }
        command.add("-b:a");
        command.add(target.bitrate());
        command.add("-f");
        command.add(target.format());
        command.add("-y");
        command.add("pipe:1");
        return List.copyOf(command);
    }

    private void doTranscode(List<String> command, OutputStream output) {
        Process process;
        try {
            process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();
        } catch (IOException exception) {
            throw new RuntimeException("Failed to start FFmpeg audio transcode", exception);
        }

        try {
            StringBuilder stderrBuilder = new StringBuilder();
            Thread stderrThread = new Thread(() -> {
                try (var reader = new java.io.BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrBuilder.append(line).append(System.lineSeparator());
                    }
                } catch (IOException exception) {
                    LOG.debug("Failed to drain audio transcode stderr: {}", exception.getMessage());
                }
            }, "nyx-audio-transcode-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            try (var inputStream = process.getInputStream()) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }
                output.flush();
            }

            boolean exited = process.waitFor(audioConfig.getProcessTimeoutSeconds(), TimeUnit.SECONDS);
            stderrThread.join();
            if (!exited) {
                LOG.warn("Audio transcode timed out after {}s, destroying process", audioConfig.getProcessTimeoutSeconds());
                process.destroyForcibly();
            } else {
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    LOG.warn("Audio transcode exited with code {}: {}", exitCode, stderrBuilder);
                }
            }
        } catch (IOException exception) {
            throw new RuntimeException("Failed while streaming transcoded audio", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for audio transcode", exception);
        } finally {
            process.destroyForcibly();
        }
    }

    private List<String> parseAcceptHeader(String header) {
        return header.lines()
            .flatMap(line -> List.of(line.split(",")).stream())
            .map(part -> part.trim().split(";", 2)[0].trim())
            .filter(part -> !part.isEmpty())
            .toList();
    }

    private String formatSeekTime(long startPositionMillis) {
        return String.format(Locale.ROOT, "%.3f", startPositionMillis / 1000.0);
    }

    private boolean matches(String left, String right) {
        return right == null || left.equalsIgnoreCase(right);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    public record TranscodeTarget(
        String mimeType,
        String format,
        String codec,
        String bitrate,
        String outputCodec,
        Integer channels,
        Integer sampleRateHz
    ) {
        public TranscodeTarget(String mimeType, String format, String codec, String bitrate) {
            this(mimeType, format, codec, bitrate, codec, null, null);
        }

        public TranscodeTarget(String mimeType, String format, String codec, String bitrate, String outputCodec) {
            this(mimeType, format, codec, bitrate, outputCodec, null, null);
        }

        public TranscodeTarget {
            Objects.requireNonNull(mimeType, "mimeType");
            Objects.requireNonNull(format, "format");
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(bitrate, "bitrate");
            outputCodec = outputCodec == null ? codec : outputCodec;
        }
    }
}
