package com.nyx.playback.contracts;

import com.nyx.stream.representation.contracts.StreamingProtocol;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class PlaybackContractSupport {
    private PlaybackContractSupport() {
    }

    static <T> List<T> immutableList(List<T> values) {
        return List.copyOf(Objects.requireNonNullElse(values, List.of()));
    }

    static <T> Set<T> immutableSet(Set<T> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    static Set<StreamingProtocol> defaultStreamingProtocols() {
        LinkedHashSet<StreamingProtocol> protocols = new LinkedHashSet<>();
        protocols.add(StreamingProtocol.HLS);
        protocols.add(StreamingProtocol.DASH);
        return Collections.unmodifiableSet(protocols);
    }
}
