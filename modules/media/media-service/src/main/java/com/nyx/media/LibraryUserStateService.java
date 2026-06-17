package com.nyx.media;

import static com.nyx.common.RouteUtilsJava.pageEndIndex;
import static com.nyx.common.RouteUtilsJava.pageStartIndex;

import com.nyx.common.ErrorCode;
import com.nyx.common.NyxException;
import com.nyx.media.contracts.LibraryItem;
import com.nyx.media.contracts.LibraryItemUserState;
import com.nyx.media.contracts.LibraryItemUserStateEntry;
import com.nyx.media.contracts.LibraryItemUserStateListing;
import com.nyx.media.contracts.UserMediaState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class LibraryUserStateService {
    private final LibraryCatalogService libraryCatalogService;
    private final UserMediaStateService userMediaStateService;

    public LibraryUserStateService(
        LibraryCatalogService libraryCatalogService,
        UserMediaStateService userMediaStateService
    ) {
        this.libraryCatalogService = Objects.requireNonNull(libraryCatalogService, "libraryCatalogService");
        this.userMediaStateService = Objects.requireNonNull(userMediaStateService, "userMediaStateService");
    }

    public LibraryItemUserStateListing listItemStates(String libraryId, String userId) {
        return listItemStates(libraryId, userId, null, 1, 50);
    }

    public LibraryItemUserStateListing listItemStates(String libraryId, String userId, String parentItemId) {
        return listItemStates(libraryId, userId, parentItemId, 1, 50);
    }

    public LibraryItemUserStateListing listItemStates(String libraryId, String userId, String parentItemId, int page) {
        return listItemStates(libraryId, userId, parentItemId, page, 50);
    }

    public LibraryItemUserStateListing listItemStates(
        String libraryId,
        String userId,
        String parentItemId,
        int page,
        int limit
    ) {
        List<LibraryItemUserStateEntry> projected = projectLibraryStates(libraryId.trim(), userId)
            .values()
            .stream()
            .filter(entry -> Objects.equals(entry.item().parentItemId(), parentItemId))
            .sorted(
                Comparator
                    .comparing(
                        (LibraryItemUserStateEntry entry) -> effectiveSortValue(entry.item()).toLowerCase(Locale.ROOT)
                    )
                    .thenComparing(entry -> entry.item().libraryItemId())
            )
            .toList();
        return toListing(projected, page, limit);
    }

    public LibraryItemUserStateEntry getItemState(String libraryId, String userId, String itemId) {
        return projectLibraryStates(libraryId.trim(), userId).get(itemId.trim());
    }

    public LibraryItemUserStateListing listFavorites(String libraryId, String userId) {
        return listFavorites(libraryId, userId, 1, 50);
    }

    public LibraryItemUserStateListing listFavorites(String libraryId, String userId, int page) {
        return listFavorites(libraryId, userId, page, 50);
    }

    public LibraryItemUserStateListing listFavorites(String libraryId, String userId, int page, int limit) {
        List<LibraryItemUserStateEntry> projected = projectLibraryStates(libraryId.trim(), userId)
            .values()
            .stream()
            .filter(entry -> entry.item().sourceObjectId() != null && entry.state().favorite())
            .sorted(
                Comparator
                    .comparing(
                        (LibraryItemUserStateEntry entry) -> entry.state().lastInteractionAt(),
                        Comparator.nullsFirst(Comparator.naturalOrder())
                    )
                    .reversed()
                    .thenComparing(entry -> effectiveSortValue(entry.item()).toLowerCase(Locale.ROOT))
            )
            .toList();
        return toListing(projected, page, limit);
    }

    public LibraryItemUserStateListing listWatched(String libraryId, String userId) {
        return listWatched(libraryId, userId, 1, 50);
    }

    public LibraryItemUserStateListing listWatched(String libraryId, String userId, int page) {
        return listWatched(libraryId, userId, page, 50);
    }

    public LibraryItemUserStateListing listWatched(String libraryId, String userId, int page, int limit) {
        List<LibraryItemUserStateEntry> projected = projectLibraryStates(libraryId.trim(), userId)
            .values()
            .stream()
            .filter(entry -> entry.item().sourceObjectId() != null && entry.state().watched())
            .sorted(
                Comparator
                    .comparing(
                        (LibraryItemUserStateEntry entry) -> playedOrInteraction(entry.state()),
                        Comparator.nullsFirst(Comparator.naturalOrder())
                    )
                    .reversed()
                    .thenComparing(entry -> effectiveSortValue(entry.item()).toLowerCase(Locale.ROOT))
            )
            .toList();
        return toListing(projected, page, limit);
    }

    public LibraryItemUserStateListing listResume(String libraryId, String userId) {
        return listResume(libraryId, userId, 1, 50);
    }

    public LibraryItemUserStateListing listResume(String libraryId, String userId, int page) {
        return listResume(libraryId, userId, page, 50);
    }

    public LibraryItemUserStateListing listResume(String libraryId, String userId, int page, int limit) {
        List<LibraryItemUserStateEntry> projected = projectLibraryStates(libraryId.trim(), userId)
            .values()
            .stream()
            .filter(entry ->
                entry.item().sourceObjectId() != null
                    && !entry.state().watched()
                    && entry.state().resumePositionMillis() != null
            )
            .sorted(
                Comparator
                    .comparing(
                        (LibraryItemUserStateEntry entry) -> playedOrInteraction(entry.state()),
                        Comparator.nullsFirst(Comparator.naturalOrder())
                    )
                    .reversed()
                    .thenComparing(entry -> effectiveSortValue(entry.item()).toLowerCase(Locale.ROOT))
            )
            .toList();
        return toListing(projected, page, limit);
    }

    public LibraryItemUserStateListing listContinueWatching(String libraryId, String userId) {
        return listContinueWatching(libraryId, userId, 1, 50);
    }

    public LibraryItemUserStateListing listContinueWatching(String libraryId, String userId, int page) {
        return listContinueWatching(libraryId, userId, page, 50);
    }

    public LibraryItemUserStateListing listContinueWatching(String libraryId, String userId, int page, int limit) {
        return listResume(libraryId, userId, page, limit);
    }

    private Map<String, LibraryItemUserStateEntry> projectLibraryStates(String libraryId, String userId) {
        List<LibraryItem> items = libraryCatalogService.listAllLibraryItems(libraryId);
        if (items.isEmpty()) {
            return Map.of();
        }

        Map<String, LibraryItem> itemsById = items.stream().collect(
            Collectors.toMap(LibraryItem::libraryItemId, Function.identity(), (left, right) -> left, LinkedHashMap::new)
        );
        Map<String, List<LibraryItem>> childrenByParent = groupChildrenByParent(items);
        List<LibraryItem> leafItems = items.stream().filter(item -> item.sourceObjectId() != null).toList();
        Set<String> objectIds = leafItems.stream()
            .map(LibraryItem::sourceObjectId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<String, UserMediaState> stateByObjectId = userMediaStateService.listStatesForObjects(userId, objectIds)
            .stream()
            .collect(Collectors.toMap(UserMediaState::objectId, Function.identity()));
        Map<String, List<LibraryItem>> leafMemo = new HashMap<>();
        Map<String, LibraryItemUserStateEntry> projected = new LinkedHashMap<>();

        for (LibraryItem item : items) {
            List<LibraryItem> leaves = descendantLeaves(item.libraryItemId(), itemsById, childrenByParent, leafMemo);
            List<LeafStatePair> statePairs = new ArrayList<>(leaves.size());
            for (LibraryItem leaf : leaves) {
                String objectId = leaf.sourceObjectId();
                if (objectId == null) {
                    throw new IllegalStateException(
                        "Leaf library item missing source object id: " + leaf.libraryItemId()
                    );
                }
                statePairs.add(new LeafStatePair(leaf, stateByObjectId.getOrDefault(objectId, new UserMediaState(userId, objectId))));
            }
            projected.put(item.libraryItemId(), new LibraryItemUserStateEntry(item, summarizeState(statePairs)));
        }

        return projected;
    }

    private List<LibraryItem> descendantLeaves(
        String itemId,
        Map<String, LibraryItem> itemsById,
        Map<String, List<LibraryItem>> childrenByParent,
        Map<String, List<LibraryItem>> leafMemo
    ) {
        List<LibraryItem> cached = leafMemo.get(itemId);
        if (cached != null) {
            return cached;
        }

        LibraryItem item = itemsById.get(itemId);
        if (item == null) {
            throw sneakyThrow(new NyxException(ErrorCode.LIBRARY_ITEM_NOT_FOUND, "Library item not found: " + itemId));
        }

        List<LibraryItem> leaves;
        if (item.sourceObjectId() != null) {
            leaves = List.of(item);
        } else {
            List<LibraryItem> collected = new ArrayList<>();
            for (LibraryItem child : childrenByParent.getOrDefault(itemId, List.of())) {
                collected.addAll(descendantLeaves(child.libraryItemId(), itemsById, childrenByParent, leafMemo));
            }
            leaves = List.copyOf(collected);
        }
        leafMemo.put(itemId, leaves);
        return leaves;
    }

    private Map<String, List<LibraryItem>> groupChildrenByParent(List<LibraryItem> items) {
        Map<String, List<LibraryItem>> childrenByParent = new LinkedHashMap<>();
        for (LibraryItem item : items) {
            childrenByParent.computeIfAbsent(item.parentItemId(), ignored -> new ArrayList<>()).add(item);
        }
        return childrenByParent;
    }

    private LibraryItemUserState summarizeState(List<LeafStatePair> statePairs) {
        if (statePairs.isEmpty()) {
            return new LibraryItemUserState();
        }

        int watchedLeafCount = 0;
        int favoriteLeafCount = 0;
        int playCount = 0;
        List<LeafStatePair> inProgress = new ArrayList<>();
        String lastPlayedAt = null;
        String lastInteractionAt = null;

        for (LeafStatePair pair : statePairs) {
            UserMediaState state = pair.state();
            if (state.watched()) {
                watchedLeafCount++;
            }
            if (state.favorite()) {
                favoriteLeafCount++;
            }
            if (!state.watched() && state.resumePositionMillis() != null) {
                inProgress.add(pair);
            }
            playCount += state.playCount();
            if (state.lastPlayedAt() != null && (lastPlayedAt == null || state.lastPlayedAt().compareTo(lastPlayedAt) > 0)) {
                lastPlayedAt = state.lastPlayedAt();
            }
            if (state.lastInteractionAt() != null
                && (lastInteractionAt == null || state.lastInteractionAt().compareTo(lastInteractionAt) > 0)) {
                lastInteractionAt = state.lastInteractionAt();
            }
        }

        LeafStatePair resumeCandidate = inProgress.stream()
            .max(Comparator.comparing(pair -> resumeSortKey(pair.state())))
            .orElse(null);

        return new LibraryItemUserState(
            watchedLeafCount == statePairs.size(),
            favoriteLeafCount > 0,
            resumeCandidate == null ? null : resumeCandidate.state().resumePositionMillis(),
            playCount,
            lastPlayedAt,
            lastInteractionAt,
            statePairs.size(),
            watchedLeafCount,
            favoriteLeafCount,
            inProgress.size(),
            resumeCandidate == null ? null : resumeCandidate.item().libraryItemId(),
            resumeCandidate == null ? null : resumeCandidate.item().sourceObjectId()
        );
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

    private LibraryItemUserStateListing toListing(List<LibraryItemUserStateEntry> entries, int page, int limit) {
        int total = entries.size();
        int start = pageStartIndex(page, limit, total);
        int end = pageEndIndex(start, limit, total);
        return new LibraryItemUserStateListing(entries.subList(start, end), total, page, limit);
    }

    private static String playedOrInteraction(LibraryItemUserState state) {
        return state.lastPlayedAt() != null ? state.lastPlayedAt() : state.lastInteractionAt();
    }

    private static String resumeSortKey(UserMediaState state) {
        if (state.lastPlayedAt() != null) {
            return state.lastPlayedAt();
        }
        if (state.lastInteractionAt() != null) {
            return state.lastInteractionAt();
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record LeafStatePair(
        LibraryItem item,
        UserMediaState state
    ) {
    }
}
