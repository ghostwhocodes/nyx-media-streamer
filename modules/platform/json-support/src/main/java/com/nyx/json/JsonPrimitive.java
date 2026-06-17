package com.nyx.json;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class JsonPrimitive extends JsonElement {
    JsonPrimitive(JsonNode node) {
        super(node);
    }

    public String getContent() {
        return node.asText();
    }

    public @Nullable String getContentOrNull() {
        return node.isNull() ? null : node.asText();
    }

    public int getInt() {
        return node.asInt();
    }

    public long getLong() {
        return node.asLong();
    }

    public double getDouble() {
        return node.asDouble();
    }

    public boolean getBoolean() {
        return node.asBoolean();
    }
}
