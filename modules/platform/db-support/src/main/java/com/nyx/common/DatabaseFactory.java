package com.nyx.common;

import com.nyx.config.DatabaseConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.jackson2.Jackson2Plugin;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DatabaseFactory {
    private static final Logger LOG = LoggerFactory.getLogger("DatabaseFactory");

    private DatabaseFactory() {}

    public static DatabaseResources create(Path dir, String name) {
        return create(dir, name, new DatabaseConfig(dir, 4, 600_000L, 1_800_000L));
    }

    public static DatabaseResources create(Path dir, String name, DatabaseConfig config) {
        try {
            Files.createDirectories(dir);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create database directory: " + dir, exception);
        }
        Path dbPath = dir.resolve(name + ".db");
        String jdbcUrl = "jdbc:sqlite:" + dbPath + "?journal_mode=WAL&foreign_keys=true";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setMaximumPoolSize(config.getPoolSize());
        hikariConfig.setIdleTimeout(config.getIdleTimeoutMs());
        hikariConfig.setMaxLifetime(config.getMaxLifetimeMs());
        hikariConfig.setConnectionTestQuery("SELECT 1");
        hikariConfig.setConnectionInitSql("PRAGMA busy_timeout=5000");
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);

        try {
            String migrationLocation = "classpath:db/migration/" + name;
            var migrationResource = DatabaseFactory.class.getClassLoader().getResource("db/migration/" + name);
            if (migrationResource != null) {
                var flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations(migrationLocation)
                    .load();
                var result = flyway.migrate();
                if (result.migrationsExecuted > 0) {
                    LOG.info("Flyway: applied {} migration(s) to '{}'", result.migrationsExecuted, name);
                }
            } else {
                LOG.debug("No Flyway migration scripts for '{}', skipping", name);
            }
        } catch (Exception exception) {
            dataSource.close();
            throw exception;
        }

        Jdbi jdbi = Jdbi.create(dataSource)
            .installPlugin(new Jackson2Plugin());
        return new DatabaseResources(jdbi, dataSource);
    }
}
