package com.nyx.media;

import static com.nyx.common.RouteUtilsJava.pageEndIndex;
import static com.nyx.common.RouteUtilsJava.pageStartIndex;

import com.nyx.common.ErrorCode;
import com.nyx.common.MediaTypes;
import com.nyx.common.NyxException;
import com.nyx.common.VirtualPathResolver;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.MediaProberInterop;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.media.contracts.AudioListing;
import com.nyx.media.contracts.Gallery;
import com.nyx.media.contracts.ImageDimensions;
import com.nyx.media.contracts.MediaCapabilityHints;
import com.nyx.media.contracts.MediaItem;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.SortOrder;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.MediaSourceRef;
import com.nyx.playback.contracts.PlaybackSourceAudioStream;
import com.nyx.playback.contracts.PlaybackSourceCharacteristics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MediaFileService {
    public static final int LARGE_DIR_WARN_THRESHOLD = 5_000;
    public static final Companion Companion = new Companion();

    private static final Logger LOG = LoggerFactory.getLogger(MediaFileService.class);
    private static final List<Integer> DEFAULT_THUMBNAIL_SIZES = List.of(150, 300, 600);
    private static final Set<SortOrder> METADATA_SORT_ORDERS = Set.of(
        SortOrder.ARTIST,
        SortOrder.ALBUM,
        SortOrder.DURATION
    );

    private final List<Integer> thumbnailSizes;
    private final List<Path> mediaRoots;
    private final MediaProber probeService;
    private final VirtualPathResolver virtualPathResolver;
    private final AudioMetadataService audioMetadataService;
    private final MediaObjectResolver mediaObjectResolver;
    private final ExecutorService backgroundExecutor;

    public MediaFileService(MediaProber probeService) {
        this(DEFAULT_THUMBNAIL_SIZES, List.of(), probeService, null, null, null, null, null);
    }

    public MediaFileService(List<Path> mediaRoots, MediaProber probeService) {
        this(DEFAULT_THUMBNAIL_SIZES, mediaRoots, probeService, null, null, null, null, null);
    }

    public MediaFileService(List<Integer> thumbnailSizes, List<Path> mediaRoots, MediaProber probeService) {
        this(thumbnailSizes, mediaRoots, probeService, null, null, null, null, null);
    }

    public MediaFileService(List<Path> mediaRoots, MediaProber probeService, VirtualPathResolver virtualPathResolver) {
        this(DEFAULT_THUMBNAIL_SIZES, mediaRoots, probeService, null, virtualPathResolver, null, null, null);
    }

    public MediaFileService(
        List<Path> mediaRoots,
        MediaProber probeService,
        AudioMetadataService audioMetadataService,
        MediaObjectResolver mediaObjectResolver
    ) {
        this(DEFAULT_THUMBNAIL_SIZES, mediaRoots, probeService, null, null, audioMetadataService, mediaObjectResolver, null);
    }

    public MediaFileService(
        List<Integer> thumbnailSizes,
        List<Path> mediaRoots,
        MediaProber probeService,
        AudioMetadataService audioMetadataService,
        MediaObjectResolver mediaObjectResolver
    ) {
        this(thumbnailSizes, mediaRoots, probeService, null, null, audioMetadataService, mediaObjectResolver, null);
    }

    public MediaFileService(
        List<Integer> thumbnailSizes,
        List<Path> mediaRoots,
        MediaProber probeService,
        FileProbeCache fileProbeCache,
        VirtualPathResolver virtualPathResolver,
        AudioMetadataService audioMetadataService,
        MediaObjectResolver mediaObjectResolver,
        ExecutorService backgroundExecutor
    ) {
        this.thumbnailSizes = thumbnailSizes == null ? DEFAULT_THUMBNAIL_SIZES : List.copyOf(thumbnailSizes);
        this.mediaRoots = mediaRoots == null ? List.of() : List.copyOf(mediaRoots);
        this.probeService = Objects.requireNonNull(probeService, "probeService");
        this.virtualPathResolver = virtualPathResolver;
        this.audioMetadataService = audioMetadataService == null
            ? new AudioMetadataService(probeService, fileProbeCache)
            : audioMetadataService;
        this.mediaObjectResolver = mediaObjectResolver;
        this.backgroundExecutor = backgroundExecutor;
    }

    public Gallery listImages(Path dir, int page, int limit, SortOrder sort) {
        int total = countDirectory(dir, this::isImagePath);
        int pageOffset = pageStartIndex(page, limit, total);
        List<Path> pageItems = streamDirectory(dir, this::isImagePath, fileComparator(sort), pageOffset, limit);
        List<MediaItem.Image> images = new ArrayList<>(pageItems.size());
        for (Path path : pageItems) {
            MediaItem.Image image = toImageItem(path);
            if (image != null) {
                images.add(image);
            }
        }
        LOG.debug("listImages dir={} total={} page={}", dir, total, page);
        return new Gallery(images, total, page, limit);
    }

    public AudioListing listAudio(Path dir, int page, int limit, SortOrder sort) {
        int total = countDirectory(dir, this::isAudioPath);
        if (METADATA_SORT_ORDERS.contains(sort)) {
            if (total > LARGE_DIR_WARN_THRESHOLD) {
                LOG.warn(
                    "listAudio: metadata sort '{}' on large directory {} ({} files) — consider filesystem sort for better performance",
                    sort,
                    dir,
                    total
                );
            }
            List<Path> allFiles = streamDirectory(
                dir,
                this::isAudioPath,
                Comparator.comparing(path -> fileName(path).toLowerCase(Locale.ROOT)),
                0,
                Integer.MAX_VALUE
            );
            List<AudioCandidate> allCandidates = parallelMap(allFiles, this::inspectAudioCandidate)
                .stream()
                .filter(Objects::nonNull)
                .toList();
            List<AudioCandidate> sorted = sortAudioCandidates(allCandidates, sort);
            int pageOffset = pageStartIndex(page, limit, total);
            List<AudioCandidate> pageCandidates = sorted.subList(
                pageOffset,
                pageEndIndex(pageOffset, limit, sorted.size())
            );
            return new AudioListing(parallelMap(pageCandidates, this::toAudioItem), total, page, limit);
        }

        int pageOffset = pageStartIndex(page, limit, total);
        List<Path> pageItems = streamDirectory(dir, this::isAudioPath, fileComparator(sort), pageOffset, limit);
        List<MediaItem.Music> tracks = new ArrayList<>(pageItems.size());
        for (Path path : pageItems) {
            MediaItem.Music audio = toAudioItem(path);
            if (audio != null) {
                tracks.add(audio);
            }
        }
        return new AudioListing(tracks, total, page, limit);
    }

    public AudioNegotiationRequest resolveAudioNegotiationRequest(AudioNegotiationRequest request) {
        if (request.source().characteristics() != null) {
            return request;
        }
        return new AudioNegotiationRequest(
            describeAudioSource(
                Path.of(request.source().path()),
                request.source().objectId(),
                request.source().mediaKind()
            ),
            request.startPositionMillis(),
            request.client(),
            request.capabilities(),
            request.constraints(),
            request.output()
        );
    }

    public MediaSourceRef describeAudioSource(Path path) {
        return describeAudioSource(path, null, null);
    }

    public MediaSourceRef describeAudioSource(Path path, String objectId, MediaKind mediaKind) {
        String mimeType = MediaTypes.detectMimeType(path);
        if (!MediaTypes.isAudio(mimeType)) {
            return sneakyThrow(new NyxException(ErrorCode.AUDIO_NOT_FOUND, "Not an audio file: " + path));
        }

        ProbeResult probe;
        try {
            probe = MediaProberInterop.probeCachedOrThrow(probeService, path);
        } catch (Throwable error) {
            MediaSourceRef fallback = fallbackAudioSource(
                path,
                mimeType,
                "probe failed: " + (error.getMessage() == null ? "unknown error" : error.getMessage()),
                objectId,
                mediaKind
            );
            if (fallback != null) {
                return fallback;
            }
            return sneakyThrow(new NyxException(
                ErrorCode.PROBE_FAILED,
                "Failed to probe audio source '" + path.toAbsolutePath() + "': "
                    + (error.getMessage() == null ? "unknown error" : error.getMessage()),
                error
            ));
        }

        if (probe.getStreams().getAudio().isEmpty()) {
            MediaSourceRef fallback = fallbackAudioSource(
                path,
                mimeType,
                "probe result contained no audio streams",
                objectId,
                mediaKind
            );
            if (fallback != null) {
                return fallback;
            }
            return sneakyThrow(new NyxException(ErrorCode.AUDIO_NOT_FOUND, "No audio streams found for " + path));
        }

        List<PlaybackSourceAudioStream> audioStreams = new ArrayList<>(probe.getStreams().getAudio().size());
        for (AudioStream stream : probe.getStreams().getAudio()) {
            audioStreams.add(new PlaybackSourceAudioStream(
                stream.getIndex(),
                stream.getCodec(),
                stream.getChannels(),
                stream.getBitrateKbps(),
                stream.getSampleRateHz(),
                stream.getLanguage(),
                stream.getTitle()
            ));
        }

        return new MediaSourceRef(
            path.toString(),
            new PlaybackSourceCharacteristics(
                normalizeAudioContainer(path, probe.getFormat()),
                roundDurationMillis(probe.getDurationSecs()),
                probe.getSizeBytes() > 0 ? probe.getSizeBytes() : null,
                List.of(),
                audioStreams,
                List.of()
            ),
            objectId,
            mediaKind == null ? MediaKind.AUDIO : mediaKind
        );
    }

    private MediaSourceRef fallbackAudioSource(
        Path path,
        String mimeType,
        String reason,
        String objectId,
        MediaKind mediaKind
    ) {
        String fileName = fileName(path);
        if (fileName == null) {
            return null;
        }
        int index = fileName.lastIndexOf('.');
        String container = index >= 0 ? fileName.substring(index + 1).trim().toLowerCase(Locale.ROOT) : "";
        if (container.isEmpty()) {
            return null;
        }
        String codec = fallbackCodecForContainer(container);
        if (codec == null) {
            return null;
        }
        Long sizeBytes = safeFileSize(path);
        if (sizeBytes != null && sizeBytes <= 0L) {
            sizeBytes = null;
        }

        LOG.warn(
            "describeAudioSource: falling back to extension-based audio characteristics for {} container={} mime={} reason={}",
            path,
            container,
            mimeType,
            reason
        );

        return new MediaSourceRef(
            path.toString(),
            new PlaybackSourceCharacteristics(
                container,
                null,
                sizeBytes,
                List.of(),
                List.of(new PlaybackSourceAudioStream(0, codec, 2)),
                List.of()
            ),
            objectId,
            mediaKind == null ? MediaKind.AUDIO : mediaKind
        );
    }

    private String fallbackCodecForContainer(String container) {
        return switch (container.toLowerCase(Locale.ROOT)) {
            case "aac", "adts" -> "aac";
            case "aif", "aiff" -> "pcm_s16be";
            case "flac" -> "flac";
            case "m4a", "mp4" -> "aac";
            case "mp3" -> "mp3";
            case "ogg" -> "vorbis";
            case "opus" -> "opus";
            case "wav" -> "pcm_s16le";
            case "wma" -> "wmav2";
            default -> null;
        };
    }

    private String relativePath(Path path) {
        if (virtualPathResolver != null) {
            String virtualPath = virtualPathResolver.toVirtualPath(path);
            if (virtualPath != null) {
                return virtualPath;
            }
        }

        Path absolute = path.toAbsolutePath();
        for (Path root : mediaRoots) {
            Path absoluteRoot = root.toAbsolutePath();
            if (absolute.startsWith(absoluteRoot)) {
                return absoluteRoot.relativize(absolute).toString();
            }
        }
        return fileName(path);
    }

    private MediaItem.Image toImageItem(Path path) {
        String mimeType = MediaTypes.detectMimeType(path);
        String virtualPath = relativePath(path);
        ImageDimensions dimensions = readImageDimensions(path);
        Long modifiedAt = safeLastModifiedMillis(path);
        var mediaObject = mediaObjectResolver == null ? null : mediaObjectResolver.resolveOrCreateBlocking(path);
        var primaryThumbnail = mediaObject == null
            ? null
            : mediaObjectResolver.primaryThumbnailReferenceBlocking(mediaObject);

        return new MediaItem.Image(
            fileName(path),
            virtualPath,
            safeFileSize(path, 0L),
            mediaObject == null ? null : mediaObject.objectId(),
            mediaObject == null ? MediaKind.IMAGE : mediaObject.mediaKind(),
            primaryThumbnail,
            mimeType,
            dimensions == null ? null : dimensions.width(),
            dimensions == null ? null : dimensions.height(),
            safeLastModifiedIso(path),
            thumbnailSizes,
            ImageViewingMetadataFactory.buildImageViewingMetadata(),
            modifiedAt,
            new MediaCapabilityHints(
                true,
                false,
                null,
                mimeType,
                mediaObject == null ? null : mediaObject.objectId()
            )
        );
    }

    private MediaItem.Music toAudioItem(Path path) {
        AudioCandidate candidate = inspectAudioCandidate(path);
        return candidate == null ? null : toAudioItem(candidate);
    }

    private AudioCandidate inspectAudioCandidate(Path path) {
        String mimeType = MediaTypes.detectMimeType(path);
        if (!MediaTypes.isAudio(mimeType)) {
            return null;
        }
        return new AudioCandidate(
            path,
            relativePath(path),
            fileName(path),
            mimeType,
            safeFileSize(path, 0L),
            safeLastModifiedMillis(path),
            audioMetadataService.get(path)
        );
    }

    private MediaItem.Music toAudioItem(AudioCandidate candidate) {
        AudioMetadata metadata = candidate.metadata();
        Path path = candidate.path();
        var mediaObject = mediaObjectResolver == null ? null : mediaObjectResolver.resolveOrCreate(path);
        var primaryThumbnail = mediaObject == null ? null : mediaObjectResolver.primaryThumbnailReference(mediaObject);

        return new MediaItem.Music(
            candidate.name(),
            candidate.relativePath(),
            candidate.size(),
            mediaObject == null ? null : mediaObject.objectId(),
            mediaObject == null ? MediaKind.AUDIO : mediaObject.mediaKind(),
            primaryThumbnail,
            candidate.mimeType(),
            metadata == null ? null : metadata.duration(),
            metadata == null || metadata.bitrate() == null || metadata.bitrate() <= 0L ? null : metadata.bitrate(),
            metadata == null ? null : metadata.channels(),
            metadata == null ? null : metadata.artist(),
            metadata == null ? null : metadata.album(),
            metadata == null ? null : metadata.title(),
            candidate.modifiedAt(),
            new MediaCapabilityHints(
                true,
                false,
                metadata == null ? null : metadata.duration(),
                candidate.mimeType(),
                mediaObject == null ? null : mediaObject.objectId()
            )
        );
    }

    private int countDirectory(Path dir, java.util.function.Predicate<Path> filter) {
        try (var stream = Files.newDirectoryStream(dir)) {
            int count = 0;
            for (Path path : stream) {
                if (filter.test(path)) {
                    count++;
                }
            }
            return count;
        } catch (Exception exception) {
            return sneakyThrow(exception);
        }
    }

    private <I, O> List<O> parallelMap(List<I> inputs, java.util.function.Function<I, O> mapper) {
        if (backgroundExecutor == null) {
            List<O> output = new ArrayList<>(inputs.size());
            for (I input : inputs) {
                output.add(mapper.apply(input));
            }
            return output;
        }

        List<Callable<O>> tasks = new ArrayList<>(inputs.size());
        for (I input : inputs) {
            tasks.add(() -> mapper.apply(input));
        }

        try {
            List<O> output = new ArrayList<>(tasks.size());
            for (var future : backgroundExecutor.invokeAll(tasks)) {
                try {
                    output.add(future.get());
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    return sneakyThrow(cause == null ? exception : cause);
                }
            }
            return output;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return sneakyThrow(exception);
        }
    }

    private List<Path> streamDirectory(
        Path dir,
        java.util.function.Predicate<Path> filter,
        Comparator<Path> comparator,
        int offset,
        int limit
    ) {
        try (var stream = Files.newDirectoryStream(dir)) {
            List<Path> matching = new ArrayList<>();
            for (Path path : stream) {
                if (filter.test(path)) {
                    matching.add(path);
                }
            }
            matching.sort(comparator);
            int start = Math.min(offset, matching.size());
            int end = Math.min(start + limit, matching.size());
            return List.copyOf(matching.subList(start, end));
        } catch (Exception exception) {
            return sneakyThrow(exception);
        }
    }

    private Comparator<Path> fileComparator(SortOrder sort) {
        return switch (sort) {
            case DATE -> Comparator
                .comparingLong((Path path) -> safeLastModifiedMillis(path, 0L))
                .reversed();
            case SIZE -> Comparator
                .comparingLong((Path path) -> safeFileSize(path, 0L))
                .reversed();
            default -> Comparator.comparing(path -> fileName(path).toLowerCase(Locale.ROOT));
        };
    }

    private List<AudioCandidate> sortAudioCandidates(List<AudioCandidate> items, SortOrder sort) {
        Comparator<AudioCandidate> comparator = switch (sort) {
            case ARTIST -> Comparator.comparing(
                candidate -> lowercaseOrNull(candidate.metadata() == null ? null : candidate.metadata().artist()),
                Comparator.nullsLast(Comparator.naturalOrder())
            );
            case ALBUM -> Comparator.comparing(
                candidate -> lowercaseOrNull(candidate.metadata() == null ? null : candidate.metadata().album()),
                Comparator.nullsLast(Comparator.naturalOrder())
            );
            case DURATION -> Comparator.comparing(
                candidate -> candidate.metadata() == null ? null : candidate.metadata().duration(),
                Comparator.nullsLast(Comparator.naturalOrder())
            );
            default -> null;
        };
        if (comparator == null) {
            return items;
        }
        return items.stream().sorted(comparator).toList();
    }

    private String normalizeAudioContainer(Path path, String probedFormat) {
        String fileName = fileName(path);
        if (fileName != null) {
            int index = fileName.lastIndexOf('.');
            if (index >= 0 && index + 1 < fileName.length()) {
                String extension = fileName.substring(index + 1).trim().toLowerCase(Locale.ROOT);
                if (!extension.isEmpty()) {
                    return extension;
                }
            }
        }
        int commaIndex = probedFormat.indexOf(',');
        return (commaIndex >= 0 ? probedFormat.substring(0, commaIndex) : probedFormat)
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    public static ImageDimensions readImageDimensions(Path path) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(path.toFile())) {
            if (stream == null) {
                return null;
            }
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isImagePath(Path path) {
        return Files.isRegularFile(path) && MediaTypes.isImage(MediaTypes.detectMimeType(path));
    }

    private boolean isAudioPath(Path path) {
        return Files.isRegularFile(path) && MediaTypes.isAudio(MediaTypes.detectMimeType(path));
    }

    private static String fileName(Path path) {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    private static String lowercaseOrNull(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static Long roundDurationMillis(double durationSecs) {
        long millis = Math.round(durationSecs * 1000);
        return millis > 0 ? millis : null;
    }

    private static Long safeFileSize(Path path) {
        return safeFileSize(path, null);
    }

    private static Long safeFileSize(Path path, Long fallback) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static Long safeLastModifiedMillis(Path path) {
        return safeLastModifiedMillis(path, null);
    }

    private static Long safeLastModifiedMillis(Path path, Long fallback) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safeLastModifiedIso(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant().toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    public static final class Companion {
        private Companion() {
        }

        public ImageDimensions readImageDimensions(Path path) {
            return MediaFileService.readImageDimensions(path);
        }
    }

    private record AudioCandidate(
        Path path,
        String relativePath,
        String name,
        String mimeType,
        long size,
        Long modifiedAt,
        AudioMetadata metadata
    ) {
    }
}
