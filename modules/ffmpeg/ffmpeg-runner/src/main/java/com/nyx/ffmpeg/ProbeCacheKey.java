package com.nyx.ffmpeg;

public record ProbeCacheKey(String path, long mtime, long size) {
}
