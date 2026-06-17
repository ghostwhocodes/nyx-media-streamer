package com.nyx.config;

import java.util.Map;

public record AuthConfig(
    boolean enabled,
    String token,
    Map<String, String> users,
    Map<String, String> tokens
) {
    public AuthConfig {
        token = token == null ? "" : token;
        users = users == null ? Map.of() : Map.copyOf(users);
        tokens = tokens == null ? Map.of() : Map.copyOf(tokens);
    }

    public AuthConfig() {
        this(false, "", Map.of(), Map.of());
    }

    public boolean getEnabled() {
        return enabled;
    }

    public String getToken() {
        return token;
    }

    public Map<String, String> getUsers() {
        return users;
    }

    public Map<String, String> getTokens() {
        return tokens;
    }
}
