package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import java.nio.file.Path;

public record PlaybackSessionArtifacts(
    StreamingProtocol protocol,
    String playbackUrl,
    String directContentUrl,
    String hlsMasterUrl,
    String dashManifestUrl
) {
    public PlaybackSessionArtifacts(StreamingProtocol protocol, String playbackUrl) {
        this(protocol, playbackUrl, null, null, null);
    }
}
