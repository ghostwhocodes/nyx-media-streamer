package com.nyx.ffmpeg;

import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.FfprobeOutput;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.ffmpeg.model.ProbeStreams;
import com.nyx.ffmpeg.model.SubtitleStream;
import com.nyx.ffmpeg.model.VideoStream;
import com.nyx.json.NyxJson;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class ProbeService implements MediaProber {
    public static final long PROBE_TIMEOUT_SECONDS = 30L;
    public static final int MAX_PROBE_CACHE = 1_000;

    private final String ffprobePath;
    private final ProbeMetricsCollector metricsService;
    private final ConcurrentHashMap<ProbeCacheKey, CompletableFuture<ProbeResult>> cache =
        new ConcurrentHashMap<>();
    private final ArrayDeque<ProbeCacheKey> evictionQueue = new ArrayDeque<>(MAX_PROBE_CACHE + 16);
    private final ReentrantLock evictionLock = new ReentrantLock();
    private final com.fasterxml.jackson.databind.ObjectMapper json = NyxJson.newMapper();

    public ProbeService() {
        this("ffprobe", null);
    }

    public ProbeService(String ffprobePath) {
        this(ffprobePath, null);
    }

    public ProbeService(String ffprobePath, ProbeMetricsCollector metricsService) {
        this.ffprobePath = ffprobePath;
        this.metricsService = metricsService;
    }

    public int getCacheSize() {
        return cache.size();
    }

    @Override
    public ProbeResult probe(Path path) {
        try {
            return runProbe(path);
        } catch (Throwable throwable) {
            return throwUnchecked(throwable);
        }
    }

    @Override
    public ProbeResult probeCached(Path path) {
        try {
            long mtime = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);
            ProbeCacheKey key = new ProbeCacheKey(path.toString(), mtime, size);

            CompletableFuture<ProbeResult> cachedFuture = cache.get(key);
            if (cachedFuture != null) {
                if (cachedFuture.isDone()) {
                    if (metricsService != null) {
                        metricsService.recordProbeCacheHit();
                    }
                    return cachedFuture.join();
                }
                return cachedFuture.join();
            }

            CompletableFuture<ProbeResult> newFuture = new CompletableFuture<>();
            CompletableFuture<ProbeResult> winner = cache.putIfAbsent(key, newFuture);
            if (winner != null) {
                return winner.join();
            }

            if (metricsService != null) {
                metricsService.recordProbeCacheMiss();
            }
            recordEvictionEntry(key);
            try {
                long probeStart = System.nanoTime();
                ProbeResult result = runProbe(path);
                if (metricsService != null) {
                    metricsService.recordProbeDuration(System.nanoTime() - probeStart);
                }
                newFuture.complete(result);
                return result;
            } catch (Throwable throwable) {
                cache.remove(key, newFuture);
                withEvictionLock(() -> evictionQueue.remove(key));
                newFuture.completeExceptionally(throwable);
                return throwUnchecked(throwable);
            }
        } catch (Throwable throwable) {
            return throwUnchecked(throwable);
        }
    }

    public ProbeResult parseProbeOutput(String jsonString, String filePath) {
        try {
            FfprobeOutput ffprobe = json.readValue(jsonString, FfprobeOutput.class);

            List<VideoStream> videoStreams = ffprobe.getStreams().stream()
                .filter(stream -> "video".equals(stream.getCodecType()))
                .map(stream -> new VideoStream(
                    stream.getIndex(),
                    stream.getCodecName() != null ? stream.getCodecName() : "unknown",
                    stream.getWidth() != null ? stream.getWidth() : 0,
                    stream.getHeight() != null ? stream.getHeight() : 0,
                    parseFps(firstNonBlank(stream.getRFrameRate(), stream.getAvgFrameRate(), "0/1")),
                    parseBitrateKbps(stream.getBitRate())
                ))
                .toList();

            List<AudioStream> audioStreams = ffprobe.getStreams().stream()
                .filter(stream -> "audio".equals(stream.getCodecType()))
                .map(stream -> new AudioStream(
                    stream.getIndex(),
                    stream.getCodecName() != null ? stream.getCodecName() : "unknown",
                    stream.getChannels() != null ? stream.getChannels() : 0,
                    parseBitrateKbps(stream.getBitRate()),
                    parseInteger(stream.getSampleRate()),
                    tagValue(stream.getTags(), "language"),
                    tagValue(stream.getTags(), "title")
                ))
                .toList();

            List<SubtitleStream> subtitleStreams = ffprobe.getStreams().stream()
                .filter(stream -> "subtitle".equals(stream.getCodecType()))
                .map(stream -> new SubtitleStream(
                    stream.getIndex(),
                    stream.getCodecName() != null ? stream.getCodecName() : "unknown",
                    tagValue(stream.getTags(), "language"),
                    tagValue(stream.getTags(), "title")
                ))
                .toList();

            Map<String, String> formatTags = normalizeTags(ffprobe.getFormat() != null ? ffprobe.getFormat().getTags() : null);

            return new ProbeResult(
                filePath,
                ffprobe.getFormat() != null && ffprobe.getFormat().getFormatName() != null
                    ? ffprobe.getFormat().getFormatName()
                    : "unknown",
                ffprobe.getFormat() != null ? parseDouble(ffprobe.getFormat().getDuration(), 0.0) : 0.0,
                ffprobe.getFormat() != null ? parseLong(ffprobe.getFormat().getSize(), 0L) : 0L,
                new ProbeStreams(videoStreams, audioStreams, subtitleStreams),
                formatTags
            );
        } catch (IOException exception) {
            return UncheckedThrow.sneakyThrow(exception);
        }
    }

    @Override
    public void clearCache() {
        withEvictionLock(() -> {
            cache.clear();
            evictionQueue.clear();
        });
    }

    private ProbeResult runProbe(Path path) {
        try {
            Process process = new ProcessBuilder(
                ffprobePath,
                "-v",
                "quiet",
                "-print_format",
                "json",
                "-show_format",
                "-show_streams",
                path.toString()
            ).redirectErrorStream(true).start();

            String output = new String(process.getInputStream().readAllBytes(), Charset.defaultCharset());

            boolean finished = waitFor(process, PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return UncheckedThrow.sneakyThrow(
                    new ProbeException("ffprobe timed out after " + PROBE_TIMEOUT_SECONDS + "s for " + path, null)
                );
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                return UncheckedThrow.sneakyThrow(
                    new ProbeException("ffprobe failed with exit code " + exitCode + " for " + path, null)
                );
            }

            return parseProbeOutput(output, path.toString());
        } catch (IOException exception) {
            return UncheckedThrow.sneakyThrow(exception);
        }
    }

    private void recordEvictionEntry(ProbeCacheKey key) {
        withEvictionLock(() -> {
            evictionQueue.addLast(key);
            while (evictionQueue.size() > MAX_PROBE_CACHE) {
                ProbeCacheKey oldest = evictionQueue.removeFirst();
                cache.remove(oldest);
            }
        });
    }

    private void withEvictionLock(Runnable action) {
        evictionLock.lock();
        try {
            action.run();
        } finally {
            evictionLock.unlock();
        }
    }

    private static boolean waitFor(Process process, long timeout, TimeUnit unit) {
        try {
            return process.waitFor(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return UncheckedThrow.sneakyThrow(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private static Integer parseBitrateKbps(String bitRate) {
        if (bitRate == null) {
            return null;
        }
        try {
            return Math.toIntExact(Long.parseLong(bitRate) / 1_000L);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String tagValue(Map<String, String> tags, String key) {
        return tags != null ? tags.get(key) : null;
    }

    private static Map<String, String> normalizeTags(Map<String, String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : rawTags.entrySet()) {
            normalized.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue());
        }
        return Map.copyOf(normalized);
    }

    private static double parseFps(String rateString) {
        String[] parts = rateString.split("/");
        if (parts.length != 2) {
            try {
                return Double.parseDouble(rateString);
            } catch (NumberFormatException exception) {
                return 0.0;
            }
        }
        try {
            double numerator = Double.parseDouble(parts[0]);
            double denominator = Double.parseDouble(parts[1]);
            return denominator != 0.0 ? numerator / denominator : 0.0;
        } catch (NumberFormatException exception) {
            return 0.0;
        }
    }
}
