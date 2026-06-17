package com.nyx.config;

public record ConfigBindings(
    ConfigPersistenceResources resources,
    ConfigStore configStore,
    ConfigService configService
) {}
