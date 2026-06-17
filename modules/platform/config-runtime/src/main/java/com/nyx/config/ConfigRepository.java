package com.nyx.config;

import com.nyx.common.DatabaseResources;
import com.nyx.common.DatabaseFactory;
import com.nyx.common.SqliteWriteTransactions;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.jdbi.v3.core.Jdbi;

public final class ConfigRepository implements ConfigStore {
    private static final String UPSERT_CONFIG_OVERRIDE = """
        INSERT INTO config_overrides(key, value)
        VALUES (:key, :value)
        ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """;

    private static final String UPSERT_AUTH_USER = """
        INSERT INTO auth_users(username, password_hash)
        VALUES (:username, :passwordHash)
        ON CONFLICT(username) DO UPDATE SET password_hash = excluded.password_hash
        """;

    private final Jdbi jdbi;

    public ConfigRepository(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public Map<String, String> getOverrides() {
        return SqliteWriteTransactions.withHandleUnchecked(jdbi, handle ->
            handle.createQuery("SELECT key, value FROM config_overrides")
                .map((resultSet, ctx) -> Map.entry(resultSet.getString("key"), resultSet.getString("value")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }

    @Override
    public void setOverride(String key, String value) {
        SqliteWriteTransactions.inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate(UPSERT_CONFIG_OVERRIDE)
                .bind("key", key)
                .bind("value", value)
                .execute();
            return null;
        });
    }

    @Override
    public Map<String, String> getAllUsers() {
        return SqliteWriteTransactions.withHandleUnchecked(jdbi, handle ->
            handle.createQuery("SELECT username, password_hash FROM auth_users")
                .map((resultSet, ctx) -> Map.entry(resultSet.getString("username"), resultSet.getString("password_hash")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
        );
    }

    @Override
    public void upsertUser(String username, String passwordHash) {
        SqliteWriteTransactions.inTransactionUnchecked(jdbi, handle -> {
            handle.createUpdate(UPSERT_AUTH_USER)
                .bind("username", username)
                .bind("passwordHash", passwordHash)
                .execute();
            return null;
        });
    }

    @Override
    public boolean deleteUser(String username) {
        return SqliteWriteTransactions.inTransactionUnchecked(jdbi, handle ->
            handle.createUpdate("DELETE FROM auth_users WHERE username = :username")
                .bind("username", username)
                .execute() > 0
        );
    }

    public static DatabaseResources createDatabase(Path dbDir) {
        return createDatabase(dbDir, new DatabaseConfig(dbDir, 1, 600_000L, 1_800_000L));
    }

    public static DatabaseResources createDatabase(Path dbDir, DatabaseConfig dbConfig) {
        return DatabaseFactory.create(dbDir, "config", dbConfig);
    }
}
