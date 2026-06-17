package com.nyx.eforms;

import static com.nyx.common.RouteUtilsJava.getLimitParam;

import com.fasterxml.jackson.databind.JsonNode;
import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.eforms.model.FieldDefinition;
import com.nyx.eforms.model.FormDefinition;
import com.nyx.eforms.model.MediaMetadata;
import com.nyx.eforms.model.MediaType;
import com.nyx.eforms.model.SearchQuery;
import com.nyx.eforms.model.SearchResult;
import com.nyx.eforms.model.SearchResultItem;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.ParameterDoc;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class EFormRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_CREATED = HttpStatusCode.Companion.getCreated();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();
    private static final HttpStatusCode HTTP_BAD_REQUEST = HttpStatusCode.Companion.getBadRequest();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();

    private EFormRoutes() {
    }

    public static void eformRoutes(
        Route route,
        EFormService eformService,
        ExportImportService exportImportService,
        RelocationService relocationService,
        PathSecurity pathSecurity
    ) {
        eformRoutes(route, eformService, exportImportService, relocationService, pathSecurity, List.of(), null);
    }

    public static void eformRoutes(
        Route route,
        EFormService eformService,
        ExportImportService exportImportService,
        RelocationService relocationService,
        PathSecurity pathSecurity,
        List<String> authProviders,
        VirtualPathResolver virtualPathResolver
    ) {
        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/forms",
                doc(config -> {
                    config.setDescription("Create a new form definition");
                    config.request(requestDoc(request -> request.body(CreateFormRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_CREATED, bodyDoc(FormDefinition.class));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid form definition"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    CreateFormRequest request = call.receive(CreateFormRequest.class);
                    FormDefinition form = eformService.createForm(request.name(), request.mediaTypes(), request.fields());
                    call.respond(HTTP_CREATED, form);
                })
            );
        });

        route.get(
            "/api/v1/forms",
            doc(config -> {
                config.setDescription("List all form definitions");
                config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(List.class))));
            }),
            handler(scope -> scope.getCall().respond(eformService.listForms()))
        );

        route.get(
            "/api/v1/forms/export",
            doc(config -> {
                config.setDescription("Export all form definitions and metadata as a ZIP archive");
                config.response(responseDoc(response -> response.code(HTTP_OK, describe("ZIP archive of form definitions and metadata"))));
            }),
            handler(scope -> scope.getCall().respondBytes(exportImportService.export(), ContentType.Companion.parse("application/zip")))
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/forms/import",
                doc(config -> {
                    config.setDescription("Import form definitions and metadata from a ZIP archive");
                    config.request(requestDoc(request -> {
                        request.queryParameter("forms", parameterDoc(parameter -> {
                            parameter.setDescription("Comma-separated list of form names to import (all if omitted)");
                            parameter.setRequired(false);
                        }));
                        request.body(ByteArrayHolder.class);
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Import result summary"));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid ZIP content"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    byte[] zipBytes = call.receive(byte[].class);
                    String forms = call.getRequest().getQueryParameters().get("forms");
                    Set<String> formNames = forms == null
                        ? null
                        : forms.lines()
                            .flatMap(line -> java.util.Arrays.stream(line.split(",")))
                            .map(String::trim)
                            .filter(value -> !value.isEmpty())
                            .collect(java.util.stream.Collectors.toSet());
                    call.respond(exportImportService.importArchive(zipBytes, formNames));
                })
            );
        });

        route.get(
            "/api/v1/forms/{formId}",
            doc(config -> {
                config.setDescription("Get a form definition by ID");
                config.request(requestDoc(request -> request.pathParameter("formId", parameterDoc(parameter -> parameter.setDescription("Form definition ID")))));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(FormDefinition.class));
                    response.code(HTTP_NOT_FOUND, describe("Form not found"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String formId = requirePathParam(call, "formId");
                FormDefinition form = eformService.getForm(formId);
                if (form == null) {
                    throw nyxException(ErrorCode.FORM_NOT_FOUND, "Form not found: " + formId);
                }
                call.respond(form);
            })
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.put(
                "/api/v1/forms/{formId}",
                doc(config -> {
                    config.setDescription("Update a form definition (creates a new version)");
                    config.request(requestDoc(request -> {
                        request.pathParameter("formId", parameterDoc(parameter -> parameter.setDescription("Form definition ID")));
                        request.body(UpdateFormRequest.class);
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(UpdateFormResponse.class));
                        response.code(HTTP_NOT_FOUND, describe("Form not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String formId = requirePathParam(call, "formId");
                    UpdateFormRequest request = call.receive(UpdateFormRequest.class);
                    var update = eformService.updateForm(formId, request.fields());
                    call.respond(new UpdateFormResponse(update.getKey(), update.getValue()));
                })
            );

            authenticatedRoute.delete(
                "/api/v1/forms/{formId}",
                doc(config -> {
                    config.setDescription("Delete a form definition");
                    config.request(requestDoc(request -> {
                        request.pathParameter("formId", parameterDoc(parameter -> parameter.setDescription("Form definition ID")));
                        request.queryParameter("delete_metadata", parameterDoc(parameter -> {
                            parameter.setDescription("Also delete all associated metadata");
                            parameter.setRequired(false);
                        }));
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_NO_CONTENT, describe("Form deleted"));
                        response.code(HTTP_NOT_FOUND, describe("Form not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String formId = requirePathParam(call, "formId");
                    boolean deleteMetadata = Boolean.parseBoolean(call.getRequest().getQueryParameters().get("delete_metadata"));
                    eformService.deleteForm(formId, deleteMetadata);
                    call.respond(HTTP_NO_CONTENT);
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/metadata",
                doc(config -> {
                    config.setDescription("Attach metadata to a media file using a form definition");
                    config.request(requestDoc(request -> request.body(AttachMetadataRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_CREATED, bodyDoc(MediaMetadata.class));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid request or path"));
                        response.code(HTTP_NOT_FOUND, describe("Form not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    AttachMetadataRequest request = call.receive(AttachMetadataRequest.class);
                    String absolutePath = resolveMediaPath(request.mediaPath(), pathSecurity, virtualPathResolver);
                    MediaMetadata metadata = eformService.attachMetadata(absolutePath, request.formId(), request.values());
                    call.respond(HTTP_CREATED, metadata);
                })
            );
        });

        route.get(
            "/api/v1/metadata",
            doc(config -> {
                config.setDescription("Get all metadata attached to a media file by path");
                config.request(requestDoc(request -> request.queryParameter("path", parameterDoc(parameter -> {
                    parameter.setDescription("Virtual path of the media file");
                    parameter.setRequired(true);
                }))));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(List.class));
                    response.code(HTTP_BAD_REQUEST, describe("Missing path parameter"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String mediaPath = call.getRequest().getQueryParameters().get("path");
                if (mediaPath == null) {
                    throw nyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: path");
                }
                String absolutePath = resolveMediaPath(mediaPath, pathSecurity, virtualPathResolver);
                List<MediaMetadata> results = eformService.getMetadata(absolutePath);
                call.respond(virtualPathResolver == null ? results : results.stream().map(meta -> toVirtualMetadata(meta, virtualPathResolver)).toList());
            })
        );

        route.get(
            "/api/v1/metadata/{metadataId}",
            doc(config -> {
                config.setDescription("Get a metadata item by ID");
                config.request(requestDoc(request -> request.pathParameter("metadataId", parameterDoc(parameter -> parameter.setDescription("Metadata item ID")))));
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(MediaMetadata.class));
                    response.code(HTTP_NOT_FOUND, describe("Metadata not found"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String metadataId = requirePathParam(call, "metadataId");
                MediaMetadata meta = eformService.getMetadataById(metadataId);
                if (meta == null) {
                    throw nyxException(ErrorCode.METADATA_NOT_FOUND, "Metadata not found: " + metadataId);
                }
                call.respond(virtualPathResolver == null ? meta : toVirtualMetadata(meta, virtualPathResolver));
            })
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.put(
                "/api/v1/metadata/{metadataId}",
                doc(config -> {
                    config.setDescription("Update metadata values");
                    config.request(requestDoc(request -> {
                        request.pathParameter("metadataId", parameterDoc(parameter -> parameter.setDescription("Metadata item ID")));
                        request.body(UpdateMetadataRequest.class);
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(MediaMetadata.class));
                        response.code(HTTP_NOT_FOUND, describe("Metadata not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String metadataId = requirePathParam(call, "metadataId");
                    UpdateMetadataRequest request = call.receive(UpdateMetadataRequest.class);
                    call.respond(eformService.updateMetadata(metadataId, request.values()));
                })
            );

            authenticatedRoute.delete(
                "/api/v1/metadata/{metadataId}",
                doc(config -> {
                    config.setDescription("Delete a metadata item");
                    config.request(requestDoc(request -> request.pathParameter("metadataId", parameterDoc(parameter -> parameter.setDescription("Metadata item ID")))));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_NO_CONTENT, describe("Metadata deleted"));
                        response.code(HTTP_NOT_FOUND, describe("Metadata not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    eformService.deleteMetadata(requirePathParam(call, "metadataId"));
                    call.respond(HTTP_NO_CONTENT);
                })
            );
        });

        route.get(
            "/api/v1/search/meta",
            doc(config -> {
                config.setDescription("Search metadata using full-text search");
                config.request(requestDoc(request -> {
                    request.queryParameter("q", optionalParam("Full-text search query"));
                    request.queryParameter("form", optionalParam("Filter by form ID"));
                    request.queryParameter("type", optionalParam("Filter by media type (IMAGE, VIDEO, AUDIO)"));
                    request.queryParameter("sort", optionalParam("Sort field"));
                    request.queryParameter("limit", optionalParam("Max results"));
                    request.queryParameter("offset", optionalParam("Result offset for pagination"));
                }));
                config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(SearchResult.class))));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String type = call.getRequest().getQueryParameters().get("type");
                SearchQuery query = new SearchQuery(
                    call.getRequest().getQueryParameters().get("q"),
                    Map.of(),
                    call.getRequest().getQueryParameters().get("form"),
                    type == null ? null : MediaType.valueOf(type.toUpperCase()),
                    call.getRequest().getQueryParameters().get("sort"),
                    getLimitParam(call, 50, 200),
                    parseInteger(call.getRequest().getQueryParameters().get("offset"), 0)
                );
                SearchResult result = eformService.search(query);
                if (virtualPathResolver == null) {
                    call.respond(result);
                    return;
                }
                call.respond(new SearchResult(
                    result.results().stream().map(item -> {
                        String virtualPath = virtualPathResolver.toVirtualPath(Path.of(item.mediaPath()));
                        return virtualPath == null
                            ? item
                            : new SearchResultItem(
                                virtualPath,
                                item.mediaType(),
                                item.formId(),
                                item.formVersion(),
                                item.metadata(),
                                item.relevance()
                            );
                    }).toList(),
                    result.total(),
                    result.limit(),
                    result.offset()
                ));
            })
        );

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/metadata/relocate",
                doc(config -> {
                    config.setDescription("Relocate metadata when a media file is moved");
                    config.request(requestDoc(request -> request.body(RelocateRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Number of updated metadata records"));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid path"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    RelocateRequest request = call.receive(RelocateRequest.class);
                    String fromPath = resolveMediaPath(request.from(), pathSecurity, virtualPathResolver);
                    String toPath = resolveMediaPath(request.to(), pathSecurity, virtualPathResolver);
                    call.respond(Map.of("updated", relocationService.relocate(fromPath, toPath)));
                })
            );

            authenticatedRoute.post(
                "/api/v1/metadata/relocate/batch",
                doc(config -> {
                    config.setDescription("Batch relocate metadata using path prefix patterns");
                    config.request(requestDoc(request -> {
                        request.queryParameter("dry_run", optionalParam("If true, report changes without applying them"));
                        request.body(BatchRelocateRequest.class);
                    }));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, describe("Batch relocation result"));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid path pattern"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    BatchRelocateRequest request = call.receive(BatchRelocateRequest.class);
                    boolean dryRun = Boolean.parseBoolean(call.getRequest().getQueryParameters().get("dry_run"));
                    String fromPattern = resolveCanonicalBatchPattern(request.fromPattern(), pathSecurity, virtualPathResolver);
                    String toPattern = resolveCanonicalBatchPattern(request.toPattern(), pathSecurity, virtualPathResolver);
                    call.respond(relocationService.relocateBatch(fromPattern, toPattern, dryRun));
                })
            );
        });

        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/metadata/hash",
                doc(config -> {
                    config.setDescription("Compute and store content hash for a media file");
                    config.request(requestDoc(request -> request.body(HashRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(HashResponse.class));
                        response.code(HTTP_BAD_REQUEST, describe("Invalid path"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    HashRequest request = call.receive(HashRequest.class);
                    Path canonicalPath = resolveCanonicalMediaPath(request.path(), pathSecurity, virtualPathResolver);
                    String hash = RelocationService.computeContentHash(canonicalPath);
                    call.respond(new HashResponse(hash, eformService.storeContentHash(canonicalPath.toString(), hash)));
                })
            );
        });
    }

    private static String requirePathParam(RoutingCall call, String name) {
        String value = call.getParameters().get(name);
        if (value == null) {
            throw nyxException(ErrorCode.INVALID_REQUEST, "Missing " + name);
        }
        return value;
    }

    private static Path resolveCanonicalMediaPath(
        String mediaPath,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        if (virtualPathResolver != null) {
            return pathSecurity.validate(virtualPathResolver.resolveToAbsolute(mediaPath).toString());
        }
        return pathSecurity.validate(mediaPath);
    }

    private static String resolveMediaPath(
        String mediaPath,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        return resolveCanonicalMediaPath(mediaPath, pathSecurity, virtualPathResolver).toString();
    }

    private static String resolveCanonicalBatchPattern(
        String pattern,
        PathSecurity pathSecurity,
        VirtualPathResolver virtualPathResolver
    ) {
        boolean wildcard = pattern.endsWith("*");
        String base = wildcard ? pattern.substring(0, pattern.length() - 1) : pattern;
        String canonicalBase = resolveCanonicalMediaPath(base, pathSecurity, virtualPathResolver).toString();
        return wildcard ? canonicalBase + "*" : canonicalBase;
    }

    private static MediaMetadata toVirtualMetadata(MediaMetadata metadata, VirtualPathResolver virtualPathResolver) {
        String virtualPath = virtualPathResolver.toVirtualPath(Path.of(metadata.mediaPath()));
        if (virtualPath == null) {
            return metadata;
        }
        return new MediaMetadata(
            metadata.id(),
            virtualPath,
            metadata.contentHash(),
            metadata.formId(),
            metadata.formVersion(),
            metadata.values(),
            metadata.createdAt(),
            metadata.updatedAt()
        );
    }

    private static int parseInteger(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.OPTIONAL, authProviders));
    }

    private static Consumer<OpenApiRouteConfig> doc(RouteDoc doc) {
        return doc::accept;
    }

    private static Consumer<com.nyx.http.RequestDoc> requestDoc(RequestDocBlock block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.ResponseCollection> responseDoc(ResponseDocBlock block) {
        return block::accept;
    }

    private static Consumer<ParameterDoc> parameterDoc(ParameterDocBlock block) {
        return block::accept;
    }

    private static Consumer<RouteHandlerScope> handler(RouteHandler handler) {
        return handler::accept;
    }

    private static Consumer<com.nyx.http.ResponseDoc> bodyDoc(Class<?> type) {
        return response -> response.body(type);
    }

    private static Consumer<com.nyx.http.ResponseDoc> describe(String description) {
        return response -> response.setDescription(description);
    }

    private static Consumer<ParameterDoc> optionalParam(String description) {
        return parameter -> {
            parameter.setDescription(description);
            parameter.setRequired(false);
        };
    }

    private static RuntimeException nyxException(ErrorCode code, String message) {
        return sneakyThrow(new NyxException(code, message, Map.of(), null));
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable, T> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    @FunctionalInterface
    private interface RouteRegistrar {
        void accept(Route route);
    }

    @FunctionalInterface
    private interface RouteDoc {
        void accept(OpenApiRouteConfig config);
    }

    @FunctionalInterface
    private interface RequestDocBlock {
        void accept(com.nyx.http.RequestDoc request);
    }

    @FunctionalInterface
    private interface ResponseDocBlock {
        void accept(com.nyx.http.ResponseCollection response);
    }

    @FunctionalInterface
    private interface ParameterDocBlock {
        void accept(ParameterDoc parameter);
    }

    @FunctionalInterface
    private interface RouteHandler {
        void accept(RouteHandlerScope scope);
    }

    private static final class ByteArrayHolder {
        private ByteArrayHolder() {
        }
    }
}

record CreateFormRequest(
    String name,
    Set<MediaType> mediaTypes,
    List<FieldDefinition> fields
) {
}

record UpdateFormRequest(
    List<FieldDefinition> fields
) {
}

record UpdateFormResponse(
    FormDefinition form,
    VersionDiff diff
) {
}

record AttachMetadataRequest(
    String mediaPath,
    String formId,
    Map<String, JsonNode> values
) {
}

record UpdateMetadataRequest(
    Map<String, JsonNode> values
) {
}

record RelocateRequest(
    String from,
    String to
) {
}

record BatchRelocateRequest(
    String fromPattern,
    String toPattern
) {
}

record HashRequest(
    String path
) {
}

record HashResponse(
    String hash,
    int updatedCount
) {
}
