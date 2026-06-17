package com.nyx.admin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.common.MetricsCollector;
import com.nyx.config.BackupConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackupServiceTest {
    private Path tempDir;
    private Path dbDir;
    private Map<String, DataSource> databases;
    private final java.util.List<HikariDataSource> dataSources = new java.util.ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-backup-test");
        dbDir = Files.createDirectories(tempDir.resolve("db"));

        HikariDataSource db1 = createSqliteDataSource(dbDir.resolve("test1.db"));
        HikariDataSource db2 = createSqliteDataSource(dbDir.resolve("test2.db"));
        try (var connection = db1.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS t1 (id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO t1 VALUES (1)");
        }
        try (var connection = db2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS t2 (id INTEGER PRIMARY KEY)");
            statement.execute("INSERT INTO t2 VALUES (1)");
        }
        databases = Map.of("test1", db1, "test2", db2);
    }

    @AfterEach
    void teardown() throws Exception {
        for (HikariDataSource dataSource : dataSources) {
            dataSource.close();
        }
        dataSources.clear();
        deleteRecursively(tempDir);
    }

    private HikariDataSource createSqliteDataSource(Path path) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + path);
        config.setDriverClassName("org.sqlite.JDBC");
        config.setMaximumPoolSize(1);
        HikariDataSource dataSource = new HikariDataSource(config);
        dataSources.add(dataSource);
        return dataSource;
    }

    private BackupService createService() {
        return createService(AdminFixtures.testBackupConfig(true, "", 0, 5), null);
    }

    private BackupService createService(BackupConfig config) {
        return createService(config, null);
    }

    private BackupService createService(BackupConfig config, MetricsCollector metricsCollector) {
        return AdminFixtures.newBackupService(databases, config, dbDir, metricsCollector);
    }

    @Test
    void runBackupCreatesTimestampedSubdirectoryWithAllDbFiles() {
        BackupService service = createService();
        BackupResult result = service.runBackup();

        assertNotNull(result);
        assertEquals(2, result.databases().size());
        assertTrue(result.databases().containsAll(List.of("test1", "test2")));

        Path backupDir = Path.of(result.backupDir());
        assertTrue(Files.exists(backupDir));
        assertTrue(Files.exists(backupDir.resolve("test1.db")));
        assertTrue(Files.exists(backupDir.resolve("test2.db")));
    }

    @Test
    void runBackupWithCustomDirCreatesBackupsThere() {
        Path customDir = tempDir.resolve("custom-backups");
        BackupService service = createService(AdminFixtures.testBackupConfig(true, customDir.toString(), 0, 5));

        BackupResult result = service.runBackup();

        assertNotNull(result);
        assertTrue(Path.of(result.backupDir()).startsWith(customDir));
        assertTrue(Files.exists(customDir));
    }

    @Test
    void runBackupReturnsBackupResultWithCorrectDatabaseNamesAndPositiveTotalBytes() {
        BackupService service = createService();
        BackupResult result = service.runBackup();

        assertNotNull(result);
        assertEquals(List.of("test1", "test2"), result.databases().stream().sorted().toList());
        assertTrue(result.totalBytes() > 0, "totalBytes should be positive");
        assertFalse(result.timestamp().isBlank(), "timestamp should not be blank");
        assertFalse(result.backupDir().isBlank(), "backupDir should not be blank");
    }

    @Test
    void runBackupUpdatesLastBackupTimestampEpochSecondsAndLastBackupTotalBytes() {
        BackupService service = createService();
        assertEquals(0L, service.getLastBackupTimestampEpochSeconds());
        assertEquals(0L, service.getLastBackupTotalBytes());

        service.runBackup();

        assertTrue(service.getLastBackupTimestampEpochSeconds() > 0);
        assertTrue(service.getLastBackupTotalBytes() > 0);
    }

    @Test
    void runBackupIncrementsBackupSuccessCount() {
        BackupService service = createService();
        assertEquals(0L, service.getBackupSuccessCount());

        service.runBackup();
        assertEquals(1L, service.getBackupSuccessCount());

        service.runBackup();
        assertEquals(2L, service.getBackupSuccessCount());
    }

    @Test
    void runBackupReturnsNullWhenBackupAlreadyInProgress() {
        BackupService service = createService();

        assertFalse(service.isBackupRunning(), "Should not be running before backup");

        BackupResult first = service.runBackup();
        assertNotNull(first, "First backup should succeed");
        assertFalse(service.isBackupRunning(), "Should not be running after backup completes");
    }

    @Test
    void isBackupRunningReturnsFalseWhenNoBackupIsRunning() {
        assertFalse(createService().isBackupRunning());
    }

    @Test
    void runBackupInvokesMetricsCollectorBackupCompletedOnSuccess() {
        RecordingMetricsCollector collector = new RecordingMetricsCollector();
        collector.onBackupCompleted = () -> collector.completedCalled = true;
        BackupService service = createService(AdminFixtures.testBackupConfig(true, "", 0, 5), collector);

        service.runBackup();

        assertTrue(collector.completedCalled, "backupCompleted should be called");
    }

    @Test
    void pruneOldBackupsKeepsOnlyRetainCountNewestDirectories() throws Exception {
        BackupService service = createService(AdminFixtures.testBackupConfig(true, "", 0, 2));
        Path backupRoot = Files.createDirectories(service.resolveBackupDir());

        for (String name : List.of(
            "backup-20240101-000000",
            "backup-20240102-000000",
            "backup-20240103-000000",
            "backup-20240104-000000"
        )) {
            Files.createDirectories(backupRoot.resolve(name));
        }

        service.pruneOldBackups(backupRoot);

        assertEquals(
            List.of("backup-20240103-000000", "backup-20240104-000000"),
            listBackupDirectories(backupRoot)
        );
    }

    @Test
    void pruneOldBackupsDeletesOldestDirectoriesFirst() throws Exception {
        BackupService service = createService(AdminFixtures.testBackupConfig(true, "", 0, 1));
        Path backupRoot = Files.createDirectories(service.resolveBackupDir());

        for (String name : List.of(
            "backup-20240101-000000",
            "backup-20240103-000000",
            "backup-20240102-000000"
        )) {
            Files.createDirectories(backupRoot.resolve(name));
        }

        service.pruneOldBackups(backupRoot);

        assertEquals(List.of("backup-20240103-000000"), listBackupDirectories(backupRoot));
    }

    @Test
    void pruneOldBackupsDoesNothingWhenWithinLimit() throws Exception {
        BackupService service = createService(AdminFixtures.testBackupConfig(true, "", 0, 5));
        Path backupRoot = Files.createDirectories(service.resolveBackupDir());

        Files.createDirectories(backupRoot.resolve("backup-20240101-000000"));
        Files.createDirectories(backupRoot.resolve("backup-20240102-000000"));

        service.pruneOldBackups(backupRoot);

        assertEquals(2, listBackupDirectories(backupRoot).size());
    }

    @Test
    void pruneOldBackupsHandlesNonExistentBackupDirGracefully() {
        BackupService service = createService();
        service.pruneOldBackups(tempDir.resolve("does-not-exist"));
    }

    @Test
    void runBackupCleansUpPartialDirectoryOnFailureAndIncrementsBackupFailureCount() throws Exception {
        Path invalidDir = tempDir.resolve("invalid-backup-dir");
        Files.createFile(invalidDir);
        BackupService service = AdminFixtures.newBackupService(
            databases,
            AdminFixtures.testBackupConfig(true, invalidDir.toString(), 0, 5),
            dbDir
        );

        assertThrows(Exception.class, service::runBackup);

        assertEquals(1L, service.getBackupFailureCount());
        assertEquals(0L, service.getBackupSuccessCount());
    }

    @Test
    void runBackupFailureInvokesMetricsCollectorBackupFailed() throws Exception {
        RecordingMetricsCollector collector = new RecordingMetricsCollector();
        collector.onBackupFailed = () -> collector.failedCalled = true;
        Path invalidDir = tempDir.resolve("invalid-backup-dir2");
        Files.createFile(invalidDir);
        BackupService service = AdminFixtures.newBackupService(
            databases,
            AdminFixtures.testBackupConfig(true, invalidDir.toString(), 0, 5),
            dbDir,
            collector
        );

        assertThrows(Exception.class, service::runBackup);

        assertTrue(collector.failedCalled, "backupFailed should be called");
    }

    @Test
    void resolveBackupDirReturnsDefaultWhenConfigDirIsBlank() {
        BackupService service = createService(AdminFixtures.testBackupConfig(true, "", 0, 5));
        assertEquals(dbDir.resolve("backups"), service.resolveBackupDir());
    }

    @Test
    void resolveBackupDirReturnsConfiguredPathWhenSet() {
        String customPath = "/tmp/nyx-custom-backups";
        BackupService service = createService(AdminFixtures.testBackupConfig(true, customPath, 0, 5));
        assertEquals(Path.of(customPath), service.resolveBackupDir());
    }

    @Test
    void constructorRejectsRetainCountOfZero() {
        assertThrows(IllegalArgumentException.class, () ->
            AdminFixtures.newBackupService(databases, AdminFixtures.testBackupConfig(true, "", 0, 0), dbDir)
        );
    }

    @Test
    void constructorRejectsNegativeRetainCount() {
        assertThrows(IllegalArgumentException.class, () ->
            AdminFixtures.newBackupService(databases, AdminFixtures.testBackupConfig(true, "", 0, -1), dbDir)
        );
    }

    @Test
    void scheduledBackupDoesNotLaunchWhenScheduleIntervalMinutesIsZero() {
        BackupService service = AdminFixtures.newBackupService(
            databases,
            AdminFixtures.testBackupConfig(true, "", 0, 5),
            dbDir
        );
        assertEquals(0L, service.getBackupSuccessCount());
    }

    @Test
    void getStatusReturnsZeroStateBeforeAnyBackup() {
        BackupStatus status = createService().getStatus();

        assertNull(status.lastBackupTimestamp());
        assertEquals(0L, status.lastBackupBytes());
        assertEquals(0L, status.successCount());
        assertEquals(0L, status.failureCount());
        assertFalse(status.backupDir().isBlank());
    }

    @Test
    void getStatusReflectsStateAfterSuccessfulBackup() {
        BackupService service = createService();
        service.runBackup();

        BackupStatus status = service.getStatus();
        assertNotNull(status.lastBackupTimestamp());
        assertTrue(status.lastBackupBytes() > 0);
        assertEquals(1L, status.successCount());
        assertEquals(0L, status.failureCount());
    }

    @Test
    void retainCountMustBeAtLeastOneWithEmptyDatabases() {
        assertThrows(IllegalArgumentException.class, () ->
            AdminFixtures.newBackupService(Map.of(), AdminFixtures.testBackupConfig(false, "", 0, 0), Path.of("/tmp"))
        );
    }

    @Test
    void resolveBackupDirUsesConfigDirWhenSetWithEmptyDatabases() {
        BackupService service = AdminFixtures.newBackupService(
            Map.of(),
            AdminFixtures.testBackupConfig(false, "/custom/backup", 0, 5),
            Path.of("/tmp/db")
        );
        assertEquals(Path.of("/custom/backup"), service.resolveBackupDir());
    }

    @Test
    void resolveBackupDirDefaultsToDbDirBackupsWithEmptyDatabases() {
        BackupService service = AdminFixtures.newBackupService(
            Map.of(),
            AdminFixtures.testBackupConfig(false, "", 0, 5),
            Path.of("/tmp/db")
        );
        assertEquals(Path.of("/tmp/db/backups"), service.resolveBackupDir());
    }

    @Test
    void pruneOldBackupsWithNonExistentDirDoesNothingWithEmptyDatabases() {
        BackupService service = AdminFixtures.newBackupService(
            Map.of(),
            AdminFixtures.testBackupConfig(false, "", 0, 3),
            Path.of("/tmp/db")
        );
        service.pruneOldBackups(Path.of("/nonexistent/path"));
    }

    @Test
    void pruneOldBackupsRetainsConfiguredCountWithEmptyDatabases() throws Exception {
        Path pruneDir = Files.createTempDirectory("nyx-prune-test");
        try {
            for (int index = 1; index <= 5; index++) {
                Files.createDirectories(pruneDir.resolve("backup-2024010" + index + "-000000-000"));
            }

            BackupService service = AdminFixtures.newBackupService(
                Map.of(),
                AdminFixtures.testBackupConfig(false, "", 0, 2),
                Path.of("/tmp")
            );
            service.pruneOldBackups(pruneDir);

            try (Stream<Path> entries = Files.list(pruneDir)) {
                long remaining = entries.filter(path -> path.getFileName().toString().startsWith("backup-")).count();
                assertEquals(2L, remaining);
            }
        } finally {
            deleteRecursively(pruneDir);
        }
    }

    @Test
    void getStatusBeforeAnyBackupWithEmptyDatabases() {
        BackupService service = AdminFixtures.newBackupService(Map.of(), new BackupConfig(), Path.of("/tmp"));
        BackupStatus status = service.getStatus();
        assertNull(status.lastBackupTimestamp());
        assertEquals(0L, status.lastBackupBytes());
        assertEquals(0L, status.successCount());
    }

    @Test
    void shutdownIsSafeWithoutScheduledJob() {
        BackupService service = AdminFixtures.newBackupService(
            Map.of(),
            AdminFixtures.testBackupConfig(false, "", 0, 5),
            Path.of("/tmp")
        );
        assertDoesNotThrow(service::shutdown);
    }

    @Test
    void isBackupRunningReturnsFalseInitiallyWithEmptyDatabases() {
        BackupService service = AdminFixtures.newBackupService(Map.of(), new BackupConfig(), Path.of("/tmp"));
        assertFalse(service.isBackupRunning());
    }

    @Test
    void scheduledBackupLoopFiresAfterDelay() throws Exception {
        Path schedTempDir = Files.createTempDirectory("nyx-backup-sched-test");
        try {
            Path schedDbDir = Files.createDirectories(schedTempDir.resolve("db"));
            HikariDataSource db1 = createSqliteDataSource(schedDbDir.resolve("sched1.db"));
            try (var connection = db1.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS t1 (id INTEGER PRIMARY KEY)");
                statement.execute("INSERT INTO t1 VALUES (1)");
            }
            TestScheduledExecutorService scheduler = new TestScheduledExecutorService();
            BackupService service = AdminFixtures.newBackupService(
                Map.of("sched1", db1),
                AdminFixtures.testBackupConfig(true, "", 1, 5),
                schedDbDir,
                null,
                scheduler
            );

            scheduler.runScheduledTask();

            assertTrue(
                service.getBackupSuccessCount() >= 1,
                "Expected at least 1 scheduled backup, got " + service.getBackupSuccessCount()
            );
            service.shutdown();
        } finally {
            deleteRecursively(schedTempDir);
        }
    }

    @Test
    void scheduledBackupLoopIsCreatedWhenConfigIsEnabledWithScheduler() throws Exception {
        Path schedTempDir = Files.createTempDirectory("nyx-backup-sched-test");
        try {
            Path schedDbDir = Files.createDirectories(schedTempDir.resolve("db"));
            HikariDataSource db1 = createSqliteDataSource(schedDbDir.resolve("sched1.db"));
            try (var connection = db1.getConnection(); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS t1 (id INTEGER PRIMARY KEY)");
                statement.execute("INSERT INTO t1 VALUES (1)");
            }
            TestScheduledExecutorService scheduler = new TestScheduledExecutorService();
            BackupService service = AdminFixtures.newBackupService(
                Map.of("sched1", db1),
                AdminFixtures.testBackupConfig(true, "", 1, 5),
                schedDbDir,
                null,
                scheduler
            );

            scheduler.runScheduledTask();
            service.shutdown();
        } finally {
            deleteRecursively(schedTempDir);
        }
    }

    private static List<String> listBackupDirectories(Path backupRoot) throws IOException {
        try (Stream<Path> stream = Files.list(backupRoot)) {
            return stream
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("backup-"))
                .sorted()
                .toList();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to delete " + path, exception);
                }
            });
        }
    }

    private static final class RecordingMetricsCollector implements MetricsCollector {
        private Runnable onBackupCompleted = () -> { };
        private Runnable onBackupFailed = () -> { };
        private boolean completedCalled;
        private boolean failedCalled;

        @Override
        public void jobStarted() {
        }

        @Override
        public void jobFinished() {
        }

        @Override
        public void ffmpegProcessStarted() {
        }

        @Override
        public void ffmpegProcessFinished() {
        }

        @Override
        public void recordSegmentCacheEviction() {
        }

        @Override
        public void recordJobDuration(long nanos) {
        }

        @Override
        public void recordProbeCacheHit() {
        }

        @Override
        public void recordProbeCacheMiss() {
        }

        @Override
        public void recordProbeDuration(long nanos) {
        }

        @Override
        public void recordFfmpegProcessDuration(long nanos, boolean success) {
        }

        @Override
        public void recordThumbnailCacheHit() {
        }

        @Override
        public void recordThumbnailCacheMiss() {
        }

        @Override
        public void recordThumbnailGenerationDuration(long nanos) {
        }

        @Override
        public void webhookDispatched() {
        }

        @Override
        public void webhookDeliverySuccess() {
        }

        @Override
        public void webhookDeliveryFailure() {
        }

        @Override
        public void webhookDeliveriesPurged(int count) {
        }

        @Override
        public void backupCompleted() {
            onBackupCompleted.run();
        }

        @Override
        public void backupFailed() {
            onBackupFailed.run();
        }
    }
}
