package com.nyx.eforms;

import com.nyx.common.DatabaseResources;
import com.nyx.config.ServerConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jdbi.v3.core.Jdbi;

public final class EFormsPersistenceResources {
    private final Jdbi jdbi;
    private final HikariDataSource dataSource;

    public EFormsPersistenceResources(Jdbi jdbi, HikariDataSource dataSource) {
        this.jdbi = jdbi;
        this.dataSource = dataSource;
    }

    public Jdbi getJdbi() {
        return jdbi;
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public static EFormsPersistenceResources create(ServerConfig config) {
        DatabaseResources resources = EFormsDatabase.createDatabase(config.getDatabase().getDir(), config.getDatabase());
        return new EFormsPersistenceResources(resources.getJdbi(), resources.getDataSource());
    }
}
