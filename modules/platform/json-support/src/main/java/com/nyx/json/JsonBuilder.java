package com.nyx.json;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class JsonBuilder {
    private boolean ignoreUnknownKeys;
    private boolean encodeDefaults;
    private @Nullable String classDiscriminator;

    public boolean isIgnoreUnknownKeys() {
        return ignoreUnknownKeys;
    }

    public void setIgnoreUnknownKeys(boolean ignoreUnknownKeys) {
        this.ignoreUnknownKeys = ignoreUnknownKeys;
    }

    public boolean isEncodeDefaults() {
        return encodeDefaults;
    }

    public void setEncodeDefaults(boolean encodeDefaults) {
        this.encodeDefaults = encodeDefaults;
    }

    public @Nullable String getClassDiscriminator() {
        return classDiscriminator;
    }

    public void setClassDiscriminator(@Nullable String classDiscriminator) {
        this.classDiscriminator = classDiscriminator;
    }
}
