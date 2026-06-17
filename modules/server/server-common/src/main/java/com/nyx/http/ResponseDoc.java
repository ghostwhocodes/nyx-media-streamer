package com.nyx.http;

import java.util.function.Consumer;

public final class ResponseDoc {
    private final int code;
    private String description;

    public ResponseDoc(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void body(Class<?> type) {
    }

    public void body(Class<?> type, Consumer<? super BodyDoc> block) {
        BodyDoc doc = new BodyDoc();
        block.accept(doc);
    }
}
