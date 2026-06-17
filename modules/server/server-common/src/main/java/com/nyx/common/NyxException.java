package com.nyx.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;

public class NyxException extends Exception {
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final Map<String, String> details;

    public NyxException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    public NyxException(ErrorCode errorCode, String message, Map<String, String> details) {
        this(errorCode, message, details, null);
    }

    public NyxException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, Map.of(), cause);
    }

    public NyxException(ErrorCode errorCode, String message, Map<String, String> details, Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Map<String, String> getDetails() {
        return details;
    }

    @Override
    @NotNull
    public String getMessage() {
        return Objects.requireNonNull(super.getMessage());
    }
}
