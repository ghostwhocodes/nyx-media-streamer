package com.nyx.media;

import static com.nyx.common.SqliteWriteTransactions.sqliteWriteTransaction;
import static com.nyx.common.SqliteWriteTransactions.withHandleUnchecked;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.Library;
import com.nyx.media.contracts.LibraryItem;
import com.nyx.media.contracts.LibraryItemListing;
import com.nyx.media.contracts.LibraryItemType;
import com.nyx.media.contracts.LibrarySourceRoot;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jdbi.v3.core.Jdbi;

public final class LibraryInterpretationService {
    private final Jdbi jdbi;
    private final LibraryService libraryService;
    private final MediaObjectService mediaObjectService;
    private final Clock clock;
    private final List<LibraryItemBuilder> itemBuilders;

    public LibraryInterpretationService(
        Jdbi jdbi,
        LibraryService libraryService,
        MediaObjectService mediaObjectService
    ) {
        this(jdbi, libraryService, mediaObjectService, Clock.systemUTC());
    }

    public LibraryInterpretationService(
        Jdbi jdbi,
        LibraryService libraryService,
        MediaObjectService mediaObjectService,
        Clock clock
    ) {
        this(jdbi, libraryService, mediaObjectService, clock, defaultItemBuilders());
    }

    public LibraryInterpretationService(
        Jdbi jdbi,
        LibraryService libraryService,
        MediaObjectService mediaObjectService,
        Clock clock,
        List<LibraryItemBuilder> itemBuilders
    ) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.libraryService = Objects.requireNonNull(libraryService, "libraryService");
        this.mediaObjectService = Objects.requireNonNull(mediaObjectService, "mediaObjectService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.itemBuilders = itemBuilders == null || itemBuilders.isEmpty()
            ? defaultItemBuilders()
            : List.copyOf(itemBuilders);
    }

    public LibraryItemListing rebuildLibraryItems(String libraryId) {
        String normalizedLibraryId = libraryId.trim();
        Library library = libraryService.getLibrary(normalizedLibraryId);
        if (library == null) {
            return sneakyThrow(new NyxException(
                ErrorCode.LIBRARY_NOT_FOUND,
                "Library not found: " + normalizedLibraryId
            ));
        }

        List<InterpretableLibraryEntry> activeEntries = loadActiveEntries(normalizedLibraryId);
        Map<String, PersistedLibraryItemIdentity> existingByIdentityKey = new HashMap<>();
        for (PersistedLibraryItemIdentity item : loadExistingItems(normalizedLibraryId)) {
            existingByIdentityKey.put(item.identityKey(), item);
        }

        LinkedHashMap<String, LibraryItemDescriptor> descriptors = buildDesiredItems(library, activeEntries);
        String now = clock.instant().toString();
        Map<String, String> idByIdentityKey = new LinkedHashMap<>();
        for (LibraryItemDescriptor descriptor : descriptors.values()) {
            PersistedLibraryItemIdentity existing = existingByIdentityKey.get(descriptor.identityKey());
            idByIdentityKey.put(
                descriptor.identityKey(),
                existing == null ? UUID.randomUUID().toString() : existing.libraryItemId()
            );
        }
        List<String> desiredIdentityKeys = List.copyOf(descriptors.keySet());

        sqliteWriteTransaction(jdbi, handle -> {
            for (LibraryItemDescriptor descriptor : descriptors.values()) {
                PersistedLibraryItemIdentity existing = existingByIdentityKey.get(descriptor.identityKey());
                String persistedItemId = idByIdentityKey.get(descriptor.identityKey());
                if (existing == null) {
                    handle.createUpdate(
                        """
                        INSERT INTO library_items(
                            library_item_id, library_id, parent_item_id, source_entry_id, source_object_id,
                            item_type, identity_key, title, media_kind, primary_path, unmatched_reason,
                            season_number, episode_number, track_number, created_at, updated_at
                        ) VALUES (
                            :libraryItemId, :libraryId, :parentItemId, :sourceEntryId, :sourceObjectId,
                            :itemType, :identityKey, :title, :mediaKind, :primaryPath, :unmatchedReason,
                            :seasonNumber, :episodeNumber, :trackNumber, :createdAt, :updatedAt
                        )
                        """
                    )
                        .bind("libraryItemId", persistedItemId)
                        .bind("libraryId", normalizedLibraryId)
                        .bind(
                            "parentItemId",
                            descriptor.parentIdentityKey() == null
                                ? null
                                : idByIdentityKey.get(descriptor.parentIdentityKey())
                        )
                        .bind("sourceEntryId", descriptor.sourceEntryId())
                        .bind("sourceObjectId", descriptor.sourceObjectId())
                        .bind("itemType", descriptor.type().name())
                        .bind("identityKey", descriptor.identityKey())
                        .bind("title", descriptor.title())
                        .bind("mediaKind", descriptor.mediaKind() == null ? null : descriptor.mediaKind().name())
                        .bind("primaryPath", descriptor.primaryPath())
                        .bind("unmatchedReason", descriptor.unmatchedReason())
                        .bind("seasonNumber", descriptor.seasonNumber())
                        .bind("episodeNumber", descriptor.episodeNumber())
                        .bind("trackNumber", descriptor.trackNumber())
                        .bind("createdAt", now)
                        .bind("updatedAt", now)
                        .execute();
                } else {
                    handle.createUpdate(
                        """
                        UPDATE library_items
                        SET library_id = :libraryId,
                            parent_item_id = :parentItemId,
                            source_entry_id = :sourceEntryId,
                            source_object_id = :sourceObjectId,
                            item_type = :itemType,
                            identity_key = :identityKey,
                            title = :title,
                            media_kind = :mediaKind,
                            primary_path = :primaryPath,
                            unmatched_reason = :unmatchedReason,
                            season_number = :seasonNumber,
                            episode_number = :episodeNumber,
                            track_number = :trackNumber,
                            updated_at = :updatedAt
                        WHERE library_item_id = :libraryItemId
                        """
                    )
                        .bind("libraryId", normalizedLibraryId)
                        .bind(
                            "parentItemId",
                            descriptor.parentIdentityKey() == null
                                ? null
                                : idByIdentityKey.get(descriptor.parentIdentityKey())
                        )
                        .bind("sourceEntryId", descriptor.sourceEntryId())
                        .bind("sourceObjectId", descriptor.sourceObjectId())
                        .bind("itemType", descriptor.type().name())
                        .bind("identityKey", descriptor.identityKey())
                        .bind("title", descriptor.title())
                        .bind("mediaKind", descriptor.mediaKind() == null ? null : descriptor.mediaKind().name())
                        .bind("primaryPath", descriptor.primaryPath())
                        .bind("unmatchedReason", descriptor.unmatchedReason())
                        .bind("seasonNumber", descriptor.seasonNumber())
                        .bind("episodeNumber", descriptor.episodeNumber())
                        .bind("trackNumber", descriptor.trackNumber())
                        .bind("updatedAt", now)
                        .bind("libraryItemId", persistedItemId)
                        .execute();
                }
            }

            if (desiredIdentityKeys.isEmpty()) {
                handle.createUpdate("DELETE FROM library_items WHERE library_id = :libraryId")
                    .bind("libraryId", normalizedLibraryId)
                    .execute();
            } else {
                handle.createUpdate(
                    """
                    DELETE FROM library_items
                    WHERE library_id = :libraryId
                      AND identity_key NOT IN (<identityKeys>)
                    """
                )
                    .bind("libraryId", normalizedLibraryId)
                    .bindList("identityKeys", desiredIdentityKeys)
                    .execute();
            }
            return null;
        });

        return listLibraryItems(normalizedLibraryId);
    }

    public LibraryItemListing listLibraryItems(String libraryId) {
        return listLibraryItems(libraryId, null);
    }

    public LibraryItemListing listLibraryItems(String libraryId, String parentItemId) {
        String normalizedLibraryId = libraryId.trim();
        List<LibraryItem> items = loadLibraryItems(normalizedLibraryId);
        List<LibraryItem> filteredItems = items.stream()
            .filter(item -> Objects.equals(item.parentItemId(), parentItemId))
            .sorted(
                Comparator
                    .comparing((LibraryItem item) -> item.title().toLowerCase(Locale.ROOT))
                    .thenComparing(LibraryItem::libraryItemId)
            )
            .toList();
        return new LibraryItemListing(filteredItems, filteredItems.size());
    }

    public LibraryItem getLibraryItem(String libraryId, String itemId) {
        String normalizedLibraryId = libraryId.trim();
        String normalizedItemId = itemId.trim();
        for (LibraryItem item : loadLibraryItems(normalizedLibraryId)) {
            if (item.libraryItemId().equals(normalizedItemId)) {
                return item;
            }
        }
        return null;
    }

    private List<LibraryItem> loadLibraryItems(String libraryId) {
        List<PersistedLibraryItemRow> rows = withHandleUnchecked(jdbi, handle ->
            handle.createQuery(
                """
                SELECT library_item_id, library_id, parent_item_id, source_entry_id, source_object_id,
                       item_type, identity_key, title, media_kind, primary_path, unmatched_reason,
                       season_number, episode_number, track_number, created_at, updated_at
                FROM library_items
                WHERE library_id = :libraryId
                ORDER BY title ASC
                """
            )
                .bind("libraryId", libraryId)
                .map((resultSet, ignored) -> toLibraryItemRow(resultSet))
                .list()
        );

        Map<String, Integer> childCounts = new HashMap<>();
        for (PersistedLibraryItemRow row : rows) {
            if (row.parentItemId() != null) {
                childCounts.merge(row.parentItemId(), 1, Integer::sum);
            }
        }

        List<LibraryItem> items = new ArrayList<>(rows.size());
        for (PersistedLibraryItemRow row : rows) {
            items.add(row.toLibraryItem(childCounts.getOrDefault(row.libraryItemId(), 0)));
        }
        return items;
    }

    private List<PersistedLibraryItemIdentity> loadExistingItems(String libraryId) {
        return withHandleUnchecked(jdbi, handle ->
            handle.createQuery(
                """
                SELECT library_item_id, identity_key, created_at
                FROM library_items
                WHERE library_id = :libraryId
                """
            )
                .bind("libraryId", libraryId)
                .map((resultSet, ignored) -> new PersistedLibraryItemIdentity(
                    resultSet.getString("library_item_id"),
                    resultSet.getString("identity_key"),
                    resultSet.getString("created_at")
                ))
                .list()
        );
    }

    private List<InterpretableLibraryEntry> loadActiveEntries(String libraryId) {
        return withHandleUnchecked(jdbi, handle ->
            handle.createQuery(
                """
                SELECT library_entry_id, library_id, source_root_id, object_id, media_kind, primary_path
                FROM library_entries
                WHERE library_id = :libraryId
                  AND status = :status
                ORDER BY primary_path ASC
                """
            )
                .bind("libraryId", libraryId)
                .bind("status", LibraryTrackedObjectStatus.ACTIVE.name())
                .map((resultSet, ignored) -> new InterpretableLibraryEntry(
                    resultSet.getString("library_entry_id"),
                    resultSet.getString("library_id"),
                    resultSet.getString("source_root_id"),
                    resultSet.getString("object_id"),
                    MediaKind.valueOf(resultSet.getString("media_kind")),
                    resultSet.getString("primary_path")
                ))
                .list()
        );
    }

    private LinkedHashMap<String, LibraryItemDescriptor> buildDesiredItems(
        Library library,
        List<InterpretableLibraryEntry> entries
    ) {
        Map<String, LibrarySourceRoot> sourceRootsById = new HashMap<>();
        for (LibrarySourceRoot sourceRoot : library.sourceRoots()) {
            sourceRootsById.put(sourceRoot.sourceRootId(), sourceRoot);
        }

        LinkedHashMap<String, LibraryItemDescriptor> desired = new LinkedHashMap<>();
        LibraryItemBuilder builder = itemBuilders.stream()
            .filter(candidate -> candidate.supports(library))
            .findFirst()
            .orElse(null);
        if (builder == null) {
            return desired;
        }

        for (InterpretableLibraryEntry entry : entries) {
            MediaObject mediaObject = mediaObjectService.getByObjectId(entry.objectId());
            if (mediaObject == null) {
                continue;
            }

            LibrarySourceRoot sourceRoot = sourceRootsById.get(entry.sourceRootId());
            LibraryItemBuildContext context = new LibraryItemBuildContext(
                library,
                sourceRoot,
                entry.libraryEntryId(),
                entry.objectId(),
                entry.mediaKind(),
                entry.primaryPath(),
                mediaObject
            );
            for (LibraryItemDescriptor descriptor : builder.buildItems(context)) {
                if (descriptor.sourceEntryId() == null) {
                    desired.putIfAbsent(descriptor.identityKey(), descriptor);
                } else {
                    desired.put(descriptor.identityKey(), descriptor);
                }
            }
        }
        return desired;
    }

    private static List<LibraryItemBuilder> defaultItemBuilders() {
        return List.of(
            new TypedLeafLibraryItemBuilder(),
            new ShowLibraryItemBuilder(),
            new MusicLibraryItemBuilder()
        );
    }

    private static PersistedLibraryItemRow toLibraryItemRow(ResultSet resultSet) throws SQLException {
        return new PersistedLibraryItemRow(
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

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record InterpretableLibraryEntry(
        String libraryEntryId,
        String libraryId,
        String sourceRootId,
        String objectId,
        MediaKind mediaKind,
        String primaryPath
    ) {
        private String identityKey() {
            return "entry:" + libraryEntryId;
        }
    }

    private record PersistedLibraryItemIdentity(
        String libraryItemId,
        String identityKey,
        String createdAt
    ) {
    }

    private record PersistedLibraryItemRow(
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

}
