package com.nyx.qloud;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class QloudCompatibilitySessionPolicy {
    static final Duration SESSION_TTL = Duration.ofHours(24);
    static final Duration CLEANUP_INTERVAL = Duration.ofMinutes(5);

    private final Supplier<String> sessionIdFactory;
    private final Map<String, QloudCompatibilityRoutes.CompatibilitySession> sessions = new ConcurrentHashMap<>();
    private volatile Instant lastCleanupAt = Instant.EPOCH;

    QloudCompatibilitySessionPolicy(Supplier<String> sessionIdFactory) {
        this.sessionIdFactory = sessionIdFactory;
    }

    QloudCompatibilityRoutes.CompatibilitySession issue(String owner) {
        if (owner == null || owner.isBlank()) {
            return null;
        }
        Instant now = Instant.now();
        prune(now);
        QloudCompatibilityRoutes.CompatibilitySession session =
            new QloudCompatibilityRoutes.CompatibilitySession(sessionIdFactory.get(), owner, now);
        sessions.put(session.rpcSession(), session);
        return session;
    }

    String owner(String rpcSession) {
        QloudCompatibilityRoutes.CompatibilitySession session = session(rpcSession, Instant.now());
        return session == null ? null : session.owner();
    }

    QloudCompatibilityRoutes.CompatibilitySession session(String rpcSession, Instant now) {
        if (rpcSession == null || rpcSession.isBlank()) {
            return null;
        }

        QloudCompatibilityRoutes.CompatibilitySession session = sessions.get(rpcSession);
        if (session == null) {
            prune(now);
            return null;
        }
        if (session.isExpiredAt(now)) {
            sessions.remove(rpcSession, session);
            return null;
        }
        session.touch(now);
        prune(now);
        return session;
    }

    void prune(Instant now) {
        Instant lastCleanup = lastCleanupAt;
        if (lastCleanup.plus(CLEANUP_INTERVAL).isAfter(now)) {
            return;
        }

        lastCleanupAt = now;
        sessions.forEach((rpcSession, session) -> {
            if (session.isExpiredAt(now)) {
                sessions.remove(rpcSession, session);
            }
        });
    }

    Map<String, QloudCompatibilityRoutes.CompatibilitySession> sessions() {
        return sessions;
    }
}
