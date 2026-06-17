package com.nyx.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nyx.config.QuotaConfig;
import com.nyx.config.UserQuotaOverride;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class QuotaServiceTest {
    private static final long DEFAULT_MAX_STORAGE_BYTES = 10_737_418_240L;

    @Test
    void consumeRateTokenAllowsRequestsUnderLimit() {
        QuotaConfig config = new QuotaConfig(true, 4, 5, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter());

        for (int i = 0; i < 5; i++) {
            assertTrue(service.consumeRateToken("alice"), "Request " + i + " should be allowed");
        }
    }

    @Test
    void consumeRateTokenRejectsWhenLimitExceeded() {
        QuotaConfig config = new QuotaConfig(true, 4, 3, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter());

        for (int i = 0; i < 3; i++) {
            service.consumeRateToken("alice");
        }
        assertFalse(service.consumeRateToken("alice"));
    }

    @Test
    void consumeRateTokenIsPerUser() {
        QuotaConfig config = new QuotaConfig(true, 4, 2, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter());

        for (int i = 0; i < 2; i++) {
            service.consumeRateToken("alice");
        }
        assertFalse(service.consumeRateToken("alice"));
        assertTrue(service.consumeRateToken("bob"));
    }

    @Test
    void getRequestsInWindowReturnsCountWithoutMutation() {
        QuotaConfig config = new QuotaConfig(true, 4, 60, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter());

        service.consumeRateToken("alice");
        service.consumeRateToken("alice");
        service.consumeRateToken("alice");

        assertEquals(3, service.getRequestsInWindow("alice"));
        assertEquals(3, service.getRequestsInWindow("alice"));
    }

    @Test
    void getRequestsInWindowReturnsZeroForUnknownUser() {
        QuotaConfig config = new QuotaConfig(true, 4, 60, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter());

        assertEquals(0, service.getRequestsInWindow("unknown"));
    }

    @Test
    void getUsageReturnsCorrectCounts() {
        QuotaConfig config = new QuotaConfig(true, 4, 60, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, fixedCounter(2));

        service.consumeRateToken("alice");
        service.consumeRateToken("alice");
        service.consumeRateToken("alice");

        QuotaUsage usage = service.getUsage("alice");
        assertEquals("alice", usage.userId());
        assertEquals(2, usage.activeJobs());
        assertEquals(4, usage.maxConcurrentJobs());
        assertEquals(3, usage.requestsInWindow());
        assertEquals(60, usage.maxRequestsPerMinute());
    }

    @Test
    void userOverrideTakesPrecedenceOverDefaults() {
        QuotaConfig config = new QuotaConfig(
            true,
            4,
            60,
            DEFAULT_MAX_STORAGE_BYTES,
            Map.of("vip", userQuotaOverride(10, 120, null))
        );
        QuotaService service = new QuotaService(config, noopCounter());

        assertEquals(10, service.getMaxConcurrentJobs("vip"));
        assertEquals(120, service.getMaxRequestsPerMinute("vip"));
        assertEquals(4, service.getMaxConcurrentJobs("regular"));
        assertEquals(60, service.getMaxRequestsPerMinute("regular"));
    }

    @Test
    void userOverrideWithOnlyOneFieldSetUsesDefaultForTheOther() {
        QuotaConfig config = new QuotaConfig(
            true,
            4,
            60,
            DEFAULT_MAX_STORAGE_BYTES,
            Map.of("partial", userQuotaOverride(8, null, null))
        );
        QuotaService service = new QuotaService(config, noopCounter());

        assertEquals(8, service.getMaxConcurrentJobs("partial"));
        assertEquals(60, service.getMaxRequestsPerMinute("partial"));
    }

    @Test
    void concurrentConsumeRateTokenRespectsLimit() throws Exception {
        QuotaConfig config = new QuotaConfig(true, 4, 50, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter());
        List<Boolean> results = runConcurrent(100, () -> service.consumeRateToken("alice"));

        long accepted = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(50, accepted, "Exactly 50 should be accepted");
    }

    @Test
    void rateLimitTokensAreReclaimedAfterWindowExpiry() {
        QuotaConfig config = new QuotaConfig(true, 4, 2, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter());

        assertTrue(service.consumeRateToken("alice"));
        assertTrue(service.consumeRateToken("alice"));
        assertFalse(service.consumeRateToken("alice"));
        assertEquals(2, service.getRequestsInWindow("alice"));
    }

    @Test
    void getUsageDelegatesToCountActiveJobsLambda() {
        AtomicInteger callCount = new AtomicInteger();
        QuotaConfig config = new QuotaConfig(true, 4, 60, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, userId -> {
            callCount.incrementAndGet();
            return "alice".equals(userId) ? 3 : 0;
        });

        QuotaUsage usage = service.getUsage("alice");
        assertEquals(3, usage.activeJobs());
        assertEquals(1, callCount.get());

        QuotaUsage bobUsage = service.getUsage("bob");
        assertEquals(0, bobUsage.activeJobs());
        assertEquals(2, callCount.get());
    }

    @Test
    void isKnownUserReturnsTrueWhenKnownUserIdsIsEmpty() {
        QuotaConfig config = new QuotaConfig(true, 4, 60, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter(), Set.of());

        assertTrue(service.isKnownUser("anyone"));
    }

    @Test
    void isKnownUserReturnsTrueForKnownUser() {
        QuotaConfig config = new QuotaConfig(true, 4, 60, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter(), Set.of("alice", "bob"));

        assertTrue(service.isKnownUser("alice"));
    }

    @Test
    void isKnownUserReturnsFalseForUnknownUser() {
        QuotaConfig config = new QuotaConfig(true, 4, 60, DEFAULT_MAX_STORAGE_BYTES, Map.of());
        QuotaService service = new QuotaService(config, noopCounter(), Set.of("alice"));

        assertFalse(service.isKnownUser("unknown"));
    }

    @Test
    void isKnownUserReturnsTrueForUserInOverridesEvenIfNotInKnownUserIds() {
        QuotaConfig config = new QuotaConfig(
            true,
            4,
            60,
            DEFAULT_MAX_STORAGE_BYTES,
            Map.of("vip", userQuotaOverride(10, null, null))
        );
        QuotaService service = new QuotaService(config, noopCounter(), Set.of("alice"));

        assertTrue(service.isKnownUser("vip"));
    }

    @Test
    void getMaxStorageBytesReturnsDefault() {
        QuotaConfig config = new QuotaConfig(true, 4, 60, 5_000_000L, Map.of());
        QuotaService service = new QuotaService(config, noopCounter());

        assertEquals(5_000_000L, service.getMaxStorageBytes("alice"));
    }

    @Test
    void getMaxStorageBytesReturnsUserOverride() {
        QuotaConfig config = new QuotaConfig(
            true,
            4,
            60,
            5_000_000L,
            Map.of("vip", userQuotaOverride(null, null, 50_000_000L))
        );
        QuotaService service = new QuotaService(config, noopCounter());

        assertEquals(50_000_000L, service.getMaxStorageBytes("vip"));
        assertEquals(5_000_000L, service.getMaxStorageBytes("regular"));
    }

    @Test
    void getUsageIncludesStorageFields() {
        QuotaConfig config = new QuotaConfig(true, 4, 60, 10_000_000L, Map.of());
        QuotaService service = new QuotaService(
            config,
            userId -> 0,
            userId -> "alice".equals(userId) ? 1_000_000L : 0L,
            Set.of()
        );

        QuotaUsage usage = service.getUsage("alice");
        assertEquals(1_000_000L, usage.storageUsedBytes());
        assertEquals(10_000_000L, usage.maxStorageBytes());
    }

    private static ToIntFunction<String> noopCounter() {
        return userId -> 0;
    }

    private static ToIntFunction<String> fixedCounter(int count) {
        return userId -> count;
    }

    private static UserQuotaOverride userQuotaOverride(Integer maxConcurrentJobs, Integer maxRequestsPerMinute, Long maxStorageBytes) {
        return new UserQuotaOverride(maxConcurrentJobs, maxRequestsPerMinute, maxStorageBytes);
    }

    private static List<Boolean> runConcurrent(int attempts, CheckedBooleanSupplier action) throws Exception {
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>(attempts);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return action.getAsBoolean();
                }));
            }
            ready.await();
            start.countDown();

            List<Boolean> results = new ArrayList<>(attempts);
            for (Future<Boolean> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    @FunctionalInterface
    private interface CheckedBooleanSupplier {
        boolean getAsBoolean() throws Exception;
    }
}
