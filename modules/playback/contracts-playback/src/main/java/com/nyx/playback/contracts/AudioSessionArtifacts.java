package com.nyx.playback.contracts;

import com.nyx.media.contracts.MediaKind;
import java.nio.file.Path;

public record AudioSessionArtifacts(
    String playbackUrl,
    String contentUrl
) {
}
