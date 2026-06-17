package com.nyx.media;

import com.nyx.ffmpeg.MediaProber;
import com.nyx.ffmpeg.MediaProberInterop;
import com.nyx.ffmpeg.model.AudioStream;
import com.nyx.ffmpeg.model.ProbeResult;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AudioMetadataService {
    private static final Logger LOG = LoggerFactory.getLogger(AudioMetadataService.class);

    private final MediaProber probeService;
    private final FileProbeCache fileProbeCache;

    public AudioMetadataService(MediaProber probeService) {
        this(probeService, null);
    }

    public AudioMetadataService(MediaProber probeService, FileProbeCache fileProbeCache) {
        this.probeService = probeService;
        this.fileProbeCache = fileProbeCache;
    }

    public AudioMetadata get(Path path) {
        try {
            String pathStr = path.toAbsolutePath().toString();
            long mtime = Files.getLastModifiedTime(path).toMillis();
            long size = Files.size(path);

            if (fileProbeCache != null) {
                AudioMetadata cached = fileProbeCache.get(pathStr, mtime, size);
                if (cached != null) {
                    return cached;
                }
            }

            ProbeResult probe = MediaProberInterop.probeCachedOrThrow(probeService, path);
            AudioStream audioStream = probe.getStreams().getAudio().isEmpty() ? null : probe.getStreams().getAudio().getFirst();
            AudioMetadata metadata = new AudioMetadata(
                probe.getDurationSecs() > 0 ? probe.getDurationSecs() : null,
                audioStream != null && audioStream.getBitrateKbps() != null ? audioStream.getBitrateKbps() * 1000L : null,
                audioStream != null ? audioStream.getChannels() : null,
                probe.getTags().get("artist"),
                probe.getTags().get("album"),
                probe.getTags().get("title")
            );
            if (fileProbeCache != null) {
                fileProbeCache.put(pathStr, mtime, size, metadata);
            }
            return metadata;
        } catch (Exception exception) {
            LOG.debug("Could not probe audio metadata for {}: {}", path.getFileName(), exception.getMessage());
            return null;
        }
    }
}
