package com.nyx.browse;

import static com.nyx.common.RouteUtilsJava.pageEndIndex;
import static com.nyx.common.RouteUtilsJava.pageStartIndex;

import com.nyx.common.MediaTypes;
import com.nyx.common.PathSecurity;
import com.nyx.common.VirtualPathResolver;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.media.AudioMetadata;
import com.nyx.media.AudioMetadataService;
import com.nyx.media.ImageViewingMetadataFactory;
import com.nyx.media.MediaFileService;
import com.nyx.media.MediaObjectResolveOptions;
import com.nyx.media.MediaObjectResolver;
import com.nyx.media.contracts.BrowseListing;
import com.nyx.media.contracts.FileSearchResult;
import com.nyx.media.contracts.ImageDimensions;
import com.nyx.media.contracts.MediaCapabilityHints;
import com.nyx.media.contracts.MediaItem;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaThumbnailReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BrowseService {
    public static final int MAX_SEARCH_DEPTH = 20;
    public static final int MAX_SEARCH_RESULTS = 10_000;

    private static final Logger LOG = LoggerFactory.getLogger(BrowseService.class);

    private final VirtualPathResolver virtualPathResolver;
    private final PathSecurity pathSecurity;
    private final List<Integer> thumbnailSizes;
    private final AudioMetadataService audioMetadataService;
    private final MediaObjectResolver mediaObjectResolver;
    private final ExecutorService backgroundExecutor;

    public BrowseService(
        VirtualPathResolver virtualPathResolver,
        PathSecurity pathSecurity,
        List<Integer> thumbnailSizes,
        MediaProber probeService
    ) {
        this(virtualPathResolver, pathSecurity, thumbnailSizes, probeService, null, null, null);
    }

    public BrowseService(
        VirtualPathResolver virtualPathResolver,
        PathSecurity pathSecurity,
        List<Integer> thumbnailSizes,
        MediaProber probeService,
        AudioMetadataService audioMetadataService
    ) {
        this(virtualPathResolver, pathSecurity, thumbnailSizes, probeService, audioMetadataService, null, null);
    }

    public BrowseService(
        VirtualPathResolver virtualPathResolver,
        PathSecurity pathSecurity,
        List<Integer> thumbnailSizes,
        MediaProber probeService,
        AudioMetadataService audioMetadataService,
        MediaObjectResolver mediaObjectResolver
    ) {
        this(virtualPathResolver, pathSecurity, thumbnailSizes, probeService, audioMetadataService, mediaObjectResolver, null);
    }

    public BrowseService(
        VirtualPathResolver virtualPathResolver,
        PathSecurity pathSecurity,
        List<Integer> thumbnailSizes,
        MediaProber probeService,
        AudioMetadataService audioMetadataService,
        MediaObjectResolver mediaObjectResolver,
        ExecutorService backgroundExecutor
    ) {
        this.virtualPathResolver = Objects.requireNonNull(virtualPathResolver, "virtualPathResolver");
        this.pathSecurity = Objects.requireNonNull(pathSecurity, "pathSecurity");
        this.thumbnailSizes = List.copyOf(thumbnailSizes);
        this.audioMetadataService = audioMetadataService != null
            ? audioMetadataService
            : probeService != null ? new AudioMetadataService(probeService) : null;
        this.mediaObjectResolver = mediaObjectResolver;
        this.backgroundExecutor = backgroundExecutor;
    }

    public List<MediaItem.Folder> listRoots() {
        return virtualPathResolver.getRoots().stream()
            .map(root -> new MediaItem.Folder(
                root.getDisplayName(),
                root.getDisplayName(),
                0L,
                null
            ))
            .toList();
    }

    public BrowseListing browse(String virtualPath, int page, int limit) {
        return browse(virtualPath, page, limit, BrowseSortOrder.NAME);
    }

    public BrowseListing browse(String virtualPath, int page, int limit, BrowseSortOrder sort) {
        if (virtualPath.isBlank()) {
            List<MediaItem.Folder> roots = listRoots();
            return new BrowseListing(new ArrayList<>(roots), roots.size(), 1, limit);
        }

        Path absoluteDir = virtualPathResolver.resolveToAbsolute(virtualPath);
        pathSecurity.validateDirectory(absoluteDir.toString());

        List<Path> entries;
        try (Stream<Path> stream = Files.list(absoluteDir)) {
            entries = stream.toList();
        } catch (IOException exception) {
            return sneakyThrow(exception);
        }

        List<BrowseCandidate> candidates = parallelMap(entries, this::inspectEntry)
            .stream()
            .filter(Objects::nonNull)
            .toList();

        List<BrowseCandidate> sorted = sortCandidates(candidates, sort);
        int total = sorted.size();
        int start = pageStartIndex(page, limit, total);
        int end = pageEndIndex(start, limit, total);
        List<MediaItem> pageItems = parallelMap(sorted.subList(start, end), this::hydrateCandidate);

        return new BrowseListing(pageItems, total, page, limit);
    }

    public FileSearchResult searchFiles(String query, int page, int limit) {
        return searchFiles(query, page, limit, null);
    }

    public FileSearchResult searchFiles(String query, int page, int limit, MediaTypeFilter typeFilter) {
        String lowerQuery = query.toLowerCase();
        List<BrowseCandidate> matches = new ArrayList<>();

        for (VirtualPathResolver.VirtualRoot root : virtualPathResolver.getRoots()) {
            Path rootPath;
            try {
                rootPath = root.getPhysicalPath().toRealPath();
            } catch (Exception ignored) {
                continue;
            }

            try (Stream<Path> stream = Files.walk(rootPath, MAX_SEARCH_DEPTH)) {
                var iterator = stream.iterator();
                while (iterator.hasNext() && matches.size() < MAX_SEARCH_RESULTS) {
                    Path path = iterator.next();
                    if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase().contains(lowerQuery)) {
                        continue;
                    }

                    BrowseCandidate candidate = inspectEntry(path);
                    if (candidate != null && matchesFilter(candidate, typeFilter)) {
                        matches.add(candidate);
                    }
                }
            } catch (Exception exception) {
                LOG.warn("Error walking root {}: {}", root.getDisplayName(), exception.getMessage());
            }
        }

        List<BrowseCandidate> sorted = matches.stream()
            .sorted(Comparator.comparing(candidate -> candidate.name().toLowerCase()))
            .toList();
        int total = sorted.size();
        int start = pageStartIndex(page, limit, total);
        int end = pageEndIndex(start, limit, total);
        List<MediaItem> pageItems = parallelMap(sorted.subList(start, end), this::hydrateCandidate);

        return new FileSearchResult(pageItems, total, page, limit, query);
    }

    private BrowseCandidate inspectEntry(Path path) {
        String virtualPath = virtualPathResolver.toVirtualPath(path);
        if (virtualPath == null) {
            return null;
        }

        String fileName = path.getFileName().toString();
        Long modifiedAt = null;
        try {
            modifiedAt = Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
        }

        if (Files.isDirectory(path)) {
            return new BrowseCandidate(path, virtualPath, fileName, 0L, modifiedAt, null, BrowseCandidateKind.FOLDER);
        }

        if (!Files.isRegularFile(path)) {
            return null;
        }

        String mimeType = MediaTypes.detectMimeType(path);
        long fileSize;
        try {
            fileSize = Files.size(path);
        } catch (Exception ignored) {
            fileSize = 0L;
        }

        if (MediaTypes.isImage(mimeType)) {
            return new BrowseCandidate(path, virtualPath, fileName, fileSize, modifiedAt, mimeType, BrowseCandidateKind.IMAGE);
        }
        if (MediaTypes.isAudio(mimeType)) {
            return new BrowseCandidate(path, virtualPath, fileName, fileSize, modifiedAt, mimeType, BrowseCandidateKind.AUDIO);
        }
        if (MediaTypes.isVideo(mimeType)) {
            return new BrowseCandidate(path, virtualPath, fileName, fileSize, modifiedAt, mimeType, BrowseCandidateKind.VIDEO);
        }
        return null;
    }

    private MediaItem hydrateCandidate(BrowseCandidate candidate) {
        return switch (candidate.kind()) {
            case FOLDER -> new MediaItem.Folder(
                candidate.name(),
                candidate.virtualPath(),
                0L,
                candidate.modifiedAt()
            );
            case IMAGE -> {
                ImageDimensions dimensions = MediaFileService.Companion.readImageDimensions(candidate.path());
                MediaObject mediaObject = resolveOrCreate(candidate.path());
                MediaThumbnailReference primaryThumbnail = mediaObject == null
                    ? null
                    : mediaObjectResolver.primaryThumbnailReference(mediaObject);
                String mimeType = candidate.mimeType() == null ? MediaTypes.APPLICATION_OCTET_STREAM : candidate.mimeType();
                yield new MediaItem.Image(
                    candidate.name(),
                    candidate.virtualPath(),
                    candidate.size(),
                    mediaObject == null ? null : mediaObject.objectId(),
                    mediaObject == null ? MediaKind.IMAGE : mediaObject.mediaKind(),
                    primaryThumbnail,
                    mimeType,
                    dimensions == null ? null : dimensions.width(),
                    dimensions == null ? null : dimensions.height(),
                    toModifiedInstant(candidate.path()),
                    thumbnailSizes,
                    ImageViewingMetadataFactory.buildImageViewingMetadata(),
                    candidate.modifiedAt(),
                    new MediaCapabilityHints(
                        true,
                        false,
                        null,
                        mimeType,
                        mediaObject == null ? null : mediaObject.objectId()
                    )
                );
            }
            case AUDIO -> {
                AudioMetadata metadata = probeAudioMetadata(candidate.path());
                MediaObject mediaObject = resolveOrCreate(candidate.path());
                MediaThumbnailReference primaryThumbnail = mediaObject == null
                    ? null
                    : mediaObjectResolver.primaryThumbnailReference(mediaObject, null);
                String mimeType = candidate.mimeType() == null ? MediaTypes.APPLICATION_OCTET_STREAM : candidate.mimeType();
                yield new MediaItem.Music(
                    candidate.name(),
                    candidate.virtualPath(),
                    candidate.size(),
                    mediaObject == null ? null : mediaObject.objectId(),
                    mediaObject == null ? MediaKind.AUDIO : mediaObject.mediaKind(),
                    primaryThumbnail,
                    mimeType,
                    metadata == null ? null : metadata.getDuration(),
                    metadata == null ? null : metadata.getBitrate(),
                    metadata == null ? null : metadata.getChannels(),
                    metadata == null ? null : metadata.getArtist(),
                    metadata == null ? null : metadata.getAlbum(),
                    metadata == null ? null : metadata.getTitle(),
                    candidate.modifiedAt(),
                    new MediaCapabilityHints(
                        true,
                        false,
                        metadata == null ? null : metadata.getDuration(),
                        mimeType,
                        mediaObject == null ? null : mediaObject.objectId()
                    )
                );
            }
            case VIDEO -> {
                MediaObject mediaObject = resolveOrCreate(candidate.path());
                MediaThumbnailReference primaryThumbnail = mediaObject == null
                    ? null
                    : mediaObjectResolver.primaryThumbnailReference(mediaObject);
                String mimeType = candidate.mimeType() == null ? MediaTypes.APPLICATION_OCTET_STREAM : candidate.mimeType();
                Double durationSeconds = durationSeconds(mediaObject);
                yield new MediaItem.Video(
                    candidate.name(),
                    candidate.virtualPath(),
                    candidate.size(),
                    mediaObject == null ? null : mediaObject.objectId(),
                    mediaObject == null ? MediaKind.VIDEO : mediaObject.mediaKind(),
                    primaryThumbnail,
                    mimeType,
                    ImageViewingMetadataFactory.buildVideoViewingMetadata(),
                    candidate.modifiedAt(),
                    thumbnailSizes,
                    new MediaCapabilityHints(
                        false,
                        true,
                        durationSeconds,
                        mimeType,
                        mediaObject == null ? null : mediaObject.objectId()
                    )
                );
            }
        };
    }

    private AudioMetadata probeAudioMetadata(Path path) {
        return audioMetadataService == null ? null : audioMetadataService.get(path);
    }

    private <I, O> List<O> parallelMap(List<I> inputs, Function<I, O> mapper) {
        if (backgroundExecutor == null) {
            return inputs.stream().map(mapper).toList();
        }

        List<Future<O>> futures;
        try {
            futures = backgroundExecutor.invokeAll(inputs.stream()
                .<Callable<O>>map(input -> () -> mapper.apply(input))
                .toList());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return sneakyThrow(exception);
        }

        List<O> results = new ArrayList<>(futures.size());
        for (Future<O> future : futures) {
            try {
                results.add(future.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return sneakyThrow(exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                return sneakyThrow(cause == null ? exception : cause);
            }
        }
        return results;
    }

    private static Double durationSeconds(MediaObject mediaObject) {
        if (mediaObject == null || mediaObject.durationMillis() == null || mediaObject.durationMillis() <= 0L) {
            return null;
        }
        return mediaObject.durationMillis() / 1000.0d;
    }

    private boolean matchesFilter(BrowseCandidate candidate, MediaTypeFilter filter) {
        if (filter == null) {
            return true;
        }
        return switch (filter) {
            case IMAGE -> candidate.kind() == BrowseCandidateKind.IMAGE;
            case MUSIC -> candidate.kind() == BrowseCandidateKind.AUDIO;
            case VIDEO -> candidate.kind() == BrowseCandidateKind.VIDEO;
        };
    }

    private List<BrowseCandidate> sortCandidates(List<BrowseCandidate> items, BrowseSortOrder sort) {
        Comparator<BrowseCandidate> folderFirst = Comparator.comparing(candidate -> candidate.kind() != BrowseCandidateKind.FOLDER);
        return switch (sort) {
            case NAME -> items.stream()
                .sorted(folderFirst.thenComparing(candidate -> candidate.name().toLowerCase()))
                .toList();
            case DATE -> items.stream()
                .sorted(folderFirst.thenComparing(BrowseCandidate::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
            case SIZE -> items.stream()
                .sorted(folderFirst.thenComparing(BrowseCandidate::size, Comparator.reverseOrder()))
                .toList();
        };
    }

    private MediaObject resolveOrCreate(Path path) {
        if (mediaObjectResolver == null) {
            return null;
        }
        return mediaObjectResolver.resolveOrCreate(path, MediaObjectResolveOptions.DEFAULT);
    }

    private String toModifiedInstant(Path path) {
        try {
            FileTime modifiedTime = Files.getLastModifiedTime(path);
            return modifiedTime.toInstant().toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private enum BrowseCandidateKind {
        FOLDER,
        IMAGE,
        AUDIO,
        VIDEO
    }

    private record BrowseCandidate(
        Path path,
        String virtualPath,
        String name,
        long size,
        Long modifiedAt,
        String mimeType,
        BrowseCandidateKind kind
    ) {}
}
