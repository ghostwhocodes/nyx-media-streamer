package com.nyx.media.client;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.nyx.common.MediaTypes;
import com.nyx.http.Parameters;
import com.nyx.http.RoutingCall;
import com.nyx.media.contracts.AudioListing;
import com.nyx.media.contracts.BrowseListing;
import com.nyx.media.contracts.FileSearchResult;
import com.nyx.media.contracts.Gallery;
import com.nyx.media.contracts.ImageTransformCapabilities;
import com.nyx.media.contracts.ImageTransformRequest;
import com.nyx.media.contracts.ImageViewingMetadata;
import com.nyx.media.contracts.MediaCapabilityHints;
import com.nyx.media.contracts.MediaItem;
import com.nyx.media.contracts.MediaKind;
import com.nyx.media.contracts.MediaThumbnailReference;
import com.nyx.media.contracts.TrickplayDiscoveryMetadata;
import com.nyx.media.contracts.TrickplayRequest;
import com.nyx.media.contracts.VideoViewingMetadata;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MediaClientContractAdapter {
    public static final MediaClientContractAdapter DEFAULT = new MediaClientContractAdapter();

    private MediaClientContractAdapter() {
    }

    public BrowseListingResponse browse(BrowseListing listing, RoutingCall call) {
        ClientOrigin origin = ClientOrigin.from(call);
        return new BrowseListingResponse(
            listing.items().stream().map(item -> toClientItem(item, origin)).toList(),
            listing.total(),
            listing.page(),
            listing.limit(),
            routeTemplates(origin)
        );
    }

    public FileSearchResultResponse search(FileSearchResult result, RoutingCall call) {
        ClientOrigin origin = ClientOrigin.from(call);
        return new FileSearchResultResponse(
            result.items().stream().map(item -> toClientItem(item, origin)).toList(),
            result.total(),
            result.page(),
            result.limit(),
            result.query(),
            routeTemplates(origin)
        );
    }

    public GalleryResponse gallery(Gallery gallery, RoutingCall call) {
        ClientOrigin origin = ClientOrigin.from(call);
        return new GalleryResponse(
            gallery.images().stream()
                .map(image -> (ImageItemResponse) toClientItem(image, origin))
                .toList(),
            gallery.total(),
            gallery.page(),
            gallery.limit()
        );
    }

    public AudioListingResponse audio(AudioListing listing, RoutingCall call) {
        ClientOrigin origin = ClientOrigin.from(call);
        return new AudioListingResponse(
            listing.tracks().stream()
                .map(track -> (MusicItemResponse) toClientItem(track, origin))
                .toList(),
            listing.total(),
            listing.page(),
            listing.limit()
        );
    }

    private static ClientMediaItem toClientItem(MediaItem item, ClientOrigin origin) {
        if (item instanceof MediaItem.Folder folder) {
            return new FolderItemResponse(
                folder.name(),
                folder.path(),
                folder.size(),
                folder.modifiedAt(),
                new MediaClientLinks(browseUrl(origin, folder.path()), null, null, null, null)
            );
        }
        if (item instanceof MediaItem.Image image) {
            String thumbnailUrl = primaryThumbnailUrl(origin, image.path(), image.thumbnailSizes());
            String imageUrl = imageFileUrl(origin, image.path());
            return new ImageItemResponse(
                image.name(),
                image.path(),
                image.size(),
                image.objectId(),
                image.mediaKind(),
                withThumbnailUrl(image.primaryThumbnail(), thumbnailUrl),
                image.mimeType(),
                image.width(),
                image.height(),
                image.takenAt(),
                thumbnailUrls(origin, image.path(), image.thumbnailSizes()),
                imageViewing(image.path(), image.viewing(), origin),
                image.modifiedAt(),
                new MediaClientLinks(null, null, imageUrl, null, thumbnailUrl),
                capabilities(image.capabilities(), thumbnailUrl, imageUrl)
            );
        }
        if (item instanceof MediaItem.Music music) {
            String audioUrl = audioFileUrl(origin, music.path());
            return new MusicItemResponse(
                music.name(),
                music.path(),
                music.size(),
                music.objectId(),
                music.mediaKind(),
                music.primaryThumbnail(),
                music.mimeType(),
                music.duration(),
                music.bitrate(),
                music.channels(),
                music.artist(),
                music.album(),
                music.title(),
                music.modifiedAt(),
                new MediaClientLinks(null, null, null, audioUrl, null),
                capabilities(music.capabilities(), null, audioUrl)
            );
        }
        if (item instanceof MediaItem.Video video) {
            String thumbnailUrl = primaryThumbnailUrl(origin, video.path(), video.thumbnailSizes());
            String playbackUrl = playbackUrl(origin, video.path());
            return new VideoItemResponse(
                video.name(),
                video.path(),
                video.size(),
                video.objectId(),
                video.mediaKind(),
                withThumbnailUrl(video.primaryThumbnail(), thumbnailUrl),
                video.mimeType(),
                videoViewing(video.path(), video.viewing(), origin),
                video.modifiedAt(),
                new MediaClientLinks(null, playbackUrl, null, null, thumbnailUrl),
                capabilities(video.capabilities(), thumbnailUrl, playbackUrl)
            );
        }
        throw new IllegalArgumentException("Unsupported media item type: " + item.getClass().getName());
    }

    private static MediaClientCapabilityHints capabilities(
        MediaCapabilityHints source,
        String primaryThumbnailUrl,
        String preferredPlaybackEndpoint
    ) {
        MediaCapabilityHints hints = source == null ? new MediaCapabilityHints() : source;
        return new MediaClientCapabilityHints(
            hints.directPlayAvailable(),
            hints.transcodeRequired(),
            hints.durationSeconds(),
            hints.mimeType(),
            hints.objectId(),
            primaryThumbnailUrl,
            preferredPlaybackEndpoint
        );
    }

    private static MediaThumbnailReference withThumbnailUrl(MediaThumbnailReference source, String url) {
        if (source == null) {
            return null;
        }
        return new MediaThumbnailReference(
            source.thumbnailId(),
            source.kind(),
            source.status(),
            url,
            source.width(),
            source.height(),
            source.format()
        );
    }

    private static ImageViewingResponse imageViewing(
        String virtualPath,
        ImageViewingMetadata source,
        ClientOrigin origin
    ) {
        ImageViewingMetadata metadata = source == null ? new ImageViewingMetadata() : source;
        return new ImageViewingResponse(
            imageViewUrl(origin, virtualPath),
            null,
            metadata.defaultTransform(),
            metadata.capabilities()
        );
    }

    private static VideoViewingResponse videoViewing(
        String virtualPath,
        VideoViewingMetadata source,
        ClientOrigin origin
    ) {
        TrickplayDiscoveryMetadata trickplay = source == null ? null : source.trickplay();
        if (trickplay == null) {
            trickplay = new TrickplayDiscoveryMetadata();
        }
        return new VideoViewingResponse(
            videoPreviewUrl(origin, virtualPath),
            new TrickplayDiscoveryResponse(
                videoTrickplayUrl(origin, virtualPath),
                trickplay.defaultRequest(),
                trickplay.cacheableByDefault()
            )
        );
    }

    private static Map<String, String> routeTemplates(ClientOrigin origin) {
        return Map.of(
            "browse", origin.link("/api/v1/browse?path={path}"),
            "search", origin.link("/api/v1/search/files?query={query}"),
            "thumbnail", origin.link("/api/v1/images/thumb?path={path}&size={size}"),
            "image", origin.link("/api/v1/images/file?path={path}"),
            "audio", origin.link("/api/v1/audio/file?path={path}"),
            "playback", origin.link("/api/v1/stream.m3u8?path={path}&quality={quality}")
        );
    }

    private static Map<String, String> thumbnailUrls(ClientOrigin origin, String virtualPath, List<Integer> sizes) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (Integer size : sizes) {
            if (size != null) {
                urls.put(size.toString(), thumbnailUrl(origin, virtualPath, size));
            }
        }
        return urls;
    }

    private static String primaryThumbnailUrl(ClientOrigin origin, String virtualPath, List<Integer> sizes) {
        Integer primarySize = sizes == null || sizes.isEmpty() ? null : sizes.getFirst();
        return primarySize == null ? null : thumbnailUrl(origin, virtualPath, primarySize);
    }

    private static String browseUrl(ClientOrigin origin, String virtualPath) {
        return origin.link("/api/v1/browse?path=" + MediaTypes.encodePathQueryValue(virtualPath));
    }

    private static String playbackUrl(ClientOrigin origin, String virtualPath) {
        return origin.link("/api/v1/stream.m3u8?path=" + MediaTypes.encodePathQueryValue(virtualPath));
    }

    private static String imageFileUrl(ClientOrigin origin, String virtualPath) {
        return origin.link("/api/v1/images/file?path=" + MediaTypes.encodePathQueryValue(virtualPath));
    }

    private static String audioFileUrl(ClientOrigin origin, String virtualPath) {
        return origin.link("/api/v1/audio/file?path=" + MediaTypes.encodePathQueryValue(virtualPath));
    }

    private static String thumbnailUrl(ClientOrigin origin, String virtualPath, int size) {
        return origin.link("/api/v1/images/thumb?path=" + MediaTypes.encodePathQueryValue(virtualPath) + "&size=" + size);
    }

    private static String imageViewUrl(ClientOrigin origin, String virtualPath) {
        return origin.link("/api/v1/images/view?path=" + MediaTypes.encodePathQueryValue(virtualPath));
    }

    private static String videoPreviewUrl(ClientOrigin origin, String virtualPath) {
        return origin.link("/api/v1/images/preview?path=" + encodeQueryValue(virtualPath));
    }

    private static String videoTrickplayUrl(ClientOrigin origin, String virtualPath) {
        return origin.link("/api/v1/images/trickplay?path=" + encodeQueryValue(virtualPath));
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record BrowseListingResponse(
        List<ClientMediaItem> items,
        int total,
        int page,
        int limit,
        Map<String, String> routeTemplates
    ) {
        public BrowseListingResponse {
            items = immutableList(items);
            routeTemplates = immutableMap(routeTemplates);
        }
    }

    public record FileSearchResultResponse(
        List<ClientMediaItem> items,
        int total,
        int page,
        int limit,
        String query,
        Map<String, String> routeTemplates
    ) {
        public FileSearchResultResponse {
            items = immutableList(items);
            routeTemplates = immutableMap(routeTemplates);
        }
    }

    public record GalleryResponse(
        List<ImageItemResponse> images,
        int total,
        int page,
        int limit
    ) {
        public GalleryResponse {
            images = immutableList(images);
        }
    }

    public record AudioListingResponse(
        List<MusicItemResponse> tracks,
        int total,
        int page,
        int limit
    ) {
        public AudioListingResponse {
            tracks = immutableList(tracks);
        }
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = FolderItemResponse.class, name = "folder"),
        @JsonSubTypes.Type(value = VideoItemResponse.class, name = "video"),
        @JsonSubTypes.Type(value = ImageItemResponse.class, name = "image"),
        @JsonSubTypes.Type(value = MusicItemResponse.class, name = "music")
    })
    public sealed interface ClientMediaItem permits FolderItemResponse, VideoItemResponse, ImageItemResponse, MusicItemResponse {
        String name();

        String path();

        long size();

        Long modifiedAt();
    }

    public record FolderItemResponse(
        String name,
        String path,
        long size,
        Long modifiedAt,
        MediaClientLinks links
    ) implements ClientMediaItem {
    }

    public record VideoItemResponse(
        String name,
        String path,
        long size,
        String objectId,
        MediaKind mediaKind,
        MediaThumbnailReference primaryThumbnail,
        String mimeType,
        VideoViewingResponse viewing,
        Long modifiedAt,
        MediaClientLinks links,
        MediaClientCapabilityHints capabilities
    ) implements ClientMediaItem {
    }

    public record ImageItemResponse(
        String name,
        String path,
        long size,
        String objectId,
        MediaKind mediaKind,
        MediaThumbnailReference primaryThumbnail,
        String mimeType,
        Integer width,
        Integer height,
        String takenAt,
        Map<String, String> thumbnails,
        ImageViewingResponse viewing,
        Long modifiedAt,
        MediaClientLinks links,
        MediaClientCapabilityHints capabilities
    ) implements ClientMediaItem {
        public ImageItemResponse {
            thumbnails = immutableMap(thumbnails);
        }
    }

    public record MusicItemResponse(
        String name,
        String path,
        long size,
        String objectId,
        MediaKind mediaKind,
        MediaThumbnailReference primaryThumbnail,
        String mimeType,
        Double duration,
        Long bitrate,
        Integer channels,
        String artist,
        String album,
        String title,
        Long modifiedAt,
        MediaClientLinks links,
        MediaClientCapabilityHints capabilities
    ) implements ClientMediaItem {
    }

    public record MediaClientLinks(
        String browseUrl,
        String playbackUrl,
        String imageUrl,
        String audioUrl,
        String thumbnailUrl
    ) {
    }

    public record MediaClientCapabilityHints(
        boolean directPlayAvailable,
        boolean transcodeRequired,
        Double durationSeconds,
        String mimeType,
        String objectId,
        String primaryThumbnailUrl,
        String preferredPlaybackEndpoint
    ) {
    }

    public record ImageViewingResponse(
        String transformUrl,
        String previewUrl,
        ImageTransformRequest defaultTransform,
        ImageTransformCapabilities capabilities
    ) {
    }

    public record VideoViewingResponse(
        String previewUrl,
        TrickplayDiscoveryResponse trickplay
    ) {
    }

    public record TrickplayDiscoveryResponse(
        String manifestUrl,
        TrickplayRequest defaultRequest,
        Boolean cacheableByDefault
    ) {
    }

    private record ClientOrigin(String baseUrl) {
        private static ClientOrigin from(RoutingCall call) {
            Parameters headers = call.getRequest().getHeaders();
            String forwardedProto = firstForwarded(headers.get("X-Forwarded-Proto"));
            String forwardedHost = firstForwarded(headers.get("X-Forwarded-Host"));
            String forwardedPort = firstForwarded(headers.get("X-Forwarded-Port"));
            if (isBlank(forwardedProto) && isBlank(forwardedHost) && isBlank(forwardedPort)) {
                return relative();
            }

            String scheme = isBlank(forwardedProto) ? "http" : forwardedProto.toLowerCase(java.util.Locale.ROOT);
            HostPort hostPort = parseAuthority(isBlank(forwardedHost) ? headers.get("Host") : forwardedHost);
            if (hostPort == null || isBlank(hostPort.host())) {
                return relative();
            }

            Integer explicitPort = explicitPort(forwardedPort);
            String host = hostPort.host();
            String authorityHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
            Integer port = explicitPort != null ? explicitPort : hostPort.port();
            String authority = port == null ? authorityHost : authorityHost + ":" + port;
            return new ClientOrigin(scheme + "://" + authority);
        }

        private static ClientOrigin relative() {
            return new ClientOrigin(null);
        }

        private String link(String path) {
            return baseUrl == null ? path : baseUrl + path;
        }
    }

    private record HostPort(String host, Integer port) {
    }

    private static HostPort parseAuthority(String authority) {
        if (isBlank(authority)) {
            return null;
        }
        String normalized = authority.trim();
        if (normalized.startsWith("[")) {
            int close = normalized.indexOf(']');
            if (close >= 0) {
                Integer port = close + 2 < normalized.length() && normalized.charAt(close + 1) == ':'
                    ? explicitPort(normalized.substring(close + 2))
                    : null;
                return new HostPort(normalized.substring(1, close), port);
            }
        }
        int firstColon = normalized.indexOf(':');
        int lastColon = normalized.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            return new HostPort(normalized.substring(0, firstColon), explicitPort(normalized.substring(firstColon + 1)));
        }
        return new HostPort(normalized, null);
    }

    private static Integer explicitPort(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            int port = Integer.parseInt(raw);
            return port >= 0 && port <= 65_535 ? port : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String firstForwarded(String value) {
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        return (comma >= 0 ? value.substring(0, comma) : value).trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> List<T> immutableList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
