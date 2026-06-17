package com.nyx.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nyx.config.DatabaseConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatabaseFactoryTest {
    private Path tempDir;

    @BeforeEach
    void setup() throws Exception {
        tempDir = Files.createTempDirectory("nyx-dbfactory-test");
    }

    @AfterEach
    void teardown() throws Exception {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach(path -> path.toFile().delete());
    }

    @Test
    void createCreatesTheDatabaseFile() {
        Path dbDir = tempDir.resolve("dbdir");
        createDirectories(dbDir);

        DatabaseResources resources = DatabaseFactory.create(dbDir, "testdb");
        try {
            resources.getJdbi().useHandle(handle -> handle.execute("SELECT 1"));
            assertThat(dbDir.resolve("testdb.db")).exists();
        } finally {
            resources.close();
        }
    }

    @Test
    void createCreatesParentDirectoriesIfNeeded() {
        Path nestedDir = tempDir.resolve("a/b/c/d");
        assertThat(Files.exists(nestedDir)).isFalse();

        DatabaseResources resources = DatabaseFactory.create(nestedDir, "nested");
        try {
            assertThat(Files.exists(nestedDir)).isTrue();
            resources.getJdbi().useHandle(handle -> handle.execute("SELECT 1"));
            assertThat(nestedDir.resolve("nested.db")).exists();
        } finally {
            resources.close();
        }
    }

    @Test
    void createReturnsAUsableJdbiInstance() {
        DatabaseResources resources = DatabaseFactory.create(tempDir.resolve("usable"), "usable");
        try {
            Integer result = SqliteWriteTransactions.withHandleUnchecked(
                resources.getJdbi(),
                handle -> handle.createQuery("SELECT 1").mapTo(Integer.class).one()
            );
            assertThat(result).isEqualTo(1);
        } finally {
            resources.close();
        }
    }

    @Test
    void createWithSameDirAndNameReturnsDatabaseWithoutError() {
        Path dbDir = tempDir.resolve("samedir");

        DatabaseResources resources1 = DatabaseFactory.create(dbDir, "same");
        DatabaseResources resources2 = DatabaseFactory.create(dbDir, "same");
        try {
            int first = SqliteWriteTransactions.withHandleUnchecked(
                resources1.getJdbi(),
                handle -> handle.createQuery("SELECT 1").mapTo(Integer.class).one()
            );
            int second = SqliteWriteTransactions.withHandleUnchecked(
                resources2.getJdbi(),
                handle -> handle.createQuery("SELECT 1").mapTo(Integer.class).one()
            );
            assertThat(first).isEqualTo(1);
            assertThat(second).isEqualTo(1);
        } finally {
            resources1.close();
            resources2.close();
        }
    }

    @Test
    void createWithDifferentNamesInSameDirCreatesSeparateFiles() {
        Path dbDir = tempDir.resolve("multidb");

        DatabaseResources alpha = DatabaseFactory.create(dbDir, "alpha");
        DatabaseResources beta = DatabaseFactory.create(dbDir, "beta");
        try {
            alpha.getJdbi().useHandle(handle -> handle.execute("SELECT 1"));
            beta.getJdbi().useHandle(handle -> handle.execute("SELECT 1"));

            assertThat(dbDir.resolve("alpha.db")).exists();
            assertThat(dbDir.resolve("beta.db")).exists();
        } finally {
            alpha.close();
            beta.close();
        }
    }

    @Test
    void createUsesWalJournalMode() throws Exception {
        DatabaseResources resources = DatabaseFactory.create(tempDir.resolve("waltest"), "waldb");
        try {
            String journalMode = resources.getDataSource().getConnection().createStatement()
                .executeQuery("PRAGMA journal_mode")
                .getString(1);
            assertThat(journalMode).isEqualTo("wal");
        } finally {
            resources.close();
        }
    }

    @Test
    void createReturnsAHikariDataSource() {
        DatabaseResources resources = DatabaseFactory.create(tempDir.resolve("hikari"), "hikaridb");
        assertThat(resources.getDataSource().isClosed()).isFalse();
        resources.close();
        assertThat(resources.getDataSource().isClosed()).isTrue();
    }

    @Test
    void createRunsFlywayMigrationsForJobsDatabase() {
        DatabaseResources resources = DatabaseFactory.create(tempDir.resolve("flyway-jobs"), "jobs");
        try {
            int migrationCount = queryInt(resources.getDataSource(), "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1");
            assertThat(migrationCount).isEqualTo(1);
        } finally {
            resources.close();
        }
    }

    @Test
    void createEnsuresTranscodeJobsTableExistsAfterFlywayMigration() throws Exception {
        DatabaseResources resources = DatabaseFactory.create(tempDir.resolve("flyway-jobs2"), "jobs");
        try (var connection = resources.getDataSource().getConnection();
             var statement = connection.createStatement()) {
            try (var resultSet = statement.executeQuery("SELECT COUNT(*) FROM transcode_jobs")) {
                assertThat(resultSet.next()).isTrue();
            }
            Set<String> columns;
            try (var resultSet = statement.executeQuery("PRAGMA table_info(transcode_jobs)")) {
                var builder = new java.util.LinkedHashSet<String>();
                while (resultSet.next()) {
                    builder.add(resultSet.getString("name"));
                }
                columns = builder;
            }
            assertThat(columns).contains(
                "execution_mode",
                "spec_key",
                "retry_count",
                "batch_id",
                "owner",
                "output_size_bytes"
            );
        } finally {
            resources.close();
        }
    }

    @Test
    void createRunsFlywayMigrationsForEformsDatabase() {
        DatabaseResources resources = DatabaseFactory.create(tempDir.resolve("flyway-eforms"), "eforms");
        try {
            int migrationCount = queryInt(resources.getDataSource(), "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1");
            assertThat(migrationCount).isGreaterThanOrEqualTo(1);
        } finally {
            resources.close();
        }
    }

    @Test
    void databaseFactorySetsBusyTimeoutPragmaOnConnections() throws Exception {
        DatabaseResources resources = DatabaseFactory.create(tempDir.resolve("busytimeout"), "busytest");
        try (var connection = resources.getDataSource().getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery("PRAGMA busy_timeout")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(5000);
        } finally {
            resources.close();
        }
    }

    @Test
    void createSucceedsWithNoMigrationScripts() {
        DatabaseResources resources = DatabaseFactory.create(
            tempDir,
            "test_no_migration",
            databaseConfig(tempDir)
        );
        try {
            assertThat(resources.getJdbi()).isNotNull();
        } finally {
            resources.close();
        }
    }

    @Test
    void createWithKnownMigrationNameSucceeds() {
        DatabaseResources resources = DatabaseFactory.create(
            tempDir,
            "webhooks",
            databaseConfig(tempDir)
        );
        try {
            assertThat(resources.getJdbi()).isNotNull();
            resources.getJdbi().useHandle(handle -> handle.execute("SELECT 1"));
            assertThat(tempDir.resolve("webhooks.db")).exists();
        } finally {
            resources.close();
        }
    }

    @Test
    void createClosesDatasourceWhenFlywayMigrationFailsDueToChecksumMismatch() throws Exception {
        Path dbDir = tempDir.resolve("checksum-fail");
        createDirectories(dbDir);

        Path dbPath = dbDir.resolve("jobs.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
             var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE flyway_schema_history (
                    installed_rank INTEGER PRIMARY KEY,
                    version VARCHAR(50),
                    description VARCHAR(200),
                    type VARCHAR(20),
                    script VARCHAR(1000),
                    checksum INTEGER,
                    installed_by VARCHAR(100),
                    installed_on TEXT DEFAULT (datetime('now')),
                    execution_time INTEGER,
                    success INTEGER
                )
                """);
            statement.execute("""
                INSERT INTO flyway_schema_history
                    (installed_rank, version, description, type, script, checksum,
                     installed_by, execution_time, success)
                VALUES (1, '1', 'initial schema', 'SQL',
                        'V1__initial_schema.sql', 99999999,
                        'test', 0, 1)
                """);
        }

        assertThatThrownBy(() -> DatabaseFactory.create(dbDir, "jobs", databaseConfig(dbDir)))
            .isInstanceOf(Exception.class)
            .hasMessageMatching("(?is).*(checksum|migration).*");
    }

    private static int queryInt(HikariDataSource dataSource, String sql) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static DatabaseConfig databaseConfig(Path dir) {
        return new DatabaseConfig(dir, 1, 600_000L, 1_800_000L);
    }
}
