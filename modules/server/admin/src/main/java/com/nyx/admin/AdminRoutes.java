package com.nyx.admin;

import static com.nyx.common.RouteUtilsJava.getLimitParam;
import static com.nyx.common.RouteUtilsJava.getRequiredPathParam;

import com.nyx.common.ErrorCode;
import com.nyx.common.ErrorDetail;
import com.nyx.common.ErrorResponse;
import com.nyx.common.NyxException;
import com.nyx.common.QuotaService;
import com.nyx.common.QuotaUsage;
import com.nyx.common.VirtualPathResolver;
import com.nyx.http.AuthMode;
import com.nyx.http.ContentType;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.OpenApiRouteConfig;
import com.nyx.http.Route;
import com.nyx.http.RouteHandlerScope;
import com.nyx.http.RoutingCall;
import com.nyx.media.LibraryAdminDiagnostics;
import com.nyx.media.LibraryAdminService;
import com.nyx.media.LibraryRepairResult;
import com.nyx.media.LibraryScanRequest;
import com.nyx.media.LibraryScanRun;
import com.nyx.media.LibraryScanService;
import com.nyx.media.ThumbnailService;
import com.nyx.transcode.contracts.SegmentCacheService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import java.util.function.Consumer;

public final class AdminRoutes {
    private static final HttpStatusCode HTTP_OK = HttpStatusCode.Companion.getOK();
    private static final HttpStatusCode HTTP_ACCEPTED = HttpStatusCode.Companion.getAccepted();
    private static final HttpStatusCode HTTP_CREATED = HttpStatusCode.Companion.getCreated();
    private static final HttpStatusCode HTTP_NO_CONTENT = HttpStatusCode.Companion.getNoContent();
    private static final HttpStatusCode HTTP_CONFLICT = HttpStatusCode.Companion.getConflict();
    private static final HttpStatusCode HTTP_NOT_FOUND = HttpStatusCode.Companion.getNotFound();

    private AdminRoutes() {
    }

    public static void adminRoutes(
        Route route,
        ThumbnailService thumbnailService,
        SegmentCacheService segmentCache,
        Map<String, DataSource> databases,
        List<Path> mediaRoots
    ) {
        adminRoutes(route, thumbnailService, segmentCache, databases, mediaRoots, null, null, null, null, null, null, List.of(), "local");
    }

    public static void adminRoutes(
        Route route,
        ThumbnailService thumbnailService,
        SegmentCacheService segmentCache,
        Map<String, DataSource> databases,
        List<Path> mediaRoots,
        MetricsService metricsService,
        VirtualPathResolver virtualPathResolver,
        QuotaService quotaService,
        BackupService backupService,
        LibraryScanService libraryScanService,
        LibraryAdminService libraryAdminService,
        List<String> authProviders,
        String storageBackendType
    ) {
        optionalAuth(route, authProviders, authenticatedRoute -> {
            authenticatedRoute.delete(
                "/api/v1/admin/cache/thumbnails",
                doc(config -> {
                    config.setDescription("Purge the thumbnail disk cache");
                    config.response(response -> {
                        response.code(HTTP_NO_CONTENT, describe("Cache purged"));
                    });
                }),
                handler(scope -> {
                    thumbnailService.purgeCache();
                    scope.getCall().respond(HTTP_NO_CONTENT);
                })
            );

            authenticatedRoute.delete(
                "/api/v1/admin/cache/segments",
                doc(config -> {
                    config.setDescription("Purge the in-memory segment cache");
                    config.response(response -> {
                        response.code(HTTP_NO_CONTENT, describe("Cache purged"));
                    });
                }),
                handler(scope -> {
                    segmentCache.purgeAll();
                    scope.getCall().respond(HTTP_NO_CONTENT);
                })
            );

            authenticatedRoute.get(
                "/api/v1/admin/cache/stats",
                doc(config -> {
                    config.setDescription("Get cache statistics");
                    config.response(response -> {
                        response.code(HTTP_OK, bodyDoc(CacheStats.class));
                    });
                }),
                handler(scope -> scope.getCall().respond(new CacheStats(storageBackendType, segmentCache.entryCount())))
            );

            authenticatedRoute.post(
                "/api/v1/admin/db/vacuum",
                doc(config -> {
                    config.setDescription("Run VACUUM on all SQLite databases");
                    config.response(response -> {
                        response.code(HTTP_OK, bodyDoc(VacuumResult.class));
                    });
                }),
                handler(scope -> {
                    try {
                        for (DataSource database : databases.values()) {
                            try (var connection = database.getConnection()) {
                                connection.setAutoCommit(true);
                                try (var statement = connection.createStatement()) {
                                    statement.execute("VACUUM");
                                }
                            }
                        }
                    } catch (Exception exception) {
                        throw sneakyThrow(exception);
                    }
                    scope.getCall().respond(new VacuumResult(databases.keySet().stream().toList()));
                })
            );

            authenticatedRoute.get(
                "/api/v1/admin/storage",
                doc(config -> {
                    config.setDescription("Get disk storage information for media roots");
                    config.response(response -> {
                        response.code(HTTP_OK, bodyDoc(StorageInfo[].class));
                    });
                }),
                handler(scope -> {
                    List<StorageInfo> storageInfos = mediaRoots.stream()
                        .filter(Files::exists)
                        .map(root -> {
                            try {
                                var store = Files.getFileStore(root);
                                String displayPath = virtualPathResolver != null
                                    ? valueOrDefault(virtualPathResolver.toVirtualPath(root), root.toString())
                                    : root.toString();
                                return new StorageInfo(
                                    displayPath,
                                    store.getTotalSpace(),
                                    store.getUnallocatedSpace(),
                                    store.getUsableSpace()
                                );
                            } catch (Exception exception) {
                                throw new IllegalStateException("Failed to inspect file store for " + root, exception);
                            }
                        })
                        .toList();
                    scope.getCall().respond(storageInfos);
                })
            );

            if (metricsService != null) {
                authenticatedRoute.get(
                    "/api/v1/admin/metrics",
                    doc(config -> {
                        config.setDescription("Scrape Prometheus metrics");
                        config.response(response -> {
                            response.code(HTTP_OK, describe("Prometheus text format"));
                        });
                    }),
                    handler(scope -> scope.getCall().respondText(
                        metricsService.scrape(),
                        ContentType.Text.INSTANCE.getPlain(),
                        null
                    ))
                );
            }

            if (quotaService != null) {
                authenticatedRoute.get(
                    "/api/v1/admin/users/{userId}/quota",
                    doc(config -> {
                        config.setDescription("Get per-user quota usage");
                        config.request(request -> {
                            request.pathParameter("userId", parameter -> {
                                parameter.setDescription("User identifier");
                            });
                        });
                        config.response(response -> {
                            response.code(HTTP_OK, bodyDoc(QuotaUsage.class));
                            response.code(HTTP_NOT_FOUND, describe("Unknown user"));
                        });
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        String userId = getRequiredPathParam(call, "userId");
                        if (!quotaService.isKnownUser(userId)) {
                            throw nyxException(ErrorCode.USER_NOT_FOUND, "Unknown user: " + userId);
                        }
                        call.respond(quotaService.getUsage(userId));
                    })
                );
            }

            if (backupService != null) {
                authenticatedRoute.post(
                    "/api/v1/admin/backup",
                    doc(config -> {
                        config.setDescription("Trigger an on-demand database backup");
                        config.response(response -> {
                            response.code(HTTP_OK, bodyDoc(BackupResult.class));
                            response.code(HTTP_CONFLICT, describe("Backup already in progress"));
                        });
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        try {
                            BackupResult result = backupService.runBackup();
                            if (result == null) {
                                call.respond(
                                    HTTP_CONFLICT,
                                    new ErrorResponse(
                                        new ErrorDetail("BACKUP_IN_PROGRESS", "A backup is already running", Map.of())
                                    )
                                );
                            } else {
                                call.respond(result);
                            }
                        } catch (Exception exception) {
                            throw nyxException(
                                ErrorCode.BACKUP_FAILED,
                                "Backup failed: " + exception.getMessage(),
                                exception
                            );
                        }
                    })
                );

                authenticatedRoute.get(
                    "/api/v1/admin/backup/status",
                    doc(config -> {
                        config.setDescription("Get backup status and statistics");
                        config.response(response -> {
                            response.code(HTTP_OK, bodyDoc(BackupStatus.class));
                        });
                    }),
                    handler(scope -> scope.getCall().respond(backupService.getStatus()))
                );
            }

            if (libraryScanService != null) {
                authenticatedRoute.post(
                    "/api/v1/admin/libraries/{libraryId}/scans",
                    doc(config -> {
                        config.setDescription("Queue a library scan run");
                        config.request(request -> {
                            request.pathParameter("libraryId", parameter -> {
                                parameter.setDescription("Library identifier");
                            });
                            request.body(LibraryScanRequest.class);
                        });
                        config.response(response -> {
                            response.code(HTTP_ACCEPTED, bodyDoc(LibraryScanRun.class));
                            response.code(HTTP_NOT_FOUND, describe("Library not found"));
                            response.code(HTTP_CONFLICT, describe("A scan is already active for this library"));
                        });
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        String libraryId = getRequiredPathParam(call, "libraryId");
                        LibraryScanRequest request = call.receive(LibraryScanRequest.class);
                        call.respond(HTTP_ACCEPTED, libraryScanService.triggerScan(libraryId, request.getMode()));
                    })
                );

                authenticatedRoute.get(
                    "/api/v1/admin/libraries/{libraryId}/scans",
                    doc(config -> {
                        config.setDescription("List recent library scan runs");
                        config.request(request -> {
                            request.pathParameter("libraryId", parameter -> {
                                parameter.setDescription("Library identifier");
                            });
                            request.queryParameter("limit", parameter -> {
                                parameter.setDescription("Maximum runs to return");
                                parameter.setRequired(false);
                            });
                        });
                        config.response(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryScanRun[].class));
                            response.code(HTTP_NOT_FOUND, describe("Library not found"));
                        });
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        String libraryId = getRequiredPathParam(call, "libraryId");
                        call.respond(libraryScanService.listRuns(libraryId, getLimitParam(call, 20, 100)));
                    })
                );
            }

            if (libraryAdminService != null) {
                authenticatedRoute.get(
                    "/api/v1/admin/libraries/{libraryId}/diagnostics",
                    doc(config -> {
                        config.setDescription(
                            "Expose library admin diagnostics including scan state, unmatched or generic items, and orphaned tracked records"
                        );
                        config.request(request -> {
                            request.pathParameter("libraryId", parameter -> {
                                parameter.setDescription("Library identifier");
                            });
                        });
                        config.response(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryAdminDiagnostics.class));
                            response.code(HTTP_NOT_FOUND, describe("Library not found"));
                        });
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        call.respond(libraryAdminService.getDiagnostics(getRequiredPathParam(call, "libraryId")));
                    })
                );

                authenticatedRoute.post(
                    "/api/v1/admin/libraries/{libraryId}/repairs/rebuild-derived-state",
                    doc(config -> {
                        config.setDescription(
                            "Repair library interpretation and enrichment state without creating a separate maintenance path"
                        );
                        config.request(request -> {
                            request.pathParameter("libraryId", parameter -> {
                                parameter.setDescription("Library identifier");
                            });
                        });
                        config.response(response -> {
                            response.code(HTTP_OK, bodyDoc(LibraryRepairResult.class));
                            response.code(HTTP_NOT_FOUND, describe("Library not found"));
                        });
                    }),
                    handler(scope -> {
                        RoutingCall call = scope.getCall();
                        call.respond(libraryAdminService.rebuildDerivedState(getRequiredPathParam(call, "libraryId")));
                    })
                );
            }
        });
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static void optionalAuth(Route route, List<String> authProviders, RouteRegistrar registrar) {
        registrar.accept(route.withAuth(AuthMode.OPTIONAL, authProviders));
    }

    private static Consumer<OpenApiRouteConfig> doc(RouteDoc block) {
        return block::accept;
    }

    private static Consumer<RouteHandlerScope> handler(RouteHandler handler) {
        return handler::accept;
    }

    private static Consumer<com.nyx.http.ResponseDoc> describe(String description) {
        return response -> response.setDescription(description);
    }

    private static Consumer<com.nyx.http.ResponseDoc> bodyDoc(Class<?> type) {
        return response -> response.body(type);
    }

    private static RuntimeException nyxException(ErrorCode errorCode, String message) {
        return nyxException(errorCode, message, null);
    }

    private static RuntimeException nyxException(ErrorCode errorCode, String message, Throwable cause) {
        return sneakyThrow(new NyxException(errorCode, message, Map.of(), cause));
    }

    private static RuntimeException sneakyThrow(Throwable throwable) {
        AdminRoutes.<RuntimeException>throwUnchecked(throwable);
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
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
    private interface RouteHandler {
        void accept(RouteHandlerScope scope);
    }
}
