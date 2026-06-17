package com.nyx.config;

import java.util.Map;

public interface ConfigStore {
    Map<String, String> getOverrides();

    void setOverride(String key, String value);

    Map<String, String> getAllUsers();

    void upsertUser(String username, String passwordHash);

    boolean deleteUser(String username);
}
