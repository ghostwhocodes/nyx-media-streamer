package com.nyx.playback.contracts;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public interface AudioNegotiationService {
    AudioNegotiationDecision decide(AudioNegotiationRequest request);
}
