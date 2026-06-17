package com.nyx.media;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.common.RouteUtilsJava;
import com.nyx.http.AuthMode;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.ParameterDoc;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.http.UserIdPrincipal;
import com.nyx.media.contracts.CreateLibraryCollectionRequest;
import com.nyx.media.contracts.CreateLibraryRequest;
import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryCollection;
import com.nyx.media.contracts.LibraryCollectionListing;
import com.nyx.media.contracts.LibraryItem;
import com.nyx.media.contracts.LibraryItemListing;
import com.nyx.media.contracts.LibraryItemUserStateEntry;
import com.nyx.media.contracts.LibraryItemUserStateListing;
import com.nyx.media.contracts.ReplaceLibraryItemArtworkRequest;
import com.nyx.media.contracts.ReplaceLibraryItemMetadataRequest;
import com.nyx.media.contracts.UpdateLibraryCollectionRequest;
import com.nyx.media.contracts.UpdateLibraryRequest;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class LibraryRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_CREATED = HttpStatusCode.Companion.getCreated();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();
    private static final HttpStatusCode HTTP_CONFLICT = HttpStatusCode.Companion.getConflict();

    private LibraryRoutes() {
    }

    public static void libraryRoutes(Route route, LibraryService libraryService) {
        libraryRoutes(route, libraryService, null, null, List.of());
    }

    public static void libraryRoutes(
        Route route,
        LibraryService libraryService,
        LibraryCatalogService libraryCatalogService
    ) {
        libraryRoutes(route, libraryService, libraryCatalogService, null, List.of());
    }

    public static void libraryRoutes(
        Route route,
        LibraryService libraryService,
        LibraryCatalogService libraryCatalogService,
        LibraryUserStateService libraryUserStateService
    ) {
        libraryRoutes(route, libraryService, libraryCatalogService, libraryUserStateService, List.of());
    }

    public static void libraryRoutes(
        Route route,
        LibraryService libraryService,
        LibraryCatalogService libraryCatalogService,
        LibraryUserStateService libraryUserStateService,
        List<String> authProviders
    ) {
        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.post(
                "/api/v1/libraries",
                doc(config -> {
                    config.setDescription("Create a new library definition");
                    config.request(requestDoc(request -> request.body(CreateLibraryRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_CREATED, bodyDoc(Library.class));
                        response.code(HttpStatusCode.Companion.getBadRequest(), describe("Invalid library request"));
                        response.code(HTTP_CONFLICT, describe("Source root already assigned to another library"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    CreateLibraryRequest request = call.receive(CreateLibraryRequest.class);
                    call.respond(HTTP_CREATED, libraryService.createLibrary(request));
                })
            );
        });

        route.get(
            "/api/v1/libraries",
            doc(config -> {
                config.setDescription("List all configured libraries");
                config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(List.class))));
            }),
            handler(scope -> {
                scope.getCall().respond(libraryService.listLibraries());
            })
        );

        route.get(
            "/api/v1/libraries/{libraryId}",
            doc(config -> {
                config.setDescription("Get a library by ID");
                config.response(responseDoc(response -> {
                    response.code(HTTP_OK, bodyDoc(Library.class));
                    response.code(HTTP_NOT_FOUND, describe("Library not found"));
                }));
            }),
            handler(scope -> {
                RoutingCall call = scope.getCall();
                String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                Library library = libraryService.getLibrary(libraryId);
                if (library == null) {
                    throw nyxException(ErrorCode.LIBRARY_NOT_FOUND, "Library not found: " + libraryId);
                }
                call.respond(library);
            })
        );

        if (libraryCatalogService != null) {
            route.get(
                "/api/v1/libraries/{libraryId}/items",
                doc(config -> {
                    config.setDescription("List typed library items for a library");
                    config.request(requestDoc(request -> request.queryParameter("parentItemId", optionalParam(
                        "Optional parent item identifier to list children"
                    ))));
                    config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(LibraryItemListing.class))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                    call.respond(libraryCatalogService.listLibraryItems(libraryId, call.getQueryParameters().get("parentItemId")));
                })
            );

            route.get(
                "/api/v1/libraries/{libraryId}/items/{itemId}",
                doc(config -> {
                    config.setDescription("Get a typed library item by ID");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(LibraryItem.class));
                        response.code(HTTP_NOT_FOUND, describe("Library item not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                    String itemId = RouteUtilsJava.getRequiredPathParam(call, "itemId");
                    LibraryItem item = libraryCatalogService.getLibraryItem(libraryId, itemId);
                    if (item == null) {
                        throw nyxException(ErrorCode.LIBRARY_ITEM_NOT_FOUND, "Library item not found: " + itemId);
                    }
                    call.respond(item);
                })
            );

            route.get(
                "/api/v1/libraries/{libraryId}/collections",
                doc(config -> {
                    config.setDescription("List manual library collections");
                    config.response(responseDoc(response -> response.code(HTTP_OK, bodyDoc(LibraryCollectionListing.class))));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    call.respond(libraryCatalogService.listCollections(RouteUtilsJava.getRequiredPathParam(call, "libraryId")));
                })
            );

            route.get(
                "/api/v1/libraries/{libraryId}/collections/{collectionId}",
                doc(config -> {
                    config.setDescription("Get a manual library collection by ID");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(LibraryCollection.class));
                        response.code(HTTP_NOT_FOUND, describe("Library collection not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                    String collectionId = RouteUtilsJava.getRequiredPathParam(call, "collectionId");
                    LibraryCollection collection = libraryCatalogService.getCollection(libraryId, collectionId);
                    if (collection == null) {
                        throw nyxException(
                            ErrorCode.LIBRARY_COLLECTION_NOT_FOUND,
                            "Library collection not found: " + collectionId
                        );
                    }
                    call.respond(collection);
                })
            );
        }

        if (libraryUserStateService != null && !authProviders.isEmpty()) {
            requireAuth(route, authProviders, authenticatedRoute -> {
                authenticatedRoute.get(
                    "/api/v1/libraries/{libraryId}/state/items",
                    doc(config -> {
                        config.setDescription("List library items with aggregated user-state summaries for the current user");
                        config.request(requestDoc(request -> {
                            request.queryParameter("parentItemId", optionalParam(
                                "Optional parent item identifier to list children"
                            ));
                            request.queryParameter("page", optionalParam("Page number"));
                            request.queryParameter("limit", optionalParam("Page size"));
                        }));
                        config.response(responseDoc(response -> response.code(
                            HTTP_OK,
                            bodyDoc(LibraryItemUserStateListing.class)
                        )));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                        call.respond(
                            libraryUserStateService.listItemStates(
                                libraryId,
                                currentAuthenticatedLibraryUserId(call),
                                call.getQueryParameters().get("parentItemId"),
                                RouteUtilsJava.getPageParam(call, 1),
                                RouteUtilsJava.getLimitParam(call, 50, 200)
                            )
                        );
                    })
                );

                authenticatedRoute.get(
                    "/api/v1/libraries/{libraryId}/items/{itemId}/state",
                    doc(config -> {
                        config.setDescription("Get an aggregated library-item state summary for the current user");
                        config.response(responseDoc(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryItemUserStateEntry.class));
                            response.code(HTTP_NOT_FOUND, describe("Library item not found"));
                        }));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                        String itemId = RouteUtilsJava.getRequiredPathParam(call, "itemId");
                        LibraryItemUserStateEntry entry = libraryUserStateService.getItemState(
                            libraryId,
                            currentAuthenticatedLibraryUserId(call),
                            itemId
                        );
                        if (entry == null) {
                            throw nyxException(ErrorCode.LIBRARY_ITEM_NOT_FOUND, "Library item not found: " + itemId);
                        }
                        call.respond(entry);
                    })
                );

                authenticatedRoute.get(
                    "/api/v1/libraries/{libraryId}/state/favorites",
                    doc(config -> {
                        config.setDescription("List favorite library items for the current user within this library");
                        config.request(requestDoc(request -> {
                            request.queryParameter("page", optionalParam("Page number"));
                            request.queryParameter("limit", optionalParam("Page size"));
                        }));
                        config.response(responseDoc(response -> response.code(
                            HTTP_OK,
                            bodyDoc(LibraryItemUserStateListing.class)
                        )));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        call.respond(
                            libraryUserStateService.listFavorites(
                                RouteUtilsJava.getRequiredPathParam(call, "libraryId"),
                                currentAuthenticatedLibraryUserId(call),
                                RouteUtilsJava.getPageParam(call, 1),
                                RouteUtilsJava.getLimitParam(call, 50, 200)
                            )
                        );
                    })
                );

                authenticatedRoute.get(
                    "/api/v1/libraries/{libraryId}/state/watched",
                    doc(config -> {
                        config.setDescription("List watched library items for the current user within this library");
                        config.request(requestDoc(request -> {
                            request.queryParameter("page", optionalParam("Page number"));
                            request.queryParameter("limit", optionalParam("Page size"));
                        }));
                        config.response(responseDoc(response -> response.code(
                            HTTP_OK,
                            bodyDoc(LibraryItemUserStateListing.class)
                        )));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        call.respond(
                            libraryUserStateService.listWatched(
                                RouteUtilsJava.getRequiredPathParam(call, "libraryId"),
                                currentAuthenticatedLibraryUserId(call),
                                RouteUtilsJava.getPageParam(call, 1),
                                RouteUtilsJava.getLimitParam(call, 50, 200)
                            )
                        );
                    })
                );

                authenticatedRoute.get(
                    "/api/v1/libraries/{libraryId}/state/resume",
                    doc(config -> {
                        config.setDescription("List resume-ready library items for the current user within this library");
                        config.request(requestDoc(request -> {
                            request.queryParameter("page", optionalParam("Page number"));
                            request.queryParameter("limit", optionalParam("Page size"));
                        }));
                        config.response(responseDoc(response -> response.code(
                            HTTP_OK,
                            bodyDoc(LibraryItemUserStateListing.class)
                        )));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        call.respond(
                            libraryUserStateService.listResume(
                                RouteUtilsJava.getRequiredPathParam(call, "libraryId"),
                                currentAuthenticatedLibraryUserId(call),
                                RouteUtilsJava.getPageParam(call, 1),
                                RouteUtilsJava.getLimitParam(call, 50, 200)
                            )
                        );
                    })
                );

                authenticatedRoute.get(
                    "/api/v1/libraries/{libraryId}/state/continue-watching",
                    doc(config -> {
                        config.setDescription(
                            "List continue-watching library items for the current user within this library"
                        );
                        config.request(requestDoc(request -> {
                            request.queryParameter("page", optionalParam("Page number"));
                            request.queryParameter("limit", optionalParam("Page size"));
                        }));
                        config.response(responseDoc(response -> response.code(
                            HTTP_OK,
                            bodyDoc(LibraryItemUserStateListing.class)
                        )));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        call.respond(
                            libraryUserStateService.listContinueWatching(
                                RouteUtilsJava.getRequiredPathParam(call, "libraryId"),
                                currentAuthenticatedLibraryUserId(call),
                                RouteUtilsJava.getPageParam(call, 1),
                                RouteUtilsJava.getLimitParam(call, 50, 200)
                            )
                        );
                    })
                );
            });
        }

        optionalAuth(route, authProviders, authenticatedRoute -> {
            if (libraryCatalogService != null) {
                authenticatedRoute.put(
                    "/api/v1/libraries/{libraryId}/items/{itemId}/metadata",
                    doc(config -> {
                        config.setDescription("Replace manual local metadata for a library item");
                        config.request(requestDoc(request -> request.body(ReplaceLibraryItemMetadataRequest.class)));
                        config.response(responseDoc(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryItem.class));
                            response.code(HTTP_NOT_FOUND, describe("Library item not found"));
                        }));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                        String itemId = RouteUtilsJava.getRequiredPathParam(call, "itemId");
                        ReplaceLibraryItemMetadataRequest request = call.receive(ReplaceLibraryItemMetadataRequest.class);
                        call.respond(libraryCatalogService.replaceManualMetadata(libraryId, itemId, request));
                    })
                );

                authenticatedRoute.delete(
                    "/api/v1/libraries/{libraryId}/items/{itemId}/metadata",
                    doc(config -> {
                        config.setDescription("Clear manual local metadata overrides for a library item");
                        config.response(responseDoc(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryItem.class));
                            response.code(HTTP_NOT_FOUND, describe("Library item not found"));
                        }));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        call.respond(
                            libraryCatalogService.clearManualMetadata(
                                RouteUtilsJava.getRequiredPathParam(call, "libraryId"),
                                RouteUtilsJava.getRequiredPathParam(call, "itemId")
                            )
                        );
                    })
                );

                authenticatedRoute.put(
                    "/api/v1/libraries/{libraryId}/items/{itemId}/artwork",
                    doc(config -> {
                        config.setDescription("Replace manual artwork assignments for a library item");
                        config.request(requestDoc(request -> request.body(ReplaceLibraryItemArtworkRequest.class)));
                        config.response(responseDoc(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryItem.class));
                            response.code(HTTP_NOT_FOUND, describe("Library item not found"));
                        }));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                        String itemId = RouteUtilsJava.getRequiredPathParam(call, "itemId");
                        ReplaceLibraryItemArtworkRequest request = call.receive(ReplaceLibraryItemArtworkRequest.class);
                        call.respond(libraryCatalogService.replaceManualArtwork(libraryId, itemId, request));
                    })
                );

                authenticatedRoute.delete(
                    "/api/v1/libraries/{libraryId}/items/{itemId}/artwork",
                    doc(config -> {
                        config.setDescription("Clear manual artwork assignments for a library item");
                        config.response(responseDoc(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryItem.class));
                            response.code(HTTP_NOT_FOUND, describe("Library item not found"));
                        }));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        call.respond(
                            libraryCatalogService.clearManualArtwork(
                                RouteUtilsJava.getRequiredPathParam(call, "libraryId"),
                                RouteUtilsJava.getRequiredPathParam(call, "itemId")
                            )
                        );
                    })
                );

                authenticatedRoute.post(
                    "/api/v1/libraries/{libraryId}/collections",
                    doc(config -> {
                        config.setDescription("Create a manual library collection");
                        config.request(requestDoc(request -> request.body(CreateLibraryCollectionRequest.class)));
                        config.response(responseDoc(response -> {
                            response.code(HTTP_CREATED, bodyDoc(LibraryCollection.class));
                            response.code(HTTP_NOT_FOUND, describe("Library or library item not found"));
                        }));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        CreateLibraryCollectionRequest request = call.receive(CreateLibraryCollectionRequest.class);
                        call.respond(
                            HTTP_CREATED,
                            libraryCatalogService.createCollection(
                                RouteUtilsJava.getRequiredPathParam(call, "libraryId"),
                                request
                            )
                        );
                    })
                );

                authenticatedRoute.put(
                    "/api/v1/libraries/{libraryId}/collections/{collectionId}",
                    doc(config -> {
                        config.setDescription("Update a manual library collection");
                        config.request(requestDoc(request -> request.body(UpdateLibraryCollectionRequest.class)));
                        config.response(responseDoc(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryCollection.class));
                            response.code(HTTP_NOT_FOUND, describe("Library collection not found"));
                        }));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        String libraryId = RouteUtilsJava.getRequiredPathParam(call, "libraryId");
                        String collectionId = RouteUtilsJava.getRequiredPathParam(call, "collectionId");
                        UpdateLibraryCollectionRequest request = call.receive(UpdateLibraryCollectionRequest.class);
                        call.respond(libraryCatalogService.updateCollection(libraryId, collectionId, request));
                    })
                );

                authenticatedRoute.delete(
                    "/api/v1/libraries/{libraryId}/collections/{collectionId}",
                    doc(config -> {
                        config.setDescription("Delete a manual library collection");
                        config.response(responseDoc(response -> {
                            response.code(HTTP_NO_CONTENT, describe("Library collection deleted"));
                            response.code(HTTP_NOT_FOUND, describe("Library collection not found"));
                        }));
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        libraryCatalogService.deleteCollection(
                            RouteUtilsJava.getRequiredPathParam(call, "libraryId"),
                            RouteUtilsJava.getRequiredPathParam(call, "collectionId")
                        );
                        call.respond(HTTP_NO_CONTENT);
                    })
                );
            }

            authenticatedRoute.put(
                "/api/v1/libraries/{libraryId}",
                doc(config -> {
                    config.setDescription("Update a library definition");
                    config.request(requestDoc(request -> request.body(UpdateLibraryRequest.class)));
                    config.response(responseDoc(response -> {
                        response.code(HTTP_OK, bodyDoc(Library.class));
                        response.code(HTTP_NOT_FOUND, describe("Library not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    UpdateLibraryRequest request = call.receive(UpdateLibraryRequest.class);
                    call.respond(
                        libraryService.updateLibrary(RouteUtilsJava.getRequiredPathParam(call, "libraryId"), request)
                    );
                })
            );

            authenticatedRoute.delete(
                "/api/v1/libraries/{libraryId}",
                doc(config -> {
                    config.setDescription("Delete a library definition");
                    config.response(responseDoc(response -> {
                        response.code(HTTP_NO_CONTENT, describe("Library deleted"));
                        response.code(HTTP_NOT_FOUND, describe("Library not found"));
                    }));
                }),
                handler(scope -> {
                    RoutingCall call = scope.getCall();
                    libraryService.deleteLibrary(RouteUtilsJava.getRequiredPathParam(call, "libraryId"));
                    call.respond(HTTP_NO_CONTENT);
                })
            );
        });
    }

    private static String currentAuthenticatedLibraryUserId(RoutingCall call) {
        UserIdPrincipal principal = call.principal(UserIdPrincipal.class);
        if (principal == null) {
            throw new IllegalStateException("Library state routes require an authenticated principal");
        }
        return principal.getName();
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.OPTIONAL, authProviders));
    }

    private static void requireAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.REQUIRED, authProviders));
    }

    private static Consumer<OpenApiRouteConfig> doc(RouteDoc block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.RequestDoc> requestDoc(RequestDocBlock block) {
        return block::accept;
    }

    private static Consumer<com.nyx.http.ResponseCollection> responseDoc(ResponseDocBlock block) {
        return block::accept;
    }

    private static Consumer<ParameterDoc> optionalParam(String description) {
        return parameter -> {
            parameter.setDescription(description);
            parameter.setRequired(false);
        };
    }

    private static Consumer<com.nyx.http.ResponseDoc> bodyDoc(Class<?> type) {
        return response -> response.body(type);
    }

    private static Consumer<com.nyx.http.ResponseDoc> describe(String description) {
        return response -> response.setDescription(description);
    }

    private static Consumer<RouteHandlerScope> handler(RouteHandler handler) {
        return handler::accept;
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
    private interface RouteHandler {
        void accept(RouteHandlerScope scope);
    }
}
