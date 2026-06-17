package com.nyx.http;

import java.util.Objects;

public final class UserIdPrincipal {
    private final String name;

    public UserIdPrincipal(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UserIdPrincipal that && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "UserIdPrincipal(name=" + name + ")";
    }
}
