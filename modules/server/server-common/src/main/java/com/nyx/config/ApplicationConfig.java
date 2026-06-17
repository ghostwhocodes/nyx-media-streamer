package com.nyx.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ApplicationConfig {
    private final Config delegate;

    public ApplicationConfig(Config delegate) {
        this.delegate = delegate;
    }

    public ApplicationConfig(String resourceName) {
        this(loadResource(resourceName));
    }

    public ApplicationProperty property(String key) {
        return new ApplicationProperty(delegate.getAnyRef(key));
    }

    public ApplicationProperty propertyOrNull(String key) {
        return delegate.hasPath(key) ? new ApplicationProperty(delegate.getAnyRef(key)) : null;
    }

    public ApplicationConfig config(String section) {
        return new ApplicationConfig(delegate.getConfig(section));
    }

    public List<ApplicationConfig> configList(String section) {
        List<? extends Config> configs = delegate.getConfigList(section);
        List<ApplicationConfig> wrapped = new ArrayList<>(configs.size());
        for (Config config : configs) {
            wrapped.add(new ApplicationConfig(config));
        }
        return wrapped;
    }

    public Set<String> keys() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        delegate.entrySet().forEach(entry -> keys.add(entry.getKey()));
        return keys;
    }

    public ServerConfig toServerConfig() {
        return ServerConfigLoader.toServerConfig(this);
    }

    private static Config loadResource(String resourceName) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ApplicationConfig.class.getClassLoader();
        }
        if (loader.getResource(resourceName) == null) {
            throw new IllegalArgumentException("Config resource not found: " + resourceName);
        }
        return ConfigFactory.parseResources(loader, resourceName).resolve();
    }
}

final class ApplicationProperty {
    private final Object value;

    ApplicationProperty(Object value) {
        this.value = value;
    }

    public String getString() {
        if (value == null) {
            throw new IllegalStateException("Config value is null");
        }
        return value.toString();
    }

    public List<String> getList() {
        if (value instanceof Iterable<?> iterable) {
            List<String> items = new ArrayList<>();
            for (Object item : iterable) {
                items.add(item == null ? "" : item.toString());
            }
            return items;
        }
        if (value instanceof ConfigObject configObject) {
            List<String> items = new ArrayList<>();
            configObject.values().forEach(configValue -> items.add(configValue.unwrapped().toString()));
            return items;
        }
        throw new IllegalStateException("Config value is not a list: " + value);
    }
}
