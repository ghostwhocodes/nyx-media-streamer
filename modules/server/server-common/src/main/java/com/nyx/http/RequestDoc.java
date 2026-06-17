package com.nyx.http;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class RequestDoc {
    private final List<OpenApiParameter> parameters = new ArrayList<>();
    private boolean hasRequestBody;

    public void body(Class<?> type) {
        hasRequestBody = true;
    }

    public void body(Class<?> type, Consumer<? super BodyDoc> block) {
        BodyDoc doc = new BodyDoc();
        block.accept(doc);
        hasRequestBody = true;
    }

    public void queryParameter(String name) {
        queryParameter(name, noParameterDoc());
    }

    public void queryParameter(String name, Consumer<? super ParameterDoc> block) {
        ParameterDoc doc = new ParameterDoc();
        block.accept(doc);
        parameters.add(new OpenApiParameter(name, "query", doc.getDescription(), doc.getRequired()));
    }

    public void pathParameter(String name) {
        pathParameter(name, noParameterDoc());
    }

    public void pathParameter(String name, Consumer<? super ParameterDoc> block) {
        ParameterDoc doc = new ParameterDoc();
        doc.setRequired(true);
        block.accept(doc);
        parameters.add(new OpenApiParameter(name, "path", doc.getDescription(), true));
    }

    public void headerParameter(String name) {
        headerParameter(name, noParameterDoc());
    }

    public void headerParameter(String name, Consumer<? super ParameterDoc> block) {
        ParameterDoc doc = new ParameterDoc();
        block.accept(doc);
        parameters.add(new OpenApiParameter(name, "header", doc.getDescription(), doc.getRequired()));
    }

    List<OpenApiParameter> getParameters() {
        return List.copyOf(parameters);
    }

    boolean hasRequestBody() {
        return hasRequestBody;
    }

    private static Consumer<ParameterDoc> noParameterDoc() {
        return doc -> {
        };
    }
}
