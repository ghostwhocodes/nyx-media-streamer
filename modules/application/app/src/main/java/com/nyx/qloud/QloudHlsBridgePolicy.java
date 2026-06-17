package com.nyx.qloud;

import com.nyx.playback.contracts.PlaybackDeliveryService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

final class QloudHlsBridgePolicy {
    static final Duration BRIDGE_TTL = Duration.ofMinutes(30);
    static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(5);

    private final PlaybackDeliveryService playbackDeliveryService;
    private final Map<String, QloudCompatibilityRoutes.HlsBridgeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, QloudCompatibilityRoutes.HlsBridgeSession> sessionsByClientKey = new ConcurrentHashMap<>();
    private final Map<String, Deque<QloudCompatibilityRoutes.HlsBridgeSession>> anonymousSessionsByPath =
        new ConcurrentHashMap<>();
    private volatile Instant lastCleanupAt = Instant.EPOCH;

    QloudHlsBridgePolicy(PlaybackDeliveryService playbackDeliveryService) {
        this.playbackDeliveryService = playbackDeliveryService;
    }

    void register(QloudCompatibilityRoutes.HlsBridgeSession bridge) {
        prune(Instant.now());
        sessions.put(bridge.token(), bridge);
        if (bridge.clientLookupKey() != null) {
            QloudCompatibilityRoutes.HlsBridgeSession replaced = sessionsByClientKey.put(bridge.clientLookupKey(), bridge);
            if (replaced != null && !replaced.token().equals(bridge.token())) {
                close(replaced);
            }
            return;
        }
        anonymousSessionsByPath.compute(bridge.qloudPath(), (ignored, bridges) -> {
            Deque<QloudCompatibilityRoutes.HlsBridgeSession> next = bridges == null ? new ArrayDeque<>() : bridges;
            next.addLast(bridge);
            return next;
        });
    }

    void close(QloudCompatibilityRoutes.HlsBridgeSession bridge) {
        if (!bridge.markClosed()) {
            return;
        }
        sessions.remove(bridge.token(), bridge);
        if (bridge.clientLookupKey() != null) {
            sessionsByClientKey.remove(bridge.clientLookupKey(), bridge);
        } else {
            removeAnonymous(bridge);
        }
        playbackDeliveryService.close(bridge.playbackSessionId(), bridge.owner());
    }

    QloudCompatibilityRoutes.HlsBridgeSession findForClose(String owner, String rpcSession, String qloudPath) {
        String clientLookupKey = QloudCompatibilityRoutes.bridgeClientKey(owner, rpcSession, qloudPath);
        if (clientLookupKey != null) {
            return sessionsByClientKey.remove(clientLookupKey);
        }
        return pollAnonymousForClose(qloudPath);
    }

    QloudCompatibilityRoutes.HlsBridgeSession bridge(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Instant now = Instant.now();
        QloudCompatibilityRoutes.HlsBridgeSession bridge = sessions.get(token);
        if (bridge == null) {
            prune(now);
            return null;
        }
        if (bridge.isExpiredAt(now)) {
            close(bridge);
            return null;
        }
        bridge.touch(now);
        return bridge;
    }

    void prune(Instant now) {
        Instant lastCleanup = lastCleanupAt;
        if (lastCleanup.plus(CLEANUP_INTERVAL).isAfter(now)) {
            return;
        }

        lastCleanupAt = now;
        sessions.forEach((token, bridge) -> {
            if (bridge.isExpiredAt(now)) {
                close(bridge);
            }
        });
    }

    Map<String, QloudCompatibilityRoutes.HlsBridgeSession> sessions() {
        return sessions;
    }

    Map<String, QloudCompatibilityRoutes.HlsBridgeSession> sessionsByClientKey() {
        return sessionsByClientKey;
    }

    private void removeAnonymous(QloudCompatibilityRoutes.HlsBridgeSession bridge) {
        anonymousSessionsByPath.computeIfPresent(bridge.qloudPath(), (ignored, bridges) -> {
            bridges.remove(bridge);
            return bridges.isEmpty() ? null : bridges;
        });
    }

    private QloudCompatibilityRoutes.HlsBridgeSession pollAnonymousForClose(String qloudPath) {
        AtomicReference<QloudCompatibilityRoutes.HlsBridgeSession> selectedBridge = new AtomicReference<>();
        anonymousSessionsByPath.compute(qloudPath, (ignored, bridges) -> {
            if (bridges == null) {
                return null;
            }
            while (!bridges.isEmpty()) {
                QloudCompatibilityRoutes.HlsBridgeSession bridge = bridges.removeLast();
                if (sessions.containsKey(bridge.token())) {
                    selectedBridge.set(bridge);
                    break;
                }
            }
            return bridges.isEmpty() ? null : bridges;
        });
        return selectedBridge.get();
    }
}
