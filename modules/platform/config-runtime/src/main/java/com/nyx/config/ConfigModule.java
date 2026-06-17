package com.nyx.config;

import java.util.concurrent.ConcurrentHashMap;

public final class ConfigModule {
    private ConfigModule() {}

    public static ConfigBindings createConfigBindings(
        ServerConfig config,
        ConcurrentHashMap<String, String> runtimeUsers
    ) {
        ConfigPersistenceResources resources = ConfigPersistenceResources.create(config);
        ConfigStore configStore = new ConfigRepository(resources.jdbi());
        return new ConfigBindings(
            resources,
            configStore,
            new ConfigService(config, configStore, runtimeUsers)
        );
    }
}
