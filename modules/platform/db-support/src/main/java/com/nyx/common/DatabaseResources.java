package com.nyx.common;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class DatabaseResources implements AutoCloseable {
    private final Jdbi jdbi;
    private final HikariDataSource dataSource;

    public DatabaseResources(Jdbi jdbi, HikariDataSource dataSource) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Jdbi getJdbi() {
        return jdbi;
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }

    public Jdbi component1() {
        return jdbi;
    }

    public HikariDataSource component2() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
