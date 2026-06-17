package com.nyx.http;

import java.util.Objects;

public final class OpenApiParameter {
    private final String name;
    private final String inValue;
    private final String description;
    private final boolean required;

    public OpenApiParameter(String name, String inValue) {
        this(name, inValue, null, false);
    }

    public OpenApiParameter(String name, String inValue, String description) {
        this(name, inValue, description, false);
    }

    public OpenApiParameter(String name, String inValue, String description, boolean required) {
        this.name = name;
        this.inValue = inValue;
        this.description = description;
        this.required = required;
    }

    public String getName() {
        return name;
    }

    public String getIn() {
        return inValue;
    }

    public String getDescription() {
        return description;
    }

    public boolean getRequired() {
        return required;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OpenApiParameter that)) {
            return false;
        }
        return required == that.required
            && Objects.equals(name, that.name)
            && Objects.equals(inValue, that.inValue)
            && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, inValue, description, required);
    }

    @Override
    public String toString() {
        return "OpenApiParameter(name=" + name + ", in=" + inValue + ", description=" + description + ", required=" + required + ")";
    }
}
