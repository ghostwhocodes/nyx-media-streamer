package com.nyx.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public final class JsonObject extends JsonElement implements Map<String, JsonElement> {
    JsonObject(JsonNode node) {
        super(node);
    }

    @Override
    public int size() {
        return node.size();
    }

    @Override
    public boolean isEmpty() {
        return node.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String fieldName && node.has(fieldName);
    }

    @Override
    public boolean containsValue(Object value) {
        return values().contains(value);
    }

    @Override
    public @Nullable JsonElement get(Object key) {
        if (!(key instanceof String fieldName)) {
            return null;
        }
        JsonNode child = node.get(fieldName);
        return child == null ? null : new JsonElement(child);
    }

    @Override
    public JsonElement put(String key, JsonElement value) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public JsonElement remove(Object key) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public void putAll(Map<? extends String, ? extends JsonElement> map) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public Set<String> keySet() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public Collection<JsonElement> values() {
        ArrayList<JsonElement> values = new ArrayList<>(node.size());
        node.elements().forEachRemaining(element -> values.add(new JsonElement(element)));
        return Collections.unmodifiableList(values);
    }

    @Override
    public Set<Entry<String, JsonElement>> entrySet() {
        LinkedHashSet<Entry<String, JsonElement>> entries = new LinkedHashSet<>();
        node.fields().forEachRemaining(entry -> entries.add(Map.entry(entry.getKey(), new JsonElement(entry.getValue()))));
        return Collections.unmodifiableSet(entries);
    }

    public JsonElement getValue(String fieldName) {
        JsonElement value = get(fieldName);
        if (value == null) {
            throw new IllegalStateException("Missing JSON field: " + fieldName);
        }
        return value;
    }
}
