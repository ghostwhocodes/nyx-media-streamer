package com.nyx.http;

import java.util.function.Function;

public final class Parameters {
    private final Function<String, String> getter;

    public Parameters(Function<String, String> getter) {
        this.getter = getter;
    }

    public String get(String name) {
        return getter.apply(name);
    }
}
