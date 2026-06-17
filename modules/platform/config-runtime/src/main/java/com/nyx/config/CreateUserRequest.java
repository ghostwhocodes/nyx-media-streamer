package com.nyx.config;

public record CreateUserRequest(
    String username,
    String password
) {}
