package com.nyx.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class OpenApiRegistry {
    private final List<OpenApiOperation> operations = new ArrayList<>();

    public void register(String method, String path, Consumer<? super OpenApiRouteConfig> documentation) {
        OpenApiRouteConfig config = new OpenApiRouteConfig();
        documentation.accept(config);
        operations.add(config.toOperation(method, path));
    }

    public Map<String, Object> buildSpec(String title, String version) {
        return buildSpec(title, version, null);
    }

    public Map<String, Object> buildSpec(String title, String version, String description) {
        Map<String, Map<String, Object>> paths = new LinkedHashMap<>();
        for (OpenApiOperation operation : operations) {
            Map<String, Object> pathEntry = paths.computeIfAbsent(operation.getPath(), ignored -> new LinkedHashMap<>());
            Map<String, Object> operationEntry = new LinkedHashMap<>();

            Map<String, Object> responses = new LinkedHashMap<>();
            for (OpenApiResponse response : operation.getResponses()) {
                responses.put(
                    Integer.toString(response.getCode()),
                    Map.of("description", response.getDescription() == null ? "" : response.getDescription())
                );
            }
            operationEntry.put("responses", responses);

            if (operation.getDescription() != null) {
                operationEntry.put("description", operation.getDescription());
            }
            if (!operation.getParameters().isEmpty()) {
                operationEntry.put(
                    "parameters",
                    operation.getParameters().stream()
                        .map(parameter -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("name", parameter.getName());
                            entry.put("in", parameter.getIn());
                            entry.put("required", parameter.getRequired());
                            if (parameter.getDescription() != null) {
                                entry.put("description", parameter.getDescription());
                            }
                            entry.put("schema", Map.of("type", "string"));
                            return entry;
                        })
                        .toList()
                );
            }
            if (operation.getHasRequestBody()) {
                operationEntry.put(
                    "requestBody",
                    Map.of(
                        "required", true,
                        "content", Map.of(
                            ContentType.Application.Json.getValue(),
                            Map.of("schema", Map.of("type", "object"))
                        )
                    )
                );
            }
            pathEntry.put(operation.getMethod().toLowerCase(java.util.Locale.ROOT), operationEntry);
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title);
        info.put("version", version);
        if (description != null) {
            info.put("description", description);
        }

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.1.0");
        spec.put("info", info);
        spec.put("paths", paths);
        return spec;
    }
}
