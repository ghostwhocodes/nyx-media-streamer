package com.nyx.json;

import com.fasterxml.jackson.databind.JsonNode;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class JsonElement {
    final JsonNode node;

    JsonElement(JsonNode node) {
        this.node = node;
    }

    public JsonObject getJsonObject() {
        return new JsonObject(requireObjectNode());
    }

    public JsonArray getJsonArray() {
        return new JsonArray(requireArrayNode());
    }

    public JsonPrimitive getJsonPrimitive() {
        return new JsonPrimitive(requirePrimitiveNode());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JsonElement element && node.equals(element.node);
    }

    @Override
    public int hashCode() {
        return node.hashCode();
    }

    @Override
    public String toString() {
        return node.toString();
    }

    private JsonNode requireObjectNode() {
        if (!node.isObject()) {
            throw new IllegalStateException("Expected JSON object but was " + node.getNodeType());
        }
        return node;
    }

    private JsonNode requireArrayNode() {
        if (!node.isArray()) {
            throw new IllegalStateException("Expected JSON array but was " + node.getNodeType());
        }
        return node;
    }

    private JsonNode requirePrimitiveNode() {
        if (!node.isValueNode() && !node.isNull()) {
            throw new IllegalStateException("Expected JSON primitive but was " + node.getNodeType());
        }
        return node;
    }
}
