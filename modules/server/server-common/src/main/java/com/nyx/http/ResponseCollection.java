package com.nyx.http;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class ResponseCollection {
    private final List<ResponseDoc> responses = new ArrayList<>();

    public void code(HttpStatusCode status) {
        code(status, noResponseDoc());
    }

    public void code(HttpStatusCode status, Consumer<? super ResponseDoc> block) {
        ResponseDoc doc = new ResponseDoc(status.getValue());
        block.accept(doc);
        responses.add(doc);
    }

    public List<ResponseDoc> getResponses() {
        return List.copyOf(responses);
    }

    private static Consumer<ResponseDoc> noResponseDoc() {
        return doc -> {
        };
    }
}
