package com.nyx.common;

import com.nyx.config.QuotaConfig;
import com.nyx.config.UserQuotaOverride;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Per-user quota enforcement: rate limiting and concurrent-job limits.
 *
 * Rate limiting uses a shared {@link SlidingWindowCounter} with atomic
 * check-and-add semantics. Job quota enforcement is not done here; it belongs
 * in the transcode persistence layer where the count + insert can be executed
 * in a single DB transaction.
 */
public final class QuotaService {
    private static final long WINDOW_MS = 60_000L;

    private final QuotaConfig config;
    private final ToIntFunction<String> countActiveJobs;
    private final ToLongFunction<String> sumStorageBytes;
    private final Set<String> knownUserIds;
    private final SlidingWindowCounter counter = new SlidingWindowCounter();

    public QuotaService(QuotaConfig config, ToIntFunction<String> countActiveJobs) {
        this(config, countActiveJobs, userId -> 0L, Set.of());
    }

    public QuotaService(QuotaConfig config, ToIntFunction<String> countActiveJobs, Set<String> knownUserIds) {
        this(config, countActiveJobs, userId -> 0L, knownUserIds);
    }

    public QuotaService(QuotaConfig config, ToIntFunction<String> countActiveJobs, ToLongFunction<String> sumStorageBytes) {
        this(config, countActiveJobs, sumStorageBytes, Set.of());
    }

    public QuotaService(
        QuotaConfig config,
        ToIntFunction<String> countActiveJobs,
        ToLongFunction<String> sumStorageBytes,
        Set<String> knownUserIds
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.countActiveJobs = Objects.requireNonNull(countActiveJobs, "countActiveJobs");
        this.sumStorageBytes = Objects.requireNonNull(sumStorageBytes, "sumStorageBytes");
        this.knownUserIds = Set.copyOf(Objects.requireNonNull(knownUserIds, "knownUserIds"));
    }

    public int getMaxConcurrentJobs(String userId) {
        UserQuotaOverride userOverride = config.getUserOverrides().get(userId);
        if (userOverride != null && userOverride.getMaxConcurrentJobs() != null) {
            return userOverride.getMaxConcurrentJobs();
        }
        return config.getDefaultMaxConcurrentJobs();
    }

    public int getMaxRequestsPerMinute(String userId) {
        UserQuotaOverride userOverride = config.getUserOverrides().get(userId);
        if (userOverride != null && userOverride.getMaxRequestsPerMinute() != null) {
            return userOverride.getMaxRequestsPerMinute();
        }
        return config.getDefaultMaxRequestsPerMinute();
    }

    public long getMaxStorageBytes(String userId) {
        UserQuotaOverride userOverride = config.getUserOverrides().get(userId);
        if (userOverride != null && userOverride.getMaxStorageBytes() != null) {
            return userOverride.getMaxStorageBytes();
        }
        return config.getDefaultMaxStorageBytes();
    }

    /**
     * Returns {@code true} if {@code userId} is recognized. When {@code knownUserIds}
     * is empty (auth disabled), all queries succeed for backward compatibility.
     */
    public boolean isKnownUser(String userId) {
        return knownUserIds.isEmpty()
            || knownUserIds.contains(userId)
            || config.getUserOverrides().containsKey(userId);
    }

    /**
     * Atomically checks whether {@code userId} is under the per-minute request
     * rate limit and, if so, records the request.
     */
    public boolean consumeRateToken(String userId) {
        int limit = getMaxRequestsPerMinute(userId);
        return counter.tryConsume(userId, limit, WINDOW_MS);
    }

    /**
     * Returns the number of requests in the current sliding window for {@code userId}.
     * This is read-only and does not mutate state.
     */
    public int getRequestsInWindow(String userId) {
        return counter.count(userId, WINDOW_MS);
    }

    public QuotaUsage getUsage(String userId) {
        int activeJobs = countActiveJobs.applyAsInt(userId);
        long storageUsed = sumStorageBytes.applyAsLong(userId);
        return new QuotaUsage(
            userId,
            activeJobs,
            getMaxConcurrentJobs(userId),
            getRequestsInWindow(userId),
            getMaxRequestsPerMinute(userId),
            storageUsed,
            getMaxStorageBytes(userId)
        );
    }
}
