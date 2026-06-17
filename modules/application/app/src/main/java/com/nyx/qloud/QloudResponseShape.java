package com.nyx.qloud;

import com.nyx.common.MediaTypes;
import com.nyx.media.contracts.MediaItem;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class QloudResponseShape {
    private QloudResponseShape() {
    }

    static Map<String, Object> responseBase(Map<String, Object> request, String defaultAction) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("action", QloudScalars.valueOrDefault(QloudScalars.stringValue(request.get("action")), defaultAction));
        return response;
    }

    static void copyVideoRequestEchoes(Map<String, Object> request, Map<String, Object> response) {
        copyIfPresent(request, response, "audio-boost-ac3");
        copyIfPresent(request, response, "audio-lang");
        copyIfPresent(request, response, "audio-vbr");
        copyIfPresent(request, response, "bandwidth");
        copyIfPresent(request, response, "episode-filter");
        copyIfPresent(request, response, "fast-playable");
        copyIfPresent(request, response, "seek");
        copyIfPresent(request, response, "session");
        copyIfPresent(request, response, "square-aspect");
        copyIfPresent(request, response, "subtitle-lang");
        copyIfPresent(request, response, "uniform-subtitle");
        copyIfPresent(request, response, "video-crf");
    }

    static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    static String qloudType(MediaItem item) {
        if (item instanceof MediaItem.Folder) {
            return "video-folder";
        }
        if (item instanceof MediaItem.Video) {
            return "video";
        }
        if (item instanceof MediaItem.Image) {
            return "image";
        }
        if (item instanceof MediaItem.Music) {
            return "music";
        }
        return "file";
    }

    static String qloudType(String mimeType) {
        if (MediaTypes.isVideo(mimeType)) {
            return "video";
        }
        if (MediaTypes.isImage(mimeType)) {
            return "image";
        }
        if (MediaTypes.isAudio(mimeType)) {
            return "music";
        }
        return "file";
    }

    static String titleFromName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String titleFromPath(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return path.toString();
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
