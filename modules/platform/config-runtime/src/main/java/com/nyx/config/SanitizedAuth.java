package com.nyx.config;

import java.util.List;

public record SanitizedAuth(
    boolean enabled,
    boolean hasToken,
    boolean hasMultiToken,
    List<String> tokenUsers,
    List<String> users
) {
    public SanitizedAuth {
        tokenUsers = List.copyOf(tokenUsers);
        users = List.copyOf(users);
    }
}
