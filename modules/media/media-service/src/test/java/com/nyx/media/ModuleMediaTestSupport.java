package com.nyx.media;

import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaObjectContracts;
import com.nyx.media.contracts.MediaObjectStatus;
import com.nyx.media.contracts.ImageViewingMetadata;
import com.nyx.media.contracts.MediaItem;
import com.nyx.media.contracts.TrickplayDiscoveryMetadata;
import com.nyx.media.contracts.VideoViewingMetadata;
import com.nyx.playback.contracts.AudioCapabilitySet;
import com.nyx.playback.contracts.AudioConstraint;
import com.nyx.playback.contracts.AudioNegotiationRequest;
import com.nyx.playback.contracts.AudioOutputPreferences;
import com.nyx.playback.contracts.MediaSessionPlaybackEvent;
import com.nyx.playback.contracts.MediaSessionPlaybackReport;
import com.nyx.playback.contracts.MediaSourceRef;
import com.zaxxer.hikari.HikariDataSource;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

public final class ModuleMediaTestSupport {
    private ModuleMediaTestSupport() {
    }

    public static void closeDataSources(List<HikariDataSource> dataSources) {
        for (HikariDataSource dataSource : dataSources) {
            dataSource.close();
        }
        dataSources.clear();
    }

    public static Path writeMediaFile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.write(path, new byte[2_048]);
    }

    public static Path writeTextFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    public static Path createImageFile(Path path, int width, int height, String format) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, format, path.toFile());
        return path;
    }

    public static MediaObjectUpsertRequest mediaObjectUpsertRequest(Path path, MediaKind mediaKind) throws IOException {
        return new MediaObjectUpsertRequest(
            mediaKind,
            path.toString(),
            switch (mediaKind) {
                case VIDEO -> "video/mp4";
                case AUDIO -> "audio/flac";
                case IMAGE -> "image/jpeg";
                case OTHER -> "application/octet-stream";
            },
            Files.size(path),
            "2026-04-10T12:00:00Z",
            path.getFileName().toString(),
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.AUDIO ? 95_000L : null,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1920 : null,
            mediaKind == MediaKind.VIDEO || mediaKind == MediaKind.IMAGE ? 1080 : null,
            mediaKind == MediaKind.AUDIO ? 2 : null,
            null,
            null,
            null,
            null,
            MediaObjectContracts.MEDIA_OBJECT_HASH_ALGORITHM_NONE,
            null,
            MediaObjectStatus.ACTIVE
        );
    }

    public static MediaSourceRef mediaSourceRef(String path) {
        return new MediaSourceRef(path, null, null, null);
    }

    public static AudioNegotiationRequest audioNegotiationRequest(MediaSourceRef source, long startPositionMillis) {
        return new AudioNegotiationRequest(
            source,
            startPositionMillis,
            null,
            new AudioCapabilitySet(),
            new AudioConstraint(),
            new AudioOutputPreferences()
        );
    }

    public static MediaSessionPlaybackReport mediaSessionPlaybackReport(
        MediaSessionPlaybackEvent event,
        Long positionMillis,
        Long durationMillis,
        String occurredAt
    ) {
        return new MediaSessionPlaybackReport(
            event,
            null,
            null,
            positionMillis,
            durationMillis,
            occurredAt,
            null,
            null,
            null
        );
    }

    public static MediaItem.Music mediaItemMusic(
        String name,
        String path,
        long size,
        String mimeType,
        Double duration,
        Long bitrate,
        Integer channels,
        String artist,
        String album,
        String title
    ) {
        return new MediaItem.Music(
            name,
            path,
            size,
            null,
            MediaKind.AUDIO,
            null,
            mimeType,
            duration,
            bitrate,
            channels,
            artist,
            album,
            title,
            null
        );
    }

    public static MediaItem.Image mediaItemImage(
        String name,
        String path,
        long size,
        String mimeType,
        Integer width,
        Integer height,
        String takenAt,
        List<Integer> thumbnailSizes,
        ImageViewingMetadata viewing
    ) {
        return new MediaItem.Image(
            name,
            path,
            size,
            null,
            MediaKind.IMAGE,
            null,
            mimeType,
            width,
            height,
            takenAt,
            thumbnailSizes,
            viewing,
            null
        );
    }

    public static MediaItem.Video mediaItemVideo(
        String name,
        String path,
        long size,
        String mimeType,
        VideoViewingMetadata viewing
    ) {
        return new MediaItem.Video(
            name,
            path,
            size,
            null,
            MediaKind.VIDEO,
            null,
            mimeType,
            viewing,
            null
        );
    }

    public static ImageViewingMetadata imageViewingMetadata() {
        return new ImageViewingMetadata();
    }

    public static VideoViewingMetadata videoViewingMetadata(TrickplayDiscoveryMetadata trickplay) {
        return new VideoViewingMetadata(trickplay);
    }

    public static TrickplayDiscoveryMetadata trickplayDiscoveryMetadata() {
        return new TrickplayDiscoveryMetadata();
    }
}
