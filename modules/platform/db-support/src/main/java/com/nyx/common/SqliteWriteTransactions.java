package com.nyx.common;

import java.util.function.Function;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

public final class SqliteWriteTransactions {
    private static final int SQLITE_WRITE_MAX_ATTEMPTS = 5;
    private static final long SQLITE_WRITE_MIN_RETRY_DELAY_MS = 25L;
    private static final long SQLITE_WRITE_MAX_RETRY_DELAY_MS = 125L;

    private SqliteWriteTransactions() {}

    public static <T> T withHandleUnchecked(Jdbi jdbi, Function<Handle, T> block) {
        try (Handle handle = jdbi.open()) {
            return block.apply(handle);
        }
    }

    public static <T> T inTransactionUnchecked(Jdbi jdbi, Function<Handle, T> block) {
        try (Handle handle = jdbi.open()) {
            return handle.inTransaction(transactionHandle -> block.apply(transactionHandle));
        }
    }

    public static <T> T sqliteWriteTransaction(Jdbi jdbi, Function<Handle, T> block) {
        return sqliteWriteTransaction(
            jdbi,
            SQLITE_WRITE_MAX_ATTEMPTS,
            SQLITE_WRITE_MIN_RETRY_DELAY_MS,
            SQLITE_WRITE_MAX_RETRY_DELAY_MS,
            block
        );
    }

    public static <T> T sqliteWriteTransaction(Jdbi jdbi, int retryAttempts, Function<Handle, T> block) {
        return sqliteWriteTransaction(
            jdbi,
            retryAttempts,
            SQLITE_WRITE_MIN_RETRY_DELAY_MS,
            SQLITE_WRITE_MAX_RETRY_DELAY_MS,
            block
        );
    }

    public static <T> T sqliteWriteTransaction(
        Jdbi jdbi,
        int retryAttempts,
        long minRetryDelayMs,
        long maxRetryDelayMs,
        Function<Handle, T> block
    ) {
        if (retryAttempts <= 0) {
            throw new IllegalArgumentException("retryAttempts must be positive");
        }
        if (minRetryDelayMs < 0L) {
            throw new IllegalArgumentException("minRetryDelayMs must be non-negative");
        }
        if (maxRetryDelayMs < minRetryDelayMs) {
            throw new IllegalArgumentException("maxRetryDelayMs must be >= minRetryDelayMs");
        }

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return inTransactionUnchecked(jdbi, block);
            } catch (RuntimeException exception) {
                if (attempt >= retryAttempts || !isSqliteBusyConflict(exception)) {
                    throw exception;
                }
            }

            try {
                Thread.sleep(Math.min(minRetryDelayMs * attempt, maxRetryDelayMs));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(interruptedException);
            }
        }
    }

    private static boolean isSqliteBusyConflict(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLiteException sqliteException && isSqliteBusyConflict(sqliteException)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isSqliteBusyConflict(SQLiteException sqliteException) {
        SQLiteErrorCode resultCode = sqliteException.getResultCode();
        return resultCode == SQLiteErrorCode.SQLITE_BUSY
            || resultCode == SQLiteErrorCode.SQLITE_BUSY_RECOVERY
            || resultCode == SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT
            || resultCode == SQLiteErrorCode.SQLITE_BUSY_TIMEOUT;
    }
}
