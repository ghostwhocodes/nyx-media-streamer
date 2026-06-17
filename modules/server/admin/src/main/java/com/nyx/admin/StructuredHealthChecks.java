package com.nyx.admin;

import com.nyx.config.ServerConfig;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

public final class StructuredHealthChecks {
    private static final Pattern FFPROBE_VERSION_PATTERN = Pattern.compile("ffprobe version (\\S+)");

    private StructuredHealthChecks() {
    }

    public static StartupDiagnostics collectStartupDiagnostics(
        ServerConfig config,
        HwAccelDetector hwAccelDetector,
        Logger logger,
        long bytesPerMb
    ) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> ffmpegVersionOutputTask = executor.submit(
                () -> runCommand(List.of(config.getFfmpeg().getPath(), "-version"))
            );
            Future<String> ffprobeVersionOutputTask = executor.submit(
                () -> runCommand(List.of(config.getFfmpeg().getFfprobePath(), "-version"))
            );
            Future<String> hwAccelsOutputTask = executor.submit(
                () -> runCommand(List.of(config.getFfmpeg().getPath(), "-hwaccels"))
            );
            Future<String> encodersOutputTask = executor.submit(
                () -> runCommand(List.of(config.getFfmpeg().getPath(), "-encoders"))
            );
            Future<List<MediaRootStatus>> mediaRootsTask = executor.submit(
                () -> inspectMediaRoots(config, logger, bytesPerMb)
            );
            Future<String> dbStatusTask = executor.submit(() -> checkDatabase(config.getDatabase().getDir()));
            Future<JvmInfo> jvmInfoTask = executor.submit(StructuredHealthChecks::buildJvmInfo);

            String ffmpegVersionOutput = getUnchecked(ffmpegVersionOutputTask);
            String ffprobeVersionOutput = getUnchecked(ffprobeVersionOutputTask);
            String hwAccelsOutput = getUnchecked(hwAccelsOutputTask);
            String encodersOutput = getUnchecked(encodersOutputTask);
            List<MediaRootStatus> mediaRoots = getUnchecked(mediaRootsTask);
            String dbStatus = getUnchecked(dbStatusTask);
            JvmInfo jvmInfo = getUnchecked(jvmInfoTask);

            return new StartupDiagnostics(
                HwAccelDetector.parseVersion(ffmpegVersionOutput),
                parseFfprobeVersion(ffprobeVersionOutput),
                HwAccelDetector.parseHwAccels(hwAccelsOutput),
                HwAccelDetector.parseEncoders(encodersOutput),
                mediaRoots,
                dbStatus,
                jvmInfo
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while collecting startup diagnostics", exception);
        }
    }

    private static <T> T getUnchecked(Future<T> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Failed to collect startup diagnostics", exception.getCause());
        }
    }

    private static String runCommand(List<String> args) {
        try {
            Process process = new ProcessBuilder(args)
                .redirectErrorStream(true)
                .start();
            String output;
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                StringWriter writer = new StringWriter();
                reader.transferTo(writer);
                output = writer.toString();
            }
            process.waitFor();
            return output;
        } catch (Exception exception) {
            return "";
        }
    }

    private static String parseFfprobeVersion(String output) {
        String firstLine = output.lines().findFirst().orElse(null);
        if (firstLine == null) {
            return null;
        }
        Matcher matcher = FFPROBE_VERSION_PATTERN.matcher(firstLine);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static List<MediaRootStatus> inspectMediaRoots(ServerConfig config, Logger logger, long bytesPerMb) {
        List<MediaRootStatus> statuses = new ArrayList<>();
        for (var mediaRoot : config.getMediaRoots()) {
            Path path = mediaRoot.getPath();
            boolean exists = Files.exists(path);
            boolean readable = exists && Files.isReadable(path);
            long freeSpace = 0L;
            if (exists) {
                try {
                    freeSpace = Files.getFileStore(path).getUsableSpace();
                } catch (Exception exception) {
                    logger.debug("Could not determine free space for {}: {}", path, exception.getMessage());
                }
            }

            if (!exists) {
                logger.warn("Media root does not exist: {}", path);
            } else if (!readable) {
                logger.warn("Media root not readable: {}", path);
            } else {
                logger.info("Media root OK: {} (free: {} MB)", path, freeSpace / bytesPerMb);
            }

            statuses.add(new MediaRootStatus(path.toString(), exists, readable, freeSpace));
        }
        return statuses;
    }

    private static String checkDatabase(Path dbDir) {
        try {
            if (!Files.exists(dbDir)) {
                Files.createDirectories(dbDir);
            }
            if (!Files.isWritable(dbDir)) {
                return "error: directory not writable";
            }
            return "ok";
        } catch (Exception exception) {
            return "error: " + exception.getMessage();
        }
    }

    private static JvmInfo buildJvmInfo() {
        Runtime runtime = Runtime.getRuntime();
        return new JvmInfo(
            System.getProperty("java.version"),
            runtime.maxMemory(),
            runtime.availableProcessors()
        );
    }

    public static final class StartupDiagnostics {
        private final String ffmpegVersion;
        private final String ffprobeVersion;
        private final java.util.Set<String> hwAccels;
        private final java.util.Set<String> encoders;
        private final List<MediaRootStatus> mediaRoots;
        private final String dbStatus;
        private final JvmInfo jvmInfo;

        public StartupDiagnostics(
            String ffmpegVersion,
            String ffprobeVersion,
            java.util.Set<String> hwAccels,
            java.util.Set<String> encoders,
            List<MediaRootStatus> mediaRoots,
            String dbStatus,
            JvmInfo jvmInfo
        ) {
            this.ffmpegVersion = ffmpegVersion;
            this.ffprobeVersion = ffprobeVersion;
            this.hwAccels = hwAccels;
            this.encoders = encoders;
            this.mediaRoots = mediaRoots;
            this.dbStatus = dbStatus;
            this.jvmInfo = jvmInfo;
        }

        public String getFfmpegVersion() {
            return ffmpegVersion;
        }

        public String getFfprobeVersion() {
            return ffprobeVersion;
        }

        public java.util.Set<String> getHwAccels() {
            return hwAccels;
        }

        public java.util.Set<String> getEncoders() {
            return encoders;
        }

        public List<MediaRootStatus> getMediaRoots() {
            return mediaRoots;
        }

        public String getDbStatus() {
            return dbStatus;
        }

        public JvmInfo getJvmInfo() {
            return jvmInfo;
        }
    }
}
