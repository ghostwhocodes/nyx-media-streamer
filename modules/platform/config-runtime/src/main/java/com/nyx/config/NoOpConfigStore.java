package com.nyx.config;

import java.util.Map;

public enum NoOpConfigStore implements ConfigStore {
    INSTANCE;

    @Override
    public Map<String, String> getOverrides() {
        return Map.of();
    }

    @Override
    public void setOverride(String key, String value) {}

    @Override
    public Map<String, String> getAllUsers() {
        return Map.of();
    }

    @Override
    public void upsertUser(String username, String passwordHash) {}

    @Override
    public boolean deleteUser(String username) {
        return false;
    }
}
