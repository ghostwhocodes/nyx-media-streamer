package com.nyx.admin;

import com.nyx.common.HealthMonitor;
import com.nyx.config.ServerConfig;
import com.nyx.transcode.contracts.TranscodeJobStore;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HealthService implements HealthMonitor {
    static final long BYTES_PER_MB = 1_048_576L;
    static final long HEALTH_CHECK_TIMEOUT_SECONDS = 10L;
    static final double DISK_WARN_THRESHOLD_PERCENT = 10.0d;
    static final int STUCK_JOBS_THRESHOLD = 50;

    private final Logger logger = LoggerFactory.getLogger(HealthService.class);
    private final ServerConfig config;
    private final TranscodeJobStore jobRepository;
    private final BooleanSupplier circuitBreakerOpen;
    private final HwAccelDetector hwAccelDetector;
    private boolean ffmpegAvailableState;

    public HealthService(ServerConfig config, TranscodeJobStore jobRepository) {
        this(config, jobRepository, null);
    }

    public HealthService(ServerConfig config, TranscodeJobStore jobRepository, BooleanSupplier circuitBreakerOpen) {
        this.config = config;
        this.jobRepository = jobRepository;
        this.circuitBreakerOpen = circuitBreakerOpen;
        this.hwAccelDetector = new HwAccelDetector(config.getFfmpeg().getPath());
    }

    @Override
    public boolean isFfmpegAvailable() {
        return ffmpegAvailableState;
    }

    public boolean getFfmpegAvailable() {
        return isFfmpegAvailable();
    }

    public StartupHealthReport runStartupChecks() {
        logger.info("Running startup health checks...");

        var diagnostics = StructuredHealthChecks.collectStartupDiagnostics(config, hwAccelDetector, logger, BYTES_PER_MB);
        String ffmpegVersion = diagnostics.getFfmpegVersion();
        String ffprobeVersion = diagnostics.getFfprobeVersion();
        var hwAccels = diagnostics.getHwAccels();
        var encoders = diagnostics.getEncoders();

        if (ffmpegVersion == null) {
            ffmpegAvailableState = false;
            logger.error(
                "FFmpeg not found at '{}' — transcoding, thumbnails, and audio transcode will return 503",
                config.getFfmpeg().getPath()
            );
        } else {
            if (!HwAccelDetector.isVersionSufficient(ffmpegVersion, config.getFfmpeg().getMinVersion())) {
                throw new IllegalStateException(
                    "FFmpeg version " + ffmpegVersion + " is below minimum required " + config.getFfmpeg().getMinVersion()
                );
            }
            logger.info("FFmpeg version: {}", ffmpegVersion);

            if (ffprobeVersion == null) {
                ffmpegAvailableState = false;
                logger.error(
                    "FFmpeg binary found (version {}) but FFprobe is missing at '{}' — all FFmpeg-backed features "
                        + "(transcoding, thumbnails, audio transcode) will return 503",
                    ffmpegVersion,
                    config.getFfmpeg().getFfprobePath()
                );
            } else {
                ffmpegAvailableState = true;
                logger.info("FFprobe version: {}", ffprobeVersion);
                logger.info("Available HW accelerations: {}", hwAccels);
                logger.info("Available encoders: {}...", encoders.stream().limit(10).toList());
            }
        }

        var mediaRootStatuses = diagnostics.getMediaRoots();
        String dbStatus = diagnostics.getDbStatus();
        logger.info("Database status: {}", dbStatus);

        JvmInfo jvmInfo = diagnostics.getJvmInfo();
        logger.info(
            "JVM: {}, max memory: {} MB, processors: {}",
            jvmInfo.version(),
            jvmInfo.maxMemoryBytes() / BYTES_PER_MB,
            jvmInfo.availableProcessors()
        );

        StartupHealthReport report = new StartupHealthReport(
            ffmpegVersion,
            ffprobeVersion,
            List.copyOf(hwAccels),
            List.copyOf(encoders),
            mediaRootStatuses,
            dbStatus,
            jvmInfo
        );

        logger.info("Startup health checks completed successfully");
        return report;
    }

    public RuntimeHealthResponse runtimeHealth() {
        boolean ffmpegBinaryResponds = checkBinary(config.getFfmpeg().getPath()) && checkBinary(config.getFfmpeg().getFfprobePath());

        int activeJobs;
        try {
            activeJobs = jobRepository.listActive().size();
        } catch (Exception exception) {
            logger.debug("Failed to count active jobs: {}", exception.getMessage());
            activeJobs = -1;
        }

        boolean dbWritable;
        try {
            dbWritable = Files.isWritable(config.getDatabase().getDir());
        } catch (Exception ignored) {
            dbWritable = false;
        }

        boolean dbConnectivity;
        try {
            jobRepository.countAll(null);
            dbConnectivity = true;
        } catch (Exception exception) {
            logger.warn("Database connectivity check failed: {}", exception.getMessage());
            dbConnectivity = false;
        }

        boolean diskSpaceWarning = config.getMediaRoots().stream().anyMatch(root -> {
            try {
                var store = Files.getFileStore(root.getPath());
                double usable = store.getUsableSpace();
                double total = store.getTotalSpace();
                return total > 0.0d && (usable / total) * 100.0d < DISK_WARN_THRESHOLD_PERCENT;
            } catch (Exception ignored) {
                return false;
            }
        });

        boolean stuckJobsWarning = activeJobs > STUCK_JOBS_THRESHOLD;
        boolean circuitBreakerIsOpen = circuitBreakerOpen != null && circuitBreakerOpen.getAsBoolean();

        String status;
        if (!ffmpegBinaryResponds || !dbWritable || !dbConnectivity || diskSpaceWarning || stuckJobsWarning || circuitBreakerIsOpen) {
            status = "degraded";
        } else {
            status = "ok";
        }

        return new RuntimeHealthResponse(
            status,
            ffmpegBinaryResponds,
            activeJobs,
            dbWritable,
            diskSpaceWarning,
            dbConnectivity,
            stuckJobsWarning,
            circuitBreakerIsOpen,
            null,
            null
        );
    }

    public LivenessResponse liveness() {
        return new LivenessResponse();
    }

    public ReadinessResponse readiness() {
        boolean dbWritable;
        try {
            dbWritable = Files.isWritable(config.getDatabase().getDir());
        } catch (Exception ignored) {
            dbWritable = false;
        }

        boolean dbConnectivity;
        try {
            jobRepository.countAll(null);
            dbConnectivity = true;
        } catch (Exception exception) {
            logger.warn("Readiness DB check failed: {}", exception.getMessage());
            dbConnectivity = false;
        }

        boolean diskSpaceOk = config.getMediaRoots().stream().allMatch(root -> {
            try {
                return Files.getFileStore(root.getPath()).getUsableSpace() >= config.getTranscode().getMinFreeDiskBytes();
            } catch (Exception ignored) {
                return true;
            }
        });

        boolean circuitBreakerIsOpen = circuitBreakerOpen != null && circuitBreakerOpen.getAsBoolean();
        boolean ready = dbWritable && dbConnectivity && diskSpaceOk && !circuitBreakerIsOpen;
        return new ReadinessResponse(
            ready ? "ready" : "not_ready",
            dbWritable,
            dbConnectivity,
            diskSpaceOk,
            circuitBreakerIsOpen
        );
    }

    private boolean checkBinary(String path) {
        try {
            Process process = new ProcessBuilder(path, "-version").redirectErrorStream(true).start();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // Drain output to avoid blocking.
                }
            }
            boolean finished = process.waitFor(HEALTH_CHECK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            return finished && process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
