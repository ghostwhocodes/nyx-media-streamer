package com.nyx.common;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public final class MediaTypes {
    public static final MediaTypes INSTANCE = new MediaTypes();

    public static final String VIDEO_MP4 = "video/mp4";
    public static final String VIDEO_MKV = "video/x-matroska";
    public static final String VIDEO_WEBM = "video/webm";
    public static final String VIDEO_AVI = "video/x-msvideo";
    public static final String VIDEO_MOV = "video/quicktime";
    public static final String VIDEO_TS = "video/mp2t";

    public static final String AUDIO_MP3 = "audio/mpeg";
    public static final String AUDIO_FLAC = "audio/flac";
    public static final String AUDIO_AAC = "audio/aac";
    public static final String AUDIO_OPUS = "audio/opus";
    public static final String AUDIO_OGG = "audio/ogg";
    public static final String AUDIO_WAV = "audio/wav";
    public static final String AUDIO_M4A = "audio/mp4";
    public static final String AUDIO_WMA = "audio/x-ms-wma";
    public static final String AUDIO_AIFF = "audio/aiff";

    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String IMAGE_PNG = "image/png";
    public static final String IMAGE_WEBP = "image/webp";
    public static final String IMAGE_GIF = "image/gif";
    public static final String IMAGE_TIFF = "image/tiff";
    public static final String IMAGE_BMP = "image/bmp";
    public static final String IMAGE_SVG = "image/svg+xml";

    public static final String SUBTITLE_VTT = "text/vtt";
    public static final String SUBTITLE_SRT = "application/x-subrip";

    public static final String DASH_MPD = "application/dash+xml";
    public static final String HLS_M3U8 = "application/vnd.apple.mpegurl";
    public static final String SEGMENT_M4S = "video/iso.segment";

    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    private static final Map<String, String> EXTENSION_TO_MIME;
    private static final Set<String> IMAGE_MIME_TYPES = Set.of(
        IMAGE_JPEG, IMAGE_PNG, IMAGE_WEBP, IMAGE_GIF, IMAGE_TIFF, IMAGE_BMP, IMAGE_SVG
    );
    private static final Set<String> AUDIO_MIME_TYPES = Set.of(
        AUDIO_MP3, AUDIO_FLAC, AUDIO_AAC, AUDIO_OPUS, AUDIO_OGG, AUDIO_WAV, AUDIO_M4A, AUDIO_WMA, AUDIO_AIFF
    );
    private static final Set<String> VIDEO_MIME_TYPES = Set.of(
        VIDEO_MP4, VIDEO_MKV, VIDEO_WEBM, VIDEO_AVI, VIDEO_MOV, VIDEO_TS
    );

    static {
        Map<String, String> extensionToMime = new LinkedHashMap<>();
        extensionToMime.put("mp4", VIDEO_MP4);
        extensionToMime.put("m4v", VIDEO_MP4);
        extensionToMime.put("mkv", VIDEO_MKV);
        extensionToMime.put("webm", VIDEO_WEBM);
        extensionToMime.put("avi", VIDEO_AVI);
        extensionToMime.put("mov", VIDEO_MOV);
        extensionToMime.put("ts", VIDEO_TS);
        extensionToMime.put("mp3", AUDIO_MP3);
        extensionToMime.put("flac", AUDIO_FLAC);
        extensionToMime.put("aac", AUDIO_AAC);
        extensionToMime.put("opus", AUDIO_OPUS);
        extensionToMime.put("ogg", AUDIO_OGG);
        extensionToMime.put("wav", AUDIO_WAV);
        extensionToMime.put("m4a", AUDIO_M4A);
        extensionToMime.put("wma", AUDIO_WMA);
        extensionToMime.put("aiff", AUDIO_AIFF);
        extensionToMime.put("aif", AUDIO_AIFF);
        extensionToMime.put("jpg", IMAGE_JPEG);
        extensionToMime.put("jpeg", IMAGE_JPEG);
        extensionToMime.put("png", IMAGE_PNG);
        extensionToMime.put("webp", IMAGE_WEBP);
        extensionToMime.put("gif", IMAGE_GIF);
        extensionToMime.put("tiff", IMAGE_TIFF);
        extensionToMime.put("tif", IMAGE_TIFF);
        extensionToMime.put("bmp", IMAGE_BMP);
        extensionToMime.put("svg", IMAGE_SVG);
        extensionToMime.put("vtt", SUBTITLE_VTT);
        extensionToMime.put("srt", SUBTITLE_SRT);
        extensionToMime.put("mpd", DASH_MPD);
        extensionToMime.put("m3u8", HLS_M3U8);
        extensionToMime.put("m4s", SEGMENT_M4S);
        EXTENSION_TO_MIME = Map.copyOf(extensionToMime);
    }

    private MediaTypes() {
    }

    public static String mimeTypeForExtension(String extension) {
        return EXTENSION_TO_MIME.get(extension.toLowerCase(Locale.ROOT));
    }

    public static String mimeTypeForPath(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == path.length() - 1) {
            return null;
        }
        return mimeTypeForExtension(path.substring(dotIndex + 1));
    }

    /**
     * Detect MIME type for a file on disk.
     *
     * Prefers extension-based lookup (consistent, predictable), then falls back
     * to {@link Files#probeContentType(Path)} for types not in the internal map.
     */
    public static String detectMimeType(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return APPLICATION_OCTET_STREAM;
        }

        String byExtension = mimeTypeForPath(fileName.toString());
        if (byExtension != null) {
            return byExtension;
        }

        try {
            String probed = Files.probeContentType(path);
            return probed != null ? probed : APPLICATION_OCTET_STREAM;
        } catch (Exception ignored) {
            return APPLICATION_OCTET_STREAM;
        }
    }

    public static boolean isImage(String mimeType) {
        return IMAGE_MIME_TYPES.contains(mimeType);
    }

    public static boolean isAudio(String mimeType) {
        return AUDIO_MIME_TYPES.contains(mimeType);
    }

    public static boolean isVideo(String mimeType) {
        return VIDEO_MIME_TYPES.contains(mimeType);
    }

    public static Map<String, String> buildThumbnailUrls(String virtualPath, java.util.List<Integer> sizes) {
        Map<String, String> urls = new LinkedHashMap<>();
        String encodedPath = encodePathQueryValue(virtualPath);
        for (Integer size : sizes) {
            urls.put(size.toString(), "/api/v1/images/thumb?path=" + encodedPath + "&size=" + size);
        }
        return urls;
    }

    public static String buildImageViewUrl(String virtualPath) {
        return "/api/v1/images/view?path=" + encodePathQueryValue(virtualPath);
    }

    public static String encodePathQueryValue(String virtualPath) {
        return URLEncoder.encode(virtualPath, StandardCharsets.UTF_8)
            .replace("%2F", "/")
            .replace("%2f", "/");
    }
}
