package com.nyx.media;

import com.nyx.common.MediaTypes;
import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.MediaProberInterop;
import com.nyx.ffmpeg.model.ProbeResult;
import com.nyx.media.contracts.ImageDimensions;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObject;
import com.nyx.media.contracts.MediaObjectContracts;
import com.nyx.media.contracts.MediaObjectStatus;
import com.nyx.media.contracts.MediaThumbnailReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class MediaObjectResolver {
    private final MediaObjectService mediaObjectService;
    private final MediaProber probeService;
    private final AudioMetadataService audioMetadataService;
    private final MediaThumbnailLifecycle mediaThumbnailLifecycle;
    private final Clock clock;

    public MediaObjectResolver(
        MediaObjectService mediaObjectService,
        MediaProber probeService,
        AudioMetadataService audioMetadataService
    ) {
        this(mediaObjectService, probeService, audioMetadataService, null, null, Clock.systemUTC());
    }

    public MediaObjectResolver(
        MediaObjectService mediaObjectService,
        MediaProber probeService,
        AudioMetadataService audioMetadataService,
        MediaThumbnailLifecycle mediaThumbnailLifecycle
    ) {
        this(mediaObjectService, probeService, audioMetadataService, null, mediaThumbnailLifecycle, Clock.systemUTC());
    }

    public MediaObjectResolver(
        MediaObjectService mediaObjectService,
        MediaProber probeService,
        AudioMetadataService audioMetadataService,
        MediaThumbnailService mediaThumbnailService
    ) {
        this(mediaObjectService, probeService, audioMetadataService, mediaThumbnailService, null, Clock.systemUTC());
    }

    public MediaObjectResolver(
        MediaObjectService mediaObjectService,
        MediaProber probeService,
        AudioMetadataService audioMetadataService,
        MediaThumbnailService mediaThumbnailService,
        MediaThumbnailLifecycle mediaThumbnailLifecycle
    ) {
        this(mediaObjectService, probeService, audioMetadataService, mediaThumbnailService, mediaThumbnailLifecycle, Clock.systemUTC());
    }

    public MediaObjectResolver(
        MediaObjectService mediaObjectService,
        MediaProber probeService,
        AudioMetadataService audioMetadataService,
        MediaThumbnailService mediaThumbnailService,
        MediaThumbnailLifecycle mediaThumbnailLifecycle,
        Clock clock
    ) {
        this.mediaObjectService = Objects.requireNonNull(mediaObjectService, "mediaObjectService");
        this.probeService = Objects.requireNonNull(probeService, "probeService");
        this.audioMetadataService = Objects.requireNonNull(audioMetadataService, "audioMetadataService");
        this.mediaThumbnailLifecycle = mediaThumbnailLifecycle != null
            ? mediaThumbnailLifecycle
            : new BestEffortMediaThumbnailLifecycle(mediaThumbnailService);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void ensureResolved(Path path) {
        ensureResolved(path, MediaObjectResolveOptions.DEFAULT);
    }

    public void ensureResolved(Path path, MediaObjectResolveOptions options) {
        resolveOrCreate(path, options);
    }

    public MediaObject resolveOrCreate(Path path) {
        return resolveOrCreate(path, MediaObjectResolveOptions.DEFAULT);
    }

    public MediaObject resolveOrCreate(Path path, MediaObjectResolveOptions options) {
        MediaObjectResolveOptions effectiveOptions = options == null ? MediaObjectResolveOptions.DEFAULT : options;
        MediaObjectInspection inspection = inspect(path);
        if (inspection == null || !effectiveOptions.getAllowedKinds().contains(inspection.mediaKind())) {
            return null;
        }

        MediaObjectUpsertRequest request = buildUpsertRequest(path, inspection);
        MediaObject mediaObject = mediaObjectService.upsertPrimaryPath(request);
        if (effectiveOptions.isBootstrapPrimaryThumbnail()) {
            mediaThumbnailLifecycle.bootstrapPrimaryThumbnail(mediaObject);
        }
        return mediaObject;
    }

    public MediaThumbnailReference primaryThumbnailReference(MediaObject mediaObject) {
        return primaryThumbnailReference(mediaObject, null);
    }

    public MediaThumbnailReference primaryThumbnailReference(MediaObject mediaObject, String url) {
        return mediaThumbnailLifecycle.primaryThumbnailReference(mediaObject.objectId(), url);
    }

    public void ensureResolvedBlocking(Path path) {
        ensureResolved(path);
    }

    public void ensureResolvedBlocking(Path path, MediaObjectResolveOptions options) {
        ensureResolved(path, options);
    }

    public MediaObject resolveOrCreateBlocking(Path path) {
        return resolveOrCreate(path);
    }

    public MediaObject resolveOrCreateBlocking(Path path, MediaObjectResolveOptions options) {
        return resolveOrCreate(path, options);
    }

    public MediaThumbnailReference primaryThumbnailReferenceBlocking(MediaObject mediaObject) {
        return primaryThumbnailReference(mediaObject, null);
    }

    public MediaThumbnailReference primaryThumbnailReferenceBlocking(MediaObject mediaObject, String url) {
        return primaryThumbnailReference(mediaObject, url);
    }

    private MediaObjectUpsertRequest buildUpsertRequest(Path path, MediaObjectInspection inspection) {
        return switch (inspection.mediaKind()) {
            case IMAGE -> {
                ImageDimensions dimensions = MediaFileService.Companion.readImageDimensions(path);
                yield new MediaObjectUpsertRequest(
                    MediaKind.IMAGE,
                    inspection.primaryPath(),
                    inspection.mimeType(),
                    inspection.sizeBytes(),
                    inspection.modifiedAt(),
                    inspection.displayName(),
                    null,
                    dimensions == null ? null : dimensions.width(),
                    dimensions == null ? null : dimensions.height(),
                    null,
                    inspection.modifiedAt(),
                    null,
                    null,
                    null,
                    MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                    null,
                    MediaObjectStatus.ACTIVE
                );
            }
            case AUDIO -> {
                AudioMetadata metadata = audioMetadataService.get(path);
                Long durationMillis = metadata == null || metadata.getDuration() == null
                    ? null
                    : Math.round(metadata.getDuration() * 1000.0d);
                if (durationMillis != null && durationMillis <= 0L) {
                    durationMillis = null;
                }
                yield new MediaObjectUpsertRequest(
                    MediaKind.AUDIO,
                    inspection.primaryPath(),
                    inspection.mimeType(),
                    inspection.sizeBytes(),
                    inspection.modifiedAt(),
                    inspection.displayName(),
                    durationMillis,
                    null,
                    null,
                    metadata == null ? null : metadata.getChannels(),
                    null,
                    metadata == null ? null : metadata.getTitle(),
                    metadata == null ? null : metadata.getArtist(),
                    metadata == null ? null : metadata.getAlbum(),
                    MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                    null,
                    MediaObjectStatus.ACTIVE
                );
            }
            case VIDEO -> {
                ProbeResult probe = null;
                try {
                    probe = MediaProberInterop.probeCachedOrThrow(probeService, path);
                } catch (Exception ignored) {
                }
                var videoStream = probe == null || probe.getStreams().getVideo().isEmpty()
                    ? null
                    : probe.getStreams().getVideo().getFirst();
                Long durationMillis = probe == null || probe.getDurationSecs() <= 0
                    ? null
                    : Math.round(probe.getDurationSecs() * 1000.0d);
                yield new MediaObjectUpsertRequest(
                    MediaKind.VIDEO,
                    inspection.primaryPath(),
                    inspection.mimeType(),
                    inspection.sizeBytes(),
                    inspection.modifiedAt(),
                    inspection.displayName(),
                    durationMillis,
                    videoStream != null && videoStream.getWidth() > 0 ? videoStream.getWidth() : null,
                    videoStream != null && videoStream.getHeight() > 0 ? videoStream.getHeight() : null,
                    null,
                    null,
                    probe == null ? null : probe.getTags().get("title"),
                    null,
                    null,
                    MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                    null,
                    MediaObjectStatus.ACTIVE
                );
            }
            case OTHER -> new MediaObjectUpsertRequest(
                MediaKind.OTHER,
                inspection.primaryPath(),
                inspection.mimeType(),
                inspection.sizeBytes(),
                inspection.modifiedAt(),
                inspection.displayName(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
                null,
                MediaObjectStatus.ACTIVE
            );
        };
    }

    private MediaObjectInspection inspect(Path path) {
        if (!Files.isRegularFile(path)) {
            return null;
        }

        String mimeType = MediaTypes.detectMimeType(path);
        MediaKind mediaKind;
        if (MediaTypes.isImage(mimeType)) {
            mediaKind = MediaKind.IMAGE;
        } else if (MediaTypes.isAudio(mimeType)) {
            mediaKind = MediaKind.AUDIO;
        } else if (MediaTypes.isVideo(mimeType)) {
            mediaKind = MediaKind.VIDEO;
        } else {
            mediaKind = MediaKind.OTHER;
        }

        String modifiedAt;
        try {
            modifiedAt = Files.getLastModifiedTime(path).toInstant().toString();
        } catch (Exception ignored) {
            modifiedAt = Instant.now(clock).toString();
        }

        long sizeBytes;
        try {
            sizeBytes = Files.size(path);
        } catch (Exception ignored) {
            sizeBytes = 0L;
        }

        String displayName = path.getFileName() != null
            ? path.getFileName().toString()
            : path.toAbsolutePath().normalize().toString();

        return new MediaObjectInspection(
            mediaKind,
            mimeType,
            path.toString(),
            sizeBytes,
            modifiedAt,
            displayName
        );
    }

    private record MediaObjectInspection(
        MediaKind mediaKind,
        String mimeType,
        String primaryPath,
        long sizeBytes,
        String modifiedAt,
        String displayName
    ) {
    }
}
