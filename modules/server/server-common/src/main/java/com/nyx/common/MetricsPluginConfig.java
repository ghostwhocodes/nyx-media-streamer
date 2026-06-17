package com.nyx.common;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

public final class MetricsPluginConfig {
    private PrometheusMeterRegistry registry;

    public PrometheusMeterRegistry getRegistry() {
        return registry;
    }

    public void setRegistry(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }
}
