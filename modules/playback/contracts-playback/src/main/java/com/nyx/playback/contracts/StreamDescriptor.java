package com.nyx.playback.contracts;

import com.nyx.stream.representation.contracts.StreamRepresentation;
import com.nyx.stream.representation.contracts.StreamRepresentationPolicy;
import com.nyx.stream.representation.contracts.StreamRepresentationTraits;
import com.nyx.stream.representation.contracts.StreamingProtocol;

public record StreamDescriptor(
    StreamingProtocol protocol,
    String container,
    boolean adaptive,
    StreamRepresentation representation
) {
    public StreamDescriptor(StreamingProtocol protocol) {
        this(protocol, null, false, null);
    }

    public StreamDescriptor(StreamRepresentation representation) {
        this(null, null, true, representation);
    }

    public StreamDescriptor(StreamingProtocol protocol, String container, boolean adaptive) {
        this(protocol, container, adaptive, null);
    }

    public StreamDescriptor {
        StreamRepresentationPolicy policy = StreamRepresentationPolicy.defaultPolicy();
        if (representation == null) {
            representation = policy.defaultFor(protocol, container, adaptive);
        } else {
            StreamRepresentationTraits traits = policy.traits(representation);
            protocol = traits.primaryProtocol();
            if (representation != StreamRepresentation.DIRECT_FILE) {
                container = traits.segmentContainer().token();
                adaptive = adaptive && traits.adaptive();
            }
        }
    }
}
