package com.nyx.http;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class OpenApiRouteConfig {
    private String description;
    private final RequestDoc requestDoc = new RequestDoc();
    private final List<ResponseDoc> responseDocs = new ArrayList<>();

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void request(Consumer<? super RequestDoc> block) {
        block.accept(requestDoc);
    }

    public void response(Consumer<? super ResponseCollection> block) {
        ResponseCollection collector = new ResponseCollection();
        block.accept(collector);
        responseDocs.addAll(collector.getResponses());
    }

    public OpenApiOperation toOperation(String method, String path) {
        List<OpenApiResponse> responses = responseDocs.stream()
            .map(response -> new OpenApiResponse(response.getCode(), response.getDescription()))
            .toList();
        return new OpenApiOperation(
            method,
            path,
            description,
            requestDoc.getParameters(),
            requestDoc.hasRequestBody(),
            responses
        );
    }
}
