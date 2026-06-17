package com.nyx.json;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class JsonArray extends JsonElement implements List<JsonElement> {
    JsonArray(JsonNode node) {
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
    public boolean contains(Object element) {
        return asList().contains(element);
    }

    @Override
    public Iterator<JsonElement> iterator() {
        return asList().iterator();
    }

    @Override
    public Object[] toArray() {
        return asList().toArray();
    }

    @Override
    public <T> T[] toArray(T[] array) {
        return asList().toArray(array);
    }

    @Override
    public boolean add(JsonElement jsonElement) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public boolean remove(Object object) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public boolean containsAll(Collection<?> objects) {
        return asList().containsAll(objects);
    }

    @Override
    public boolean addAll(Collection<? extends JsonElement> collection) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public boolean addAll(int index, Collection<? extends JsonElement> collection) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public JsonElement get(int index) {
        JsonNode child = node.get(index);
        if (child == null) {
            throw new IllegalStateException("Missing JSON element at index: " + index);
        }
        return new JsonElement(child);
    }

    @Override
    public JsonElement set(int index, JsonElement element) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public void add(int index, JsonElement element) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public JsonElement remove(int index) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int indexOf(Object object) {
        return asList().indexOf(object);
    }

    @Override
    public int lastIndexOf(Object object) {
        return asList().lastIndexOf(object);
    }

    @Override
    public ListIterator<JsonElement> listIterator() {
        return asList().listIterator();
    }

    @Override
    public ListIterator<JsonElement> listIterator(int index) {
        return asList().listIterator(index);
    }

    @Override
    public List<JsonElement> subList(int fromIndex, int toIndex) {
        return asList().subList(fromIndex, toIndex);
    }

    private List<JsonElement> asList() {
        ArrayList<JsonElement> elements = new ArrayList<>(node.size());
        node.elements().forEachRemaining(element -> elements.add(new JsonElement(element)));
        return Collections.unmodifiableList(elements);
    }
}
