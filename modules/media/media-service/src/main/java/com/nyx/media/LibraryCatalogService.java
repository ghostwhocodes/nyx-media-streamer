package com.nyx.media;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nyx.common.ErrorCode;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.json.NyxJson;
import com.nyx.media.contracts.CreateLibraryCollectionRequest;
import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryArtwork;
import com.nyx.media.contracts.LibraryArtworkKind;
import com.nyx.media.contracts.LibraryArtworkSource;
import com.nyx.media.contracts.LibraryCollection;
import com.nyx.media.contracts.LibraryCollectionListing;
import com.nyx.media.contracts.LibraryCollectionSummary;
import com.nyx.media.contracts.LibraryItem;
import com.nyx.media.contracts.LibraryItemListing;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibrarySourceRoot;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.ReplaceLibraryItemArtworkRequest;
import com.nyx.media.contracts.ReplaceLibraryItemMetadataRequest;
import com.nyx.media.contracts.UpdateLibraryCollectionRequest;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public final class LibraryCatalogService {
    private static final List<String> POSTER_FILE_NAMES = List.of(
        "poster.jpg",
        "poster.jpeg",
        "poster.png",
        "poster.webp",
        "folder.jpg",
        "folder.jpeg",
        "folder.png",
        "cover.jpg",
        "cover.jpeg",
        "cover.png"
    );

    private static final List<String> BACKGROUND_FILE_NAMES = List.of(
        "background.jpg",
        "background.jpeg",
        "background.png",
        "background.webp",
        "fanart.jpg",
        "fanart.jpeg",
        "fanart.png",
        "backdrop.jpg",
        "backdrop.jpeg",
        "backdrop.png"
    );

    private static final List<String> THUMBNAIL_FILE_NAMES = List.of(
        "thumb.jpg",
        "thumb.jpeg",
        "thumb.png",
        "thumb.webp",
        "landscape.jpg",
        "landscape.jpeg",
        "landscape.png"
    );

    private final Jdbi jdbi;
    private final LibraryService libraryService;
    private final ObjectMapper json = NyxJson.newMapper();

    public LibraryCatalogService(Jdbi jdbi, LibraryService libraryService) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.libraryService = Objects.requireNonNull(libraryService, "libraryService");
    }

    public void refreshLocalEnrichment(String libraryId) {
        String normalizedLibraryId = libraryId.trim();
        ensureLibraryExists(normalizedLibraryId);
        List<LibraryItem> items = loadBaseItems(normalizedLibraryId);
        List<String> itemIds = items.stream().map(LibraryItem::libraryItemId).toList();
        Map<String, PersistedMetadataRow> existingMetadata = loadMetadataRows(itemIds);
        Map<String, Map<LibraryArtworkKind, PersistedArtworkRow>> existingArtwork = loadArtworkRows(itemIds);
        Map<String, ImportedMetadata> importedMetadata = new LinkedHashMap<>();
        Map<String, Map<LibraryArtworkKind, ImportedArtwork>> importedArtwork = new LinkedHashMap<>();
        for (LibraryItem item : items) {
            importedMetadata.put(item.libraryItemId(), loadImportedMetadata(item));
            importedArtwork.put(item.libraryItemId(), loadImportedArtwork(item));
        }
        String now = Instant.now().toString();

        dbWriteQuery(handle -> {
            for (String itemId : itemIds) {
                upsertImportedMetadata(
                    handle,
                    itemId,
                    existingMetadata.get(itemId),
                    importedMetadata.get(itemId),
                    now
                );
                upsertImportedArtwork(
                    handle,
                    itemId,
                    existingArtwork.getOrDefault(itemId, Map.of()),
                    importedArtwork.getOrDefault(itemId, Map.of()),
                    now
                );
            }
            return null;
        });
    }

    public LibraryItemListing listLibraryItems(String libraryId) {
        return listLibraryItems(libraryId, null);
    }

    public LibraryItemListing listLibraryItems(String libraryId, String parentItemId) {
        List<LibraryItem> items = loadEnrichedItems(libraryId.trim()).stream()
            .filter(item -> Objects.equals(item.parentItemId(), parentItemId))
            .sorted(Comparator
                .comparing((LibraryItem item) -> effectiveSortValue(item).toLowerCase(Locale.ROOT))
                .thenComparing(LibraryItem::libraryItemId))
            .toList();
        return new LibraryItemListing(items, items.size());
    }

    public List<LibraryItem> listAllLibraryItems(String libraryId) {
        return loadEnrichedItems(libraryId.trim());
    }

    public LibraryItem getLibraryItem(String libraryId, String itemId) {
        return loadEnrichedItems(libraryId.trim()).stream()
            .filter(item -> item.libraryItemId().equals(itemId.trim()))
            .findFirst()
            .orElse(null);
    }

    public LibraryItem replaceManualMetadata(
        String libraryId,
        String itemId,
        ReplaceLibraryItemMetadataRequest request
    ) {
        LibraryItem baseItem = requireLibraryItem(libraryId.trim(), itemId.trim());
        String now = Instant.now().toString();
        List<String> manualTags = normalizeTags(request.tags());
        dbWriteQuery(handle -> {
            PersistedMetadataRow existing = loadMetadataRowForUpdate(handle, baseItem.libraryItemId());
            if (existing == null) {
                handle.createUpdate("""
                        INSERT INTO library_item_metadata(
                            library_item_id, manual_display_title, manual_sort_title, manual_overview,
                            manual_tags_json, imported_display_title, imported_sort_title, imported_overview,
                            imported_tags_json, imported_source_path, created_at, updated_at
                        ) VALUES (
                            :libraryItemId, :manualDisplayTitle, :manualSortTitle, :manualOverview,
                            :manualTagsJson, :importedDisplayTitle, :importedSortTitle, :importedOverview,
                            :importedTagsJson, :importedSourcePath, :createdAt, :updatedAt
                        )
                        """)
                    .bind("libraryItemId", baseItem.libraryItemId())
                    .bind("manualDisplayTitle", normalizeTextValue(request.displayTitle()))
                    .bind("manualSortTitle", normalizeTextValue(request.sortTitle()))
                    .bind("manualOverview", normalizeTextValue(request.overview()))
                    .bind("manualTagsJson", encodeTags(manualTags))
                    .bindNull("importedDisplayTitle", Types.VARCHAR)
                    .bindNull("importedSortTitle", Types.VARCHAR)
                    .bindNull("importedOverview", Types.VARCHAR)
                    .bindNull("importedTagsJson", Types.VARCHAR)
                    .bindNull("importedSourcePath", Types.VARCHAR)
                    .bind("createdAt", now)
                    .bind("updatedAt", now)
                    .execute();
            } else {
                handle.createUpdate("""
                        UPDATE library_item_metadata
                        SET manual_display_title = :manualDisplayTitle,
                            manual_sort_title = :manualSortTitle,
                            manual_overview = :manualOverview,
                            manual_tags_json = :manualTagsJson,
                            updated_at = :updatedAt
                        WHERE library_item_id = :libraryItemId
                        """)
                    .bind("manualDisplayTitle", normalizeTextValue(request.displayTitle()))
                    .bind("manualSortTitle", normalizeTextValue(request.sortTitle()))
                    .bind("manualOverview", normalizeTextValue(request.overview()))
                    .bind("manualTagsJson", encodeTags(manualTags))
                    .bind("updatedAt", now)
                    .bind("libraryItemId", baseItem.libraryItemId())
                    .execute();
            }
            return null;
        });
        LibraryItem updated = getLibraryItem(baseItem.libraryId(), baseItem.libraryItemId());
        if (updated == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_ITEM_NOT_FOUND,
                "Library item not found: " + baseItem.libraryItemId()
            ));
        }
        return updated;
    }

    public LibraryItem clearManualMetadata(String libraryId, String itemId) {
        LibraryItem baseItem = requireLibraryItem(libraryId.trim(), itemId.trim());
        String now = Instant.now().toString();
        dbWriteQuery(handle -> {
            handle.createUpdate("""
                    UPDATE library_item_metadata
                    SET manual_display_title = :manualDisplayTitle,
                        manual_sort_title = :manualSortTitle,
                        manual_overview = :manualOverview,
                        manual_tags_json = :manualTagsJson,
                        updated_at = :updatedAt
                    WHERE library_item_id = :libraryItemId
                    """)
                .bindNull("manualDisplayTitle", Types.VARCHAR)
                .bindNull("manualSortTitle", Types.VARCHAR)
                .bindNull("manualOverview", Types.VARCHAR)
                .bindNull("manualTagsJson", Types.VARCHAR)
                .bind("updatedAt", now)
                .bind("libraryItemId", baseItem.libraryItemId())
                .execute();
            return null;
        });
        LibraryItem updated = getLibraryItem(baseItem.libraryId(), baseItem.libraryItemId());
        if (updated == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_ITEM_NOT_FOUND,
                "Library item not found: " + baseItem.libraryItemId()
            ));
        }
        return updated;
    }

    public LibraryItem replaceManualArtwork(
        String libraryId,
        String itemId,
        ReplaceLibraryItemArtworkRequest request
    ) {
        String normalizedLibraryId = libraryId.trim();
        LibraryItem baseItem = requireLibraryItem(normalizedLibraryId, itemId.trim());
        Map<LibraryArtworkKind, String> manualByKind = new LinkedHashMap<>();
        manualByKind.put(LibraryArtworkKind.POSTER, validateArtworkPath(normalizedLibraryId, request.posterPath()));
        manualByKind.put(LibraryArtworkKind.BACKGROUND, validateArtworkPath(normalizedLibraryId, request.backgroundPath()));
        manualByKind.put(LibraryArtworkKind.THUMBNAIL, validateArtworkPath(normalizedLibraryId, request.thumbnailPath()));
        Map<LibraryArtworkKind, PersistedArtworkRow> existing = loadArtworkRows(List.of(baseItem.libraryItemId()))
            .getOrDefault(baseItem.libraryItemId(), Map.of());
        String now = Instant.now().toString();
        dbWriteQuery(handle -> {
            for (LibraryArtworkKind kind : LibraryArtworkKind.values()) {
                PersistedArtworkRow current = existing.get(kind);
                String manualPathValue = manualByKind.get(kind);
                if (current == null && manualPathValue == null) {
                    continue;
                }
                if (current == null) {
                    handle.createUpdate("""
                            INSERT INTO library_item_artwork(
                                library_item_id, artwork_kind, manual_path, imported_path,
                                imported_source, created_at, updated_at
                            ) VALUES (
                                :libraryItemId, :artworkKind, :manualPath, :importedPath,
                                :importedSource, :createdAt, :updatedAt
                            )
                            """)
                        .bind("libraryItemId", baseItem.libraryItemId())
                        .bind("artworkKind", kind.name())
                        .bind("manualPath", manualPathValue)
                        .bindNull("importedPath", Types.VARCHAR)
                        .bindNull("importedSource", Types.VARCHAR)
                        .bind("createdAt", now)
                        .bind("updatedAt", now)
                        .execute();
                } else {
                    handle.createUpdate("""
                            UPDATE library_item_artwork
                            SET manual_path = :manualPath,
                                updated_at = :updatedAt
                            WHERE library_item_id = :libraryItemId
                              AND artwork_kind = :artworkKind
                            """)
                        .bind("manualPath", manualPathValue)
                        .bind("updatedAt", now)
                        .bind("libraryItemId", baseItem.libraryItemId())
                        .bind("artworkKind", kind.name())
                        .execute();
                }
            }
            return null;
        });
        LibraryItem updated = getLibraryItem(baseItem.libraryId(), baseItem.libraryItemId());
        if (updated == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_ITEM_NOT_FOUND,
                "Library item not found: " + baseItem.libraryItemId()
            ));
        }
        return updated;
    }

    public LibraryItem clearManualArtwork(String libraryId, String itemId) {
        LibraryItem baseItem = requireLibraryItem(libraryId.trim(), itemId.trim());
        String now = Instant.now().toString();
        dbWriteQuery(handle -> {
            handle.createUpdate("""
                    UPDATE library_item_artwork
                    SET manual_path = :manualPath,
                        updated_at = :updatedAt
                    WHERE library_item_id = :libraryItemId
                    """)
                .bindNull("manualPath", Types.VARCHAR)
                .bind("updatedAt", now)
                .bind("libraryItemId", baseItem.libraryItemId())
                .execute();
            return null;
        });
        LibraryItem updated = getLibraryItem(baseItem.libraryId(), baseItem.libraryItemId());
        if (updated == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_ITEM_NOT_FOUND,
                "Library item not found: " + baseItem.libraryItemId()
            ));
        }
        return updated;
    }

    public LibraryCollectionListing listCollections(String libraryId) {
        String normalizedLibraryId = libraryId.trim();
        ensureLibraryExists(normalizedLibraryId);
        List<LibraryCollection> collections = loadCollections(normalizedLibraryId);
        return new LibraryCollectionListing(collections, collections.size());
    }

    public LibraryCollection getCollection(String libraryId, String collectionId) {
        return loadCollections(libraryId.trim()).stream()
            .filter(collection -> collection.collectionId().equals(collectionId.trim()))
            .findFirst()
            .orElse(null);
    }

    public LibraryCollection createCollection(String libraryId, CreateLibraryCollectionRequest request) {
        String normalizedLibraryId = libraryId.trim();
        ensureLibraryExists(normalizedLibraryId);
        String title = normalizeRequiredTextValue(request.title(), "Collection title is required");
        String sortTitle = normalizeTextValue(request.sortTitle());
        List<String> itemIds = normalizeItemIds(request.itemIds());
        ensureCollectionItemsBelongToLibrary(normalizedLibraryId, itemIds);
        String collectionId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        dbWriteQuery(handle -> {
            handle.createUpdate("""
                    INSERT INTO library_collections(
                        collection_id, library_id, title, sort_title, created_at, updated_at
                    ) VALUES (
                        :collectionId, :libraryId, :title, :sortTitle, :createdAt, :updatedAt
                    )
                    """)
                .bind("collectionId", collectionId)
                .bind("libraryId", normalizedLibraryId)
                .bind("title", title)
                .bind("sortTitle", sortTitle)
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .execute();
            replaceCollectionItems(handle, collectionId, itemIds, now);
            return null;
        });

        LibraryCollection created = getCollection(normalizedLibraryId, collectionId);
        if (created == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_COLLECTION_NOT_FOUND,
                "Library collection not found: " + collectionId
            ));
        }
        return created;
    }

    public LibraryCollection updateCollection(
        String libraryId,
        String collectionId,
        UpdateLibraryCollectionRequest request
    ) {
        String normalizedLibraryId = libraryId.trim();
        String normalizedCollectionId = collectionId.trim();
        LibraryCollection existing = requireCollection(normalizedLibraryId, normalizedCollectionId);
        String title = normalizeRequiredTextValue(request.title(), "Collection title is required");
        String sortTitle = normalizeTextValue(request.sortTitle());
        List<String> itemIds = normalizeItemIds(request.itemIds());
        ensureCollectionItemsBelongToLibrary(normalizedLibraryId, itemIds);
        String now = Instant.now().toString();

        dbWriteQuery(handle -> {
            handle.createUpdate("""
                    UPDATE library_collections
                    SET title = :title,
                        sort_title = :sortTitle,
                        updated_at = :updatedAt
                    WHERE collection_id = :collectionId
                    """)
                .bind("title", title)
                .bind("sortTitle", sortTitle)
                .bind("updatedAt", now)
                .bind("collectionId", existing.collectionId())
                .execute();
            replaceCollectionItems(handle, existing.collectionId(), itemIds, now);
            return null;
        });

        LibraryCollection updated = getCollection(normalizedLibraryId, existing.collectionId());
        if (updated == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_COLLECTION_NOT_FOUND,
                "Library collection not found: " + existing.collectionId()
            ));
        }
        return updated;
    }

    public void deleteCollection(String libraryId, String collectionId) {
        LibraryCollection existing = requireCollection(libraryId.trim(), collectionId.trim());
        dbWriteQuery(handle -> {
            handle.createUpdate("DELETE FROM library_collection_items WHERE collection_id = :collectionId")
                .bind("collectionId", existing.collectionId())
                .execute();
            int deleted = handle.createUpdate("DELETE FROM library_collections WHERE collection_id = :collectionId")
                .bind("collectionId", existing.collectionId())
                .execute();
            if (deleted == 0) {
                throw sneakyThrow(new NyxException(
                    ErrorCode.LIBRARY_COLLECTION_NOT_FOUND,
                    "Library collection not found: " + existing.collectionId()
                ));
            }
            return null;
        });
    }

    private List<LibraryItem> loadEnrichedItems(String libraryId) {
        ensureLibraryExists(libraryId);
        List<LibraryItem> baseItems = loadBaseItems(libraryId);
        List<String> itemIds = baseItems.stream().map(LibraryItem::libraryItemId).toList();
        Map<String, PersistedMetadataRow> metadata = loadMetadataRows(itemIds);
        Map<String, Map<LibraryArtworkKind, PersistedArtworkRow>> artwork = loadArtworkRows(itemIds);
        Map<String, List<LibraryCollectionSummary>> collections = loadCollectionMembership(itemIds);

        List<LibraryItem> items = new ArrayList<>(baseItems.size());
        for (LibraryItem base : baseItems) {
            PersistedMetadataRow metadataRow = metadata.get(base.libraryItemId());
            List<LibraryArtwork> effectiveArtwork = artwork
                .getOrDefault(base.libraryItemId(), Map.of())
                .values()
                .stream()
                .map(this::toEffectiveArtwork)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(value -> value.kind().ordinal()))
                .toList();
            List<LibraryCollectionSummary> collectionSummaries = collections
                .getOrDefault(base.libraryItemId(), List.of())
                .stream()
                .sorted(Comparator
                    .comparing((LibraryCollectionSummary summary) -> summary.title().toLowerCase(Locale.ROOT))
                    .thenComparing(LibraryCollectionSummary::collectionId))
                .toList();
            List<String> effectiveTags = metadataRow != null && metadataRow.manualTags() != null
                ? metadataRow.manualTags()
                : metadataRow == null ? List.of() : metadataRow.importedTags();
            items.add(new LibraryItem(
                base.libraryItemId(),
                base.libraryId(),
                base.parentItemId(),
                base.sourceEntryId(),
                base.sourceObjectId(),
                base.type(),
                base.title(),
                base.childCount(),
                base.mediaKind(),
                base.primaryPath(),
                base.unmatchedReason(),
                metadataRow == null ? null : metadataRow.manualDisplayTitle() != null
                    ? metadataRow.manualDisplayTitle()
                    : metadataRow.importedDisplayTitle(),
                metadataRow == null ? null : metadataRow.manualSortTitle() != null
                    ? metadataRow.manualSortTitle()
                    : metadataRow.importedSortTitle(),
                metadataRow == null ? null : metadataRow.manualOverview() != null
                    ? metadataRow.manualOverview()
                    : metadataRow.importedOverview(),
                effectiveTags,
                effectiveArtwork,
                collectionSummaries,
                base.seasonNumber(),
                base.episodeNumber(),
                base.trackNumber(),
                base.createdAt(),
                base.updatedAt()
            ));
        }
        return items;
    }

    private List<LibraryItem> loadBaseItems(String libraryId) {
        return dbQuery(handle -> {
            List<BaseLibraryItemRow> rows = handle.createQuery("""
                    SELECT library_item_id, library_id, parent_item_id, source_entry_id, source_object_id,
                           item_type, title, media_kind, primary_path, unmatched_reason,
                           season_number, episode_number, track_number, created_at, updated_at
                    FROM library_items
                    WHERE library_id = :libraryId
                    ORDER BY title ASC
                    """)
                .bind("libraryId", libraryId)
                .map((resultSet, context) -> toBaseLibraryItemRow(resultSet))
                .list();
            Map<String, Long> childCounts = rows.stream()
                .map(BaseLibraryItemRow::parentItemId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.groupingBy(
                    Function.identity(),
                    LinkedHashMap::new,
                    java.util.stream.Collectors.counting()
                ));
            return rows.stream()
                .map(row -> row.toLibraryItem(childCounts.getOrDefault(row.libraryItemId(), 0L).intValue()))
                .toList();
        });
    }

    private Map<String, PersistedMetadataRow> loadMetadataRows(List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return dbQuery(handle -> {
            List<MetadataRowEntry> rows = handle.createQuery("""
                    SELECT library_item_id, manual_display_title, manual_sort_title, manual_overview,
                           manual_tags_json, imported_display_title, imported_sort_title, imported_overview,
                           imported_tags_json, imported_source_path
                    FROM library_item_metadata
                    WHERE library_item_id IN (<itemIds>)
                    """)
                .bindList("itemIds", itemIds)
                .map((resultSet, context) -> new MetadataRowEntry(
                    resultSet.getString("library_item_id"),
                    new PersistedMetadataRow(
                        resultSet.getString("manual_display_title"),
                        resultSet.getString("manual_sort_title"),
                        resultSet.getString("manual_overview"),
                        resultSet.getString("manual_tags_json") == null ? null : decodeTags(resultSet.getString("manual_tags_json")),
                        resultSet.getString("imported_display_title"),
                        resultSet.getString("imported_sort_title"),
                        resultSet.getString("imported_overview"),
                        resultSet.getString("imported_tags_json") == null ? List.of() : decodeTags(resultSet.getString("imported_tags_json"))
                    )
                ))
                .list();
            Map<String, PersistedMetadataRow> result = new LinkedHashMap<>();
            for (MetadataRowEntry row : rows) {
                result.put(row.itemId(), row.row());
            }
            return result;
        });
    }

    private Map<String, Map<LibraryArtworkKind, PersistedArtworkRow>> loadArtworkRows(List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return dbQuery(handle -> {
            List<PersistedArtworkItemRow> rows = handle.createQuery("""
                    SELECT library_item_id, artwork_kind, manual_path, imported_path, imported_source
                    FROM library_item_artwork
                    WHERE library_item_id IN (<itemIds>)
                    """)
                .bindList("itemIds", itemIds)
                .map((resultSet, context) -> new PersistedArtworkItemRow(
                    resultSet.getString("library_item_id"),
                    LibraryArtworkKind.valueOf(resultSet.getString("artwork_kind")),
                    resultSet.getString("manual_path"),
                    resultSet.getString("imported_path"),
                    resultSet.getString("imported_source") == null
                        ? null
                        : LibraryArtworkSource.valueOf(resultSet.getString("imported_source"))
                ))
                .list();
            Map<String, Map<LibraryArtworkKind, PersistedArtworkRow>> result = new LinkedHashMap<>();
            for (PersistedArtworkItemRow row : rows) {
                result.computeIfAbsent(row.libraryItemId(), ignored -> new EnumMap<>(LibraryArtworkKind.class))
                    .put(row.kind(), new PersistedArtworkRow(
                        row.kind(),
                        row.manualPath(),
                        row.importedPath(),
                        row.importedSource()
                    ));
            }
            return result;
        });
    }

    private Map<String, List<LibraryCollectionSummary>> loadCollectionMembership(List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return dbQuery(handle -> {
            List<CollectionMembershipRow> rows = handle.createQuery("""
                    SELECT ci.library_item_id, c.collection_id, c.title
                    FROM library_collection_items ci
                    INNER JOIN library_collections c ON c.collection_id = ci.collection_id
                    WHERE ci.library_item_id IN (<itemIds>)
                    """)
                .bindList("itemIds", itemIds)
                .map((resultSet, context) -> new CollectionMembershipRow(
                    resultSet.getString("library_item_id"),
                    new LibraryCollectionSummary(
                        resultSet.getString("collection_id"),
                        resultSet.getString("title")
                    )
                ))
                .list();
            Map<String, List<LibraryCollectionSummary>> result = new LinkedHashMap<>();
            for (CollectionMembershipRow row : rows) {
                result.computeIfAbsent(row.itemId(), ignored -> new ArrayList<>()).add(row.summary());
            }
            return result;
        });
    }

    private List<LibraryCollection> loadCollections(String libraryId) {
        List<PersistedCollectionRow> collectionRows = dbQuery(handle -> handle.createQuery("""
                SELECT collection_id, library_id, title, sort_title, created_at, updated_at
                FROM library_collections
                WHERE library_id = :libraryId
                ORDER BY updated_at DESC
                """)
            .bind("libraryId", libraryId)
            .map((resultSet, context) -> toCollectionRow(resultSet))
            .list());
        if (collectionRows.isEmpty()) {
            return List.of();
        }
        List<String> collectionIds = collectionRows.stream().map(PersistedCollectionRow::collectionId).toList();
        Map<String, List<String>> itemIdsByCollection = dbQuery(handle -> {
            List<CollectionItemRow> rows = handle.createQuery("""
                    SELECT collection_id, library_item_id
                    FROM library_collection_items
                    WHERE collection_id IN (<collectionIds>)
                    ORDER BY position ASC
                    """)
                .bindList("collectionIds", collectionIds)
                .map((resultSet, context) -> new CollectionItemRow(
                    resultSet.getString("collection_id"),
                    resultSet.getString("library_item_id")
                ))
                .list();
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (CollectionItemRow row : rows) {
                result.computeIfAbsent(row.collectionId(), ignored -> new ArrayList<>()).add(row.libraryItemId());
            }
            return result;
        });

        return collectionRows.stream()
            .map(row -> {
                List<String> itemIds = itemIdsByCollection.getOrDefault(row.collectionId(), List.of());
                return new LibraryCollection(
                    row.collectionId(),
                    row.libraryId(),
                    row.title(),
                    row.sortTitle(),
                    itemIds,
                    itemIds.size(),
                    row.createdAt(),
                    row.updatedAt()
                );
            })
            .sorted(Comparator
                .comparing((LibraryCollection collection) -> {
                    String sortTitle = collection.sortTitle();
                    return (sortTitle == null ? collection.title() : sortTitle).toLowerCase(Locale.ROOT);
                })
                .thenComparing(LibraryCollection::collectionId))
            .toList();
    }

    private ImportedMetadata loadImportedMetadata(LibraryItem item) {
        Path metadataPath = findMetadataPath(item);
        if (metadataPath == null) {
            return new ImportedMetadata();
        }
        ImportedMetadata parsed = parseNfo(metadataPath);
        if (parsed == null) {
            return new ImportedMetadata();
        }
        return new ImportedMetadata(
            parsed.displayTitle(),
            parsed.sortTitle(),
            parsed.overview(),
            parsed.tags(),
            metadataPath.toString()
        );
    }

    private Map<LibraryArtworkKind, ImportedArtwork> loadImportedArtwork(LibraryItem item) {
        Path artworkDir = artworkDirectory(item);
        if (artworkDir == null) {
            return Map.of();
        }
        Path poster = findFirstImage(artworkDir, POSTER_FILE_NAMES);
        Path background = findFirstImage(artworkDir, BACKGROUND_FILE_NAMES);
        Path thumbnail = findFirstImage(artworkDir, THUMBNAIL_FILE_NAMES);
        if (thumbnail == null) {
            thumbnail = poster;
        }

        Map<LibraryArtworkKind, ImportedArtwork> result = new EnumMap<>(LibraryArtworkKind.class);
        if (poster != null) {
            result.put(
                LibraryArtworkKind.POSTER,
                new ImportedArtwork(LibraryArtworkKind.POSTER, poster.toString(), LibraryArtworkSource.FOLDER)
            );
        }
        if (background != null) {
            result.put(
                LibraryArtworkKind.BACKGROUND,
                new ImportedArtwork(LibraryArtworkKind.BACKGROUND, background.toString(), LibraryArtworkSource.FOLDER)
            );
        }
        if (thumbnail != null) {
            result.put(
                LibraryArtworkKind.THUMBNAIL,
                new ImportedArtwork(LibraryArtworkKind.THUMBNAIL, thumbnail.toString(), LibraryArtworkSource.FOLDER)
            );
        }
        return result;
    }

    private Path findMetadataPath(LibraryItem item) {
        Path primary = toPath(item.primaryPath());
        if (primary == null) {
            return null;
        }
        List<Path> candidates;
        if (java.nio.file.Files.isDirectory(primary)) {
            candidates = directoryMetadataCandidates(item.type(), primary);
        } else if (java.nio.file.Files.exists(primary)) {
            candidates = List.of(primary.resolveSibling(fileStem(primary.getFileName().toString()) + ".nfo"));
        } else {
            candidates = List.of();
        }
        return candidates.stream()
            .filter(java.nio.file.Files::isRegularFile)
            .findFirst()
            .orElse(null);
    }

    private List<Path> directoryMetadataCandidates(LibraryItemType type, Path directory) {
        List<String> fileNames = switch (type) {
            case SHOW -> List.of("tvshow.nfo", "show.nfo", "index.nfo");
            case SEASON -> List.of("season.nfo", "index.nfo");
            case ALBUM -> List.of("album.nfo", "index.nfo");
            default -> List.of("index.nfo");
        };
        return fileNames.stream().map(directory::resolve).toList();
    }

    private Path artworkDirectory(LibraryItem item) {
        Path primary = toPath(item.primaryPath());
        if (primary == null) {
            return null;
        }
        if (java.nio.file.Files.isDirectory(primary)) {
            return primary;
        }
        if (java.nio.file.Files.exists(primary)) {
            return primary.getParent();
        }
        return null;
    }

    private Path findFirstImage(Path directory, List<String> fileNames) {
        for (String fileName : fileNames) {
            Path candidate = directory.resolve(fileName);
            if (java.nio.file.Files.isRegularFile(candidate) && MediaTypes.isImage(MediaTypes.detectMimeType(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private ImportedMetadata parseNfo(Path path) {
        org.w3c.dom.Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            setFactoryFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            setFactoryFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFactoryFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
            setFactoryFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            document = factory.newDocumentBuilder().parse(path.toFile());
        } catch (Exception exception) {
            return null;
        }

        Element root = document.getDocumentElement();
        if (root == null) {
            return null;
        }
        NodeList nodes = root.getElementsByTagName("tag");
        List<String> tags = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index += 1) {
            String normalized = normalizeTextValue(nodes.item(index) == null ? null : nodes.item(index).getTextContent());
            if (normalized != null) {
                tags.add(normalized);
            }
        }

        String displayTitle = normalizeTextValue(firstTagText(root, "title"));
        String sortTitle = normalizeTextValue(firstTagText(root, "sorttitle"));
        String overview = normalizeTextValue(firstTagText(root, "plot"));
        if (displayTitle == null && sortTitle == null && overview == null && tags.isEmpty()) {
            return null;
        }

        return new ImportedMetadata(displayTitle, sortTitle, overview, normalizeTags(tags), null);
    }

    private static void setFactoryFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean enabled) {
        try {
            factory.setFeature(feature, enabled);
        } catch (Exception ignored) {
        }
    }

    private String firstTagText(Element root, String tagName) {
        var node = root.getElementsByTagName(tagName).item(0);
        return node == null ? null : node.getTextContent();
    }

    private LibraryArtwork toEffectiveArtwork(PersistedArtworkRow row) {
        String path = row.manualPath() != null ? row.manualPath() : row.importedPath();
        if (path == null) {
            return null;
        }
        LibraryArtworkSource source = row.manualPath() != null
            ? LibraryArtworkSource.MANUAL
            : row.importedSource() == null ? LibraryArtworkSource.FOLDER : row.importedSource();
        Path pathObject = toPath(path);
        if (pathObject == null) {
            return null;
        }
        String mimeType = java.nio.file.Files.isRegularFile(pathObject)
            ? MediaTypes.detectMimeType(pathObject)
            : MediaTypes.APPLICATION_OCTET_STREAM;
        return new LibraryArtwork(row.kind(), source, path, mimeType);
    }

    private LibraryItem requireLibraryItem(String libraryId, String itemId) {
        LibraryItem item = loadBaseItems(libraryId).stream()
            .filter(candidate -> candidate.libraryItemId().equals(itemId))
            .findFirst()
            .orElse(null);
        if (item == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_ITEM_NOT_FOUND,
                "Library item not found: " + itemId
            ));
        }
        return item;
    }

    private LibraryCollection requireCollection(String libraryId, String collectionId) {
        LibraryCollection collection = getCollection(libraryId, collectionId);
        if (collection == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_COLLECTION_NOT_FOUND,
                "Library collection not found: " + collectionId
            ));
        }
        return collection;
    }

    private void ensureCollectionItemsBelongToLibrary(String libraryId, List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }
        Set<String> matchingItemIds = dbQuery(handle -> new LinkedHashSet<>(handle.createQuery("""
                SELECT library_item_id
                FROM library_items
                WHERE library_id = :libraryId
                  AND library_item_id IN (<itemIds>)
                """)
            .bind("libraryId", libraryId)
            .bindList("itemIds", itemIds)
            .mapTo(String.class)
            .list()));
        List<String> missing = itemIds.stream().filter(itemId -> !matchingItemIds.contains(itemId)).toList();
        if (!missing.isEmpty()) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_ITEM_NOT_FOUND,
                "Collection references " + missing.size() + " library item(s) outside library " + libraryId
            ));
        }
    }

    private PersistedMetadataRow loadMetadataRowForUpdate(Handle handle, String itemId) {
        return handle.createQuery("""
                SELECT library_item_id, manual_display_title, manual_sort_title, manual_overview,
                       manual_tags_json, imported_display_title, imported_sort_title, imported_overview,
                       imported_tags_json, imported_source_path
                FROM library_item_metadata
                WHERE library_item_id = :libraryItemId
                """)
            .bind("libraryItemId", itemId)
            .map((resultSet, context) -> new PersistedMetadataRow(
                resultSet.getString("manual_display_title"),
                resultSet.getString("manual_sort_title"),
                resultSet.getString("manual_overview"),
                resultSet.getString("manual_tags_json") == null ? null : decodeTags(resultSet.getString("manual_tags_json")),
                resultSet.getString("imported_display_title"),
                resultSet.getString("imported_sort_title"),
                resultSet.getString("imported_overview"),
                resultSet.getString("imported_tags_json") == null ? List.of() : decodeTags(resultSet.getString("imported_tags_json"))
            ))
            .findOne()
            .orElse(null);
    }

    private void upsertImportedMetadata(
        Handle handle,
        String itemId,
        PersistedMetadataRow existing,
        ImportedMetadata imported,
        String now
    ) {
        ImportedMetadata effectiveImported = imported == null ? new ImportedMetadata() : imported;
        if (existing == null && effectiveImported.isEmpty()) {
            return;
        }
        if (existing == null) {
            handle.createUpdate("""
                    INSERT INTO library_item_metadata(
                        library_item_id, manual_display_title, manual_sort_title, manual_overview,
                        manual_tags_json, imported_display_title, imported_sort_title, imported_overview,
                        imported_tags_json, imported_source_path, created_at, updated_at
                    ) VALUES (
                        :libraryItemId, :manualDisplayTitle, :manualSortTitle, :manualOverview,
                        :manualTagsJson, :importedDisplayTitle, :importedSortTitle, :importedOverview,
                        :importedTagsJson, :importedSourcePath, :createdAt, :updatedAt
                    )
                    """)
                .bind("libraryItemId", itemId)
                .bindNull("manualDisplayTitle", Types.VARCHAR)
                .bindNull("manualSortTitle", Types.VARCHAR)
                .bindNull("manualOverview", Types.VARCHAR)
                .bindNull("manualTagsJson", Types.VARCHAR)
                .bind("importedDisplayTitle", effectiveImported.displayTitle())
                .bind("importedSortTitle", effectiveImported.sortTitle())
                .bind("importedOverview", effectiveImported.overview())
                .bind("importedTagsJson", effectiveImported.tags().isEmpty() ? null : encodeTags(effectiveImported.tags()))
                .bind("importedSourcePath", effectiveImported.sourcePath())
                .bind("createdAt", now)
                .bind("updatedAt", now)
                .execute();
            return;
        }

        handle.createUpdate("""
                UPDATE library_item_metadata
                SET imported_display_title = :importedDisplayTitle,
                    imported_sort_title = :importedSortTitle,
                    imported_overview = :importedOverview,
                    imported_tags_json = :importedTagsJson,
                    imported_source_path = :importedSourcePath,
                    updated_at = :updatedAt
                WHERE library_item_id = :libraryItemId
                """)
            .bind("importedDisplayTitle", effectiveImported.displayTitle())
            .bind("importedSortTitle", effectiveImported.sortTitle())
            .bind("importedOverview", effectiveImported.overview())
            .bind("importedTagsJson", effectiveImported.tags().isEmpty() ? null : encodeTags(effectiveImported.tags()))
            .bind("importedSourcePath", effectiveImported.sourcePath())
            .bind("updatedAt", now)
            .bind("libraryItemId", itemId)
            .execute();
    }

    private void upsertImportedArtwork(
        Handle handle,
        String itemId,
        Map<LibraryArtworkKind, PersistedArtworkRow> existing,
        Map<LibraryArtworkKind, ImportedArtwork> imported,
        String now
    ) {
        for (LibraryArtworkKind kind : LibraryArtworkKind.values()) {
            PersistedArtworkRow current = existing.get(kind);
            ImportedArtwork importedArtwork = imported.get(kind);
            if (current == null && importedArtwork == null) {
                continue;
            }
            if (current == null) {
                handle.createUpdate("""
                        INSERT INTO library_item_artwork(
                            library_item_id, artwork_kind, manual_path, imported_path,
                            imported_source, created_at, updated_at
                        ) VALUES (
                            :libraryItemId, :artworkKind, :manualPath, :importedPath,
                            :importedSource, :createdAt, :updatedAt
                        )
                        """)
                    .bind("libraryItemId", itemId)
                    .bind("artworkKind", kind.name())
                    .bindNull("manualPath", Types.VARCHAR)
                    .bind("importedPath", importedArtwork == null ? null : importedArtwork.path())
                    .bind("importedSource", importedArtwork == null ? null : importedArtwork.source().name())
                    .bind("createdAt", now)
                    .bind("updatedAt", now)
                    .execute();
                continue;
            }
            handle.createUpdate("""
                    UPDATE library_item_artwork
                    SET imported_path = :importedPath,
                        imported_source = :importedSource,
                        updated_at = :updatedAt
                    WHERE library_item_id = :libraryItemId
                      AND artwork_kind = :artworkKind
                    """)
                .bind("importedPath", importedArtwork == null ? null : importedArtwork.path())
                .bind("importedSource", importedArtwork == null ? null : importedArtwork.source().name())
                .bind("updatedAt", now)
                .bind("libraryItemId", itemId)
                .bind("artworkKind", kind.name())
                .execute();
        }
    }

    private void replaceCollectionItems(Handle handle, String collectionId, List<String> itemIds, String now) {
        handle.createUpdate("DELETE FROM library_collection_items WHERE collection_id = :collectionId")
            .bind("collectionId", collectionId)
            .execute();
        for (int index = 0; index < itemIds.size(); index += 1) {
            handle.createUpdate("""
                    INSERT INTO library_collection_items(
                        collection_item_id, collection_id, library_item_id, position, created_at
                    ) VALUES (
                        :collectionItemId, :collectionId, :libraryItemId, :position, :createdAt
                    )
                    """)
                .bind("collectionItemId", UUID.randomUUID().toString())
                .bind("collectionId", collectionId)
                .bind("libraryItemId", itemIds.get(index))
                .bind("position", index)
                .bind("createdAt", now)
                .execute();
        }
    }

    private String validateArtworkPath(String libraryId, String rawPath) {
        String normalized = normalizeTextValue(rawPath);
        if (normalized == null) {
            return null;
        }
        Library library = libraryService.getLibrary(libraryId);
        if (library == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + libraryId
            ));
        }
        Path absolute;
        try {
            absolute = Path.of(normalized);
        } catch (InvalidPathException exception) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Invalid artwork path: " + normalized,
                exception
            ));
        }
        if (!absolute.isAbsolute()) {
            throw sneakyThrow(new NyxException(
                ErrorCode.PATH_NOT_ALLOWED,
                "Artwork path must be absolute: " + normalized
            ));
        }
        if (!java.nio.file.Files.isRegularFile(absolute)) {
            throw sneakyThrow(new NyxException(
                ErrorCode.FILE_NOT_FOUND,
                "Artwork file not found: " + normalized
            ));
        }

        Path canonical;
        try {
            canonical = absolute.toRealPath();
        } catch (IOException exception) {
            throw sneakyThrow(exception);
        }

        List<Path> allowedRoots = new ArrayList<>();
        for (LibrarySourceRoot root : library.sourceRoots()) {
            try {
                allowedRoots.add(Path.of(root.path()).toRealPath());
            } catch (Exception ignored) {
            }
        }
        boolean insideAllowedRoots = allowedRoots.stream().anyMatch(canonical::startsWith);
        if (!insideAllowedRoots) {
            throw sneakyThrow(new NyxException(
                ErrorCode.PATH_NOT_ALLOWED,
                "Artwork path is outside the library source roots: " + normalized
            ));
        }
        String mimeType = MediaTypes.detectMimeType(canonical);
        if (!MediaTypes.isImage(mimeType)) {
            throw sneakyThrow(new NyxException(
                ErrorCode.INVALID_REQUEST,
                "Artwork path is not an image: " + normalized
            ));
        }
        return canonical.toString();
    }

    private String normalizeRequiredTextValue(String value, String message) {
        String normalized = normalizeTextValue(value);
        if (normalized == null) {
            throw sneakyThrow(new NyxException(ErrorCode.INVALID_REQUEST, message));
        }
        return normalized;
    }

    private String normalizeTextValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private List<String> normalizeItemIds(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String itemId : itemIds) {
            if (itemId == null) {
                continue;
            }
            String trimmed = itemId.trim();
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> normalized = new ArrayList<>();
        for (String tag : tags) {
            String normalizedTag = normalizeTextValue(tag);
            if (normalizedTag == null) {
                continue;
            }
            String key = normalizedTag.toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                normalized.add(normalizedTag);
            }
        }
        return List.copyOf(normalized);
    }

    private String encodeTags(List<String> tags) {
        try {
            return json.writeValueAsString(tags);
        } catch (IOException exception) {
            throw sneakyThrow(exception);
        }
    }

    private List<String> decodeTags(String raw) {
        try {
            return normalizeTags(json.readValue(raw, new TypeReference<List<String>>() {}));
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String effectiveSortValue(LibraryItem item) {
        if (item.sortTitle() != null) {
            return item.sortTitle();
        }
        if (item.displayTitle() != null) {
            return item.displayTitle();
        }
        return item.title();
    }

    private void ensureLibraryExists(String libraryId) {
        if (libraryService.getLibrary(libraryId) == null) {
            throw sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + libraryId
            ));
        }
    }

    private <T> T dbQuery(Function<Handle, T> block) {
        return withHandleUnchecked(jdbi, block);
    }

    private <T> T dbWriteQuery(Function<Handle, T> block) {
        return sqliteWriteTransaction(jdbi, block);
    }

    private static String fileStem(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot <= 0 ? fileName : fileName.substring(0, lastDot);
    }

    private static Path toPath(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        try {
            return Path.of(rawPath);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BaseLibraryItemRow toBaseLibraryItemRow(ResultSet resultSet) throws SQLException {
        return new BaseLibraryItemRow(
            resultSet.getString("library_item_id"),
            resultSet.getString("library_id"),
            resultSet.getString("parent_item_id"),
            resultSet.getString("source_entry_id"),
            resultSet.getString("source_object_id"),
            LibraryItemType.valueOf(resultSet.getString("item_type")),
            resultSet.getString("title"),
            resultSet.getString("media_kind") == null ? null : MediaKind.valueOf(resultSet.getString("media_kind")),
            resultSet.getString("primary_path"),
            resultSet.getString("unmatched_reason"),
            JdbcRow.getNullableInt(resultSet, "season_number"),
            JdbcRow.getNullableInt(resultSet, "episode_number"),
            JdbcRow.getNullableInt(resultSet, "track_number"),
            resultSet.getString("created_at"),
            resultSet.getString("updated_at")
        );
    }

    private static PersistedCollectionRow toCollectionRow(ResultSet resultSet) throws SQLException {
        return new PersistedCollectionRow(
            resultSet.getString("collection_id"),
            resultSet.getString("library_id"),
            resultSet.getString("title"),
            resultSet.getString("sort_title"),
            resultSet.getString("created_at"),
            resultSet.getString("updated_at")
        );
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record PersistedMetadataRow(
        String manualDisplayTitle,
        String manualSortTitle,
        String manualOverview,
        List<String> manualTags,
        String importedDisplayTitle,
        String importedSortTitle,
        String importedOverview,
        List<String> importedTags
    ) {}

    private record BaseLibraryItemRow(
        String libraryItemId,
        String libraryId,
        String parentItemId,
        String sourceEntryId,
        String sourceObjectId,
        LibraryItemType type,
        String title,
        MediaKind mediaKind,
        String primaryPath,
        String unmatchedReason,
        Integer seasonNumber,
        Integer episodeNumber,
        Integer trackNumber,
        String createdAt,
        String updatedAt
    ) {
        private LibraryItem toLibraryItem(int childCount) {
            return new LibraryItem(
                libraryItemId,
                libraryId,
                parentItemId,
                sourceEntryId,
                sourceObjectId,
                type,
                title,
                childCount,
                mediaKind,
                primaryPath,
                unmatchedReason,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                seasonNumber,
                episodeNumber,
                trackNumber,
                createdAt,
                updatedAt
            );
        }
    }

    private record ImportedMetadata(
        String displayTitle,
        String sortTitle,
        String overview,
        List<String> tags,
        String sourcePath
    ) {
        private ImportedMetadata() {
            this(null, null, null, List.of(), null);
        }

        private boolean isEmpty() {
            return displayTitle == null
                && sortTitle == null
                && overview == null
                && tags.isEmpty()
                && sourcePath == null;
        }
    }

    private record PersistedArtworkRow(
        LibraryArtworkKind kind,
        String manualPath,
        String importedPath,
        LibraryArtworkSource importedSource
    ) {}

    private record PersistedArtworkItemRow(
        String libraryItemId,
        LibraryArtworkKind kind,
        String manualPath,
        String importedPath,
        LibraryArtworkSource importedSource
    ) {}

    private record ImportedArtwork(
        LibraryArtworkKind kind,
        String path,
        LibraryArtworkSource source
    ) {}

    private record PersistedCollectionRow(
        String collectionId,
        String libraryId,
        String title,
        String sortTitle,
        String createdAt,
        String updatedAt
    ) {}

    private record MetadataRowEntry(
        String itemId,
        PersistedMetadataRow row
    ) {}

    private record CollectionMembershipRow(
        String itemId,
        LibraryCollectionSummary summary
    ) {}

    private record CollectionItemRow(
        String collectionId,
        String libraryItemId
    ) {}
}
