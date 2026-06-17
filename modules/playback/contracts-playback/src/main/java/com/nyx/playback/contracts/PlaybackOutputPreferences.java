package com.nyx.playback.contracts;

import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.stream.representation.contracts.StreamRepresentationTraits;
import com.nyx.stream.representation.contracts.StreamingProtocol;
import java.util.Set;

public record PlaybackOutputPreferences(
    Set<StreamingProtocol> allowedProtocols,
    StreamingProtocol preferredProtocol,
    boolean allowAdaptiveStreaming,
    StreamRepresentation preferredRepresentation
) {
    public PlaybackOutputPreferences() {
        this(PlaybackContractSupport.defaultStreamingProtocols(), null, true, null);
    }

    public PlaybackOutputPreferences(Set<StreamingProtocol> allowedProtocols, StreamingProtocol preferredProtocol) {
        this(allowedProtocols, preferredProtocol, true, null);
    }

    public PlaybackOutputPreferences(
        Set<StreamingProtocol> allowedProtocols,
        StreamingProtocol preferredProtocol,
        boolean allowAdaptiveStreaming
    ) {
        this(allowedProtocols, preferredProtocol, allowAdaptiveStreaming, null);
    }

    public PlaybackOutputPreferences {
        allowedProtocols = PlaybackContractSupport.immutableSet(allowedProtocols == null ? PlaybackContractSupport.defaultStreamingProtocols() : allowedProtocols);
        if (preferredRepresentation != null) {
            StreamRepresentationTraits traits = StreamRepresentationPolicy.defaultPolicy().traits(preferredRepresentation);
            if (!allowedProtocols.containsAll(traits.protocols())) {
                throw new IllegalArgumentException(
                    "Preferred representation protocols must be present in allowedProtocols"
                );
            }
            preferredProtocol = traits.protocols().size() == 1 ? traits.primaryProtocol() : null;
        }
    }
}
