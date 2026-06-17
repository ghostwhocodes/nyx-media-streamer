package com.nyx.qloud;

import static com.nyx.common.RouteUtilsJava.resolvePathParam;

import com.nyx.browse.BrowseService;
import com.nyx.browse.MediaTypeFilter;
import com.nyx.common.MediaTypes;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

final class QloudRecentMediaMemory {
    private static final int MEMORY_LIMIT = 100;
    private static final int SCAN_MAX_VISITED = 2_000;
    private static final long SCAN_BUDGET_NANOS = Duration.ofMillis(1_500).toNanos();

    private final PathSecurity pathSecurity;
    private final VirtualPathResolver virtualPathResolver;
    private final Map<String, RecentMediaCandidate> byPath = new ConcurrentHashMap<>();
    private final List<String> order = new ArrayList<>();

    QloudRecentMediaMemory(PathSecurity pathSecurity, VirtualPathResolver virtualPathResolver) {
        this.pathSecurity = pathSecurity;
        this.virtualPathResolver = virtualPathResolver;
    }

    List<RecentMediaCandidate> candidates(int limit, MediaTypeFilter filter) {
        List<RecentMediaCandidate> remembered = remembered(limit, filter);
        if (remembered.size() >= limit) {
            return remembered;
        }

        Comparator<RecentMediaCandidate> oldestFirst = Comparator
            .comparing(RecentMediaCandidate::modifiedAt)
            .thenComparing(candidate -> candidate.virtualPath().toLowerCase(Locale.ROOT));
        PriorityQueue<RecentMediaCandidate> candidates = new PriorityQueue<>(oldestFirst);
        remembered.forEach(candidates::add);
        Set<String> seenVirtualPaths = remembered.stream()
            .map(RecentMediaCandidate::virtualPath)
            .collect(java.util.stream.Collectors.toSet());
        long deadlineNanos = System.nanoTime() + SCAN_BUDGET_NANOS;
        int visited = 0;
        for (VirtualPathResolver.VirtualRoot root : virtualPathResolver.getRoots()) {
            try (Stream<Path> stream = Files.walk(root.physicalPath(), BrowseService.MAX_SEARCH_DEPTH)) {
                Iterator<Path> iterator = stream.iterator();
                while (
                    iterator.hasNext()
                        && visited < SCAN_MAX_VISITED
                        && System.nanoTime() < deadlineNanos
                ) {
                    Path path = iterator.next();
                    visited++;
                    if (Files.isRegularFile(path)) {
                        addCandidate(candidates, limit, filter, oldestFirst, seenVirtualPaths, path);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
            if (visited >= SCAN_MAX_VISITED || System.nanoTime() >= deadlineNanos) {
                break;
            }
        }
        return candidates.stream()
            .sorted(oldestFirst.reversed())
            .toList();
    }

    void remember(String nyxPath) {
        try {
            Path absolutePath = resolvePathParam(nyxPath, pathSecurity, virtualPathResolver);
            if (!Files.isRegularFile(absolutePath)) {
                return;
            }
            String virtualPath = virtualPathResolver.toVirtualPath(absolutePath);
            if (virtualPath == null) {
                return;
            }
            RecentMediaCandidate candidate = new RecentMediaCandidate(
                fileName(absolutePath),
                virtualPath,
                fileSize(absolutePath),
                safeLastModified(absolutePath),
                MediaTypes.detectMimeType(absolutePath)
            );
            byPath.put(virtualPath, candidate);
            synchronized (order) {
                order.remove(virtualPath);
                order.add(0, virtualPath);
                while (order.size() > MEMORY_LIMIT) {
                    String removed = order.removeLast();
                    byPath.remove(removed);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private List<RecentMediaCandidate> remembered(int limit, MediaTypeFilter filter) {
        synchronized (order) {
            return order.stream()
                .map(byPath::get)
                .filter(candidate -> candidate != null && matchesMediaFilter(candidate.mimeType(), filter))
                .limit(limit)
                .toList();
        }
    }

    private void addCandidate(
        PriorityQueue<RecentMediaCandidate> candidates,
        int limit,
        MediaTypeFilter filter,
        Comparator<RecentMediaCandidate> oldestFirst,
        Set<String> seenVirtualPaths,
        Path path
    ) {
        try {
            String mimeType = MediaTypes.detectMimeType(path);
            if (!matchesMediaFilter(mimeType, filter)) {
                return;
            }
            String virtualPath = virtualPathResolver.toVirtualPath(path);
            if (virtualPath == null || !seenVirtualPaths.add(virtualPath)) {
                return;
            }
            RecentMediaCandidate candidate = new RecentMediaCandidate(
                fileName(path),
                virtualPath,
                fileSize(path),
                safeLastModified(path),
                mimeType
            );
            if (candidates.size() < limit) {
                candidates.add(candidate);
            } else if (!candidates.isEmpty() && oldestFirst.compare(candidate, candidates.peek()) > 0) {
                candidates.poll();
                candidates.add(candidate);
            }
        } catch (RuntimeException ignored) {
        }
    }

    static boolean matchesMediaFilter(String mimeType, MediaTypeFilter filter) {
        if (filter == null) {
            return true;
        }
        return switch (filter) {
            case IMAGE -> MediaTypes.isImage(mimeType);
            case MUSIC -> MediaTypes.isAudio(mimeType);
            case VIDEO -> MediaTypes.isVideo(mimeType);
        };
    }

    private static long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    record RecentMediaCandidate(
        String name,
        String virtualPath,
        long size,
        long modifiedAt,
        String mimeType
    ) {
    }
}
