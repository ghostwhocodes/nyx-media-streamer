package com.nyx.config;

import com.nyx.common.DatabaseResources;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;

public record ConfigPersistenceResources(
    Jdbi jdbi,
    HikariDataSource dataSource
) {
    public static ConfigPersistenceResources create(ServerConfig config) {
        DatabaseResources resources = ConfigRepository.createDatabase(config.getDatabase().getDir(), config.getDatabase());
        return new ConfigPersistenceResources(resources.getJdbi(), resources.getDataSource());
    }
}
