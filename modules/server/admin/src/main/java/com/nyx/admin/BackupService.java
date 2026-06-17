package com.nyx.admin;

import com.nyx.common.ManagedService;
import com.nyx.common.MetricsCollector;
import com.nyx.config.BackupConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BackupService implements ManagedService {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Logger logger = LoggerFactory.getLogger(BackupService.class);
    private final Map<String, DataSource> databases;
    private final BackupConfig config;
    private final Path databaseDir;
    private final MetricsCollector metricsCollector;
    private final ReentrantLock lock = new ReentrantLock();
    private final boolean ownsScheduler;
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> scheduledTask;
    private final AtomicLong lastBackupTimestampEpochSeconds = new AtomicLong(0L);
    private final AtomicLong lastBackupTotalBytes = new AtomicLong(0L);
    private final AtomicLong backupSuccessCount = new AtomicLong(0L);
    private final AtomicLong backupFailureCount = new AtomicLong(0L);

    public BackupService(Map<String, DataSource> databases, BackupConfig config, Path databaseDir) {
        this(databases, config, databaseDir, null, null);
    }

    public BackupService(
        Map<String, DataSource> databases,
        BackupConfig config,
        Path databaseDir,
        MetricsCollector metricsCollector
    ) {
        this(databases, config, databaseDir, metricsCollector, null);
    }

    public BackupService(
        Map<String, DataSource> databases,
        BackupConfig config,
        Path databaseDir,
        MetricsCollector metricsCollector,
        ScheduledExecutorService scheduledExecutor
    ) {
        this.databases = databases;
        this.config = config;
        this.databaseDir = databaseDir;
        this.metricsCollector = metricsCollector;
        this.ownsScheduler = scheduledExecutor == null;
        this.scheduler = scheduledExecutor != null
            ? scheduledExecutor
            : Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "nyx-backup-scheduler");
                thread.setDaemon(true);
                return thread;
            });

        if (config.getRetainCount() < 1) {
            throw new IllegalArgumentException("backup.retainCount must be >= 1, got " + config.getRetainCount());
        }

        if (config.getEnabled() && config.getScheduleIntervalMinutes() > 0) {
            long intervalMs = config.getScheduleIntervalMinutes() * 60_000L;
            scheduledTask = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    runBackup();
                } catch (Exception exception) {
                    logger.error("Scheduled backup failed: {}", exception.getMessage(), exception);
                }
            }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        } else {
            scheduledTask = null;
        }
    }

    boolean isBackupRunning() {
        return lock.isLocked();
    }

    public long getLastBackupTimestampEpochSeconds() {
        return lastBackupTimestampEpochSeconds.get();
    }

    public long getLastBackupTotalBytes() {
        return lastBackupTotalBytes.get();
    }

    public long getBackupSuccessCount() {
        return backupSuccessCount.get();
    }

    public long getBackupFailureCount() {
        return backupFailureCount.get();
    }

    public BackupResult runBackup() {
        if (!lock.tryLock()) {
            logger.info("Backup already in progress, skipping");
            return null;
        }
        try {
            return executeBackup();
        } finally {
            lock.unlock();
        }
    }

    private BackupResult executeBackup() {
        Path backupRoot = resolveBackupDir();
        String timestamp = LocalDateTime.now(ZoneOffset.UTC).format(TIMESTAMP_FORMAT);
        Path backupDir = backupRoot.resolve("backup-" + timestamp);

        try {
            Files.createDirectories(backupDir);

            long totalBytes = 0L;
            List<String> backedUpNames = new ArrayList<>();

            for (Map.Entry<String, DataSource> entry : databases.entrySet()) {
                String name = entry.getKey();
                DataSource database = entry.getValue();
                Path destFile = backupDir.resolve(name + ".db");
                String escapedPath = destFile.toAbsolutePath().toString().replace("'", "''");

                try (var connection = database.getConnection()) {
                    connection.setAutoCommit(true);
                    try (var statement = connection.createStatement()) {
                        statement.execute("VACUUM INTO '" + escapedPath + "'");
                    }
                }

                totalBytes += Files.size(destFile);
                backedUpNames.add(name);
                logger.debug("Backed up database '{}' to {}", name, destFile);
            }

            pruneOldBackups(backupRoot);

            lastBackupTimestampEpochSeconds.set(Instant.now().getEpochSecond());
            lastBackupTotalBytes.set(totalBytes);
            backupSuccessCount.incrementAndGet();
            if (metricsCollector != null) {
                metricsCollector.backupCompleted();
            }

            logger.info(
                "Backup completed: {} databases, {} bytes -> {}",
                backedUpNames.size(),
                totalBytes,
                backupDir
            );

            return new BackupResult(timestamp, List.copyOf(backedUpNames), totalBytes, backupDir.toString());
        } catch (Exception exception) {
            backupFailureCount.incrementAndGet();
            if (metricsCollector != null) {
                metricsCollector.backupFailed();
            }
            logger.error("Backup failed, cleaning up partial directory {}: {}", backupDir, exception.getMessage(), exception);
            try {
                if (Files.exists(backupDir)) {
                    deleteRecursively(backupDir);
                }
            } catch (IOException cleanupException) {
                logger.warn(
                    "Failed to clean up partial backup dir {}: {}",
                    backupDir,
                    cleanupException.getMessage()
                );
            }
            return sneakyThrow(exception);
        }
    }

    void pruneOldBackups(Path backupRoot) {
        if (!Files.exists(backupRoot) || !Files.isDirectory(backupRoot)) {
            return;
        }

        try (var entries = Files.list(backupRoot)) {
            List<Path> backupDirs = entries
                .filter(path -> path.getFileName().toString().startsWith("backup-"))
                .filter(Files::isDirectory)
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();

            int toDelete = backupDirs.size() - config.getRetainCount();
            if (toDelete <= 0) {
                return;
            }

            for (Path directory : backupDirs.subList(0, toDelete)) {
                logger.info("Pruning old backup: {}", directory);
                deleteRecursively(directory);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to inspect backup directory " + backupRoot, exception);
        }
    }

    Path resolveBackupDir() {
        if (!config.getDir().isBlank()) {
            return Path.of(config.getDir());
        }
        return databaseDir.resolve("backups");
    }

    public BackupStatus getStatus() {
        long timestamp = lastBackupTimestampEpochSeconds.get();
        return new BackupStatus(
            timestamp > 0 ? Instant.ofEpochSecond(timestamp).toString() : null,
            lastBackupTotalBytes.get(),
            backupSuccessCount.get(),
            backupFailureCount.get(),
            resolveBackupDir().toString()
        );
    }

    @Override
    public void shutdown() {
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
        }
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static <T> T sneakyThrow(Throwable throwable) {
        BackupService.<RuntimeException>throwUnchecked(throwable);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
