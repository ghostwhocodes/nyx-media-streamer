package com.nyx.transcode;

import com.nyx.ffmpeg.model.RepresentationConfig;
import com.nyx.transcode.contracts.TranscodeRepresentation;
import java.util.Objects;

public final class RepresentationMappings {
    private RepresentationMappings() {
    }

    public static TranscodeRepresentation toContractRepresentation(RepresentationConfig representation) {
        Objects.requireNonNull(representation, "representation");
        return new TranscodeRepresentation(
            representation.getWidth(),
            representation.getHeight(),
            representation.getBitrateKbps()
        );
    }

    public static RepresentationConfig toFfmpegRepresentation(TranscodeRepresentation representation) {
        Objects.requireNonNull(representation, "representation");
        return new RepresentationConfig(
            representation.getWidth(),
            representation.getHeight(),
            representation.getBitrateKbps()
        );
    }
}
