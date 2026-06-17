package com.nyx.transcode;

import com.nyx.common.DatabaseResources;
import com.nyx.config.ServerConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;

public record JobRepositoryResources(Jdbi jdbi, HikariDataSource dataSource) {
    public static JobRepositoryResources create(ServerConfig config) {
        DatabaseResources resources = JobRepository.createDatabase(config.getDatabase().getDir(), config.getDatabase());
        return new JobRepositoryResources(resources.getJdbi(), resources.getDataSource());
    }
}
