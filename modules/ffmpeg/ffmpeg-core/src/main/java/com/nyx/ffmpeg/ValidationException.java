package com.nyx.ffmpeg;

import java.util.List;

public final class ValidationException extends Exception {
    private final List<String> violations;

    public ValidationException(List<String> violations) {
        super("FFmpeg command validation failed: " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}
