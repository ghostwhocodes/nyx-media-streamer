package com.nyx.common;

import com.nyx.http.ContentType;
import com.nyx.http.HttpHeaders;
import com.nyx.http.HttpStatusCode;
import com.nyx.http.RoutingCall;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class RangeSupport {
    public static final RangeSupport INSTANCE = new RangeSupport();

    private RangeSupport() {
    }

    public static String generateETag(Path path) {
        long size = fileSize(path);
        long modifiedTime = lastModifiedMillis(path);
        return "\"" + size + "-" + modifiedTime + "\"";
    }

    public static boolean isCacheValid(Path path, long cachedSize, long cachedModifiedTime) {
        try {
            return Files.size(path) == cachedSize && Files.getLastModifiedTime(path).toMillis() == cachedModifiedTime;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void respondFile(RoutingCall call, Path path, String contentType) {
        respondFile(call, path, contentType, 3600);
    }

    public static void respondFile(RoutingCall call, Path path, String contentType, int cacheMaxAge) {
        if (!Files.isRegularFile(path)) {
            throw nyxException(ErrorCode.FILE_NOT_FOUND, "File not found: " + path);
        }

        String etag = generateETag(path);
        String ifNoneMatch = call.getRequest().getHeaders().get(HttpHeaders.IfNoneMatch);
        if (etag.equals(ifNoneMatch)) {
            call.getResponse().header(HttpHeaders.ETag, etag);
            call.respond(HttpStatusCode.Companion.getNotModified());
            return;
        }

        call.getResponse().header(HttpHeaders.ETag, etag);
        call.getResponse().header(HttpHeaders.CacheControl, "public, max-age=" + cacheMaxAge);
        call.getResponse().header(HttpHeaders.AcceptRanges, "bytes");
        call.getResponse().header(HttpHeaders.ContentType, contentType);

        long size = fileSize(path);
        ByteRange byteRange = parseRangeHeader(call.getRequest().getHeaders().get(HttpHeaders.Range), size);
        if (byteRange == null) {
            call.respondFile(path, contentType);
            return;
        }

        call.respond(HttpStatusCode.Companion.getPartialContent());
        call.getResponse().header(HttpHeaders.ContentLength, Long.toString(byteRange.length()));
        call.getResponse().header(
            HttpHeaders.ContentRange,
            "bytes " + byteRange.start() + "-" + byteRange.endInclusive() + "/" + size
        );
        call.respondOutputStream(ContentType.Companion.parse(contentType), output -> {
            try (InputStream input = Files.newInputStream(path)) {
                copyRangeTo(input, output, byteRange);
            } catch (IOException exception) {
                throw RangeSupport.<RuntimeException>sneakyThrow(exception);
            }
        });
    }

    private static ByteRange parseRangeHeader(String rangeHeader, long fileSize) {
        if (rangeHeader == null || rangeHeader.isBlank() || !rangeHeader.startsWith("bytes=")) {
            return null;
        }

        String[] ranges = rangeHeader.substring("bytes=".length()).split(",");
        if (ranges.length != 1) {
            return null;
        }

        String rawRange = ranges[0].trim();
        int separatorIndex = rawRange.indexOf('-');
        if (separatorIndex < 0) {
            return null;
        }

        String startToken = rawRange.substring(0, separatorIndex).trim();
        String endToken = rawRange.substring(separatorIndex + 1).trim();
        if (startToken.isEmpty() && endToken.isEmpty()) {
            return null;
        }

        ByteRange parsedRange;
        if (startToken.isEmpty()) {
            Long suffixLength = parseLong(endToken);
            if (suffixLength == null || suffixLength <= 0L) {
                return null;
            }
            long length = Math.min(suffixLength, fileSize);
            parsedRange = new ByteRange(fileSize - length, fileSize - 1);
        } else {
            Long start = parseLong(startToken);
            if (start == null || start < 0L || start >= fileSize) {
                return null;
            }

            long endInclusive;
            if (endToken.isBlank()) {
                endInclusive = fileSize - 1;
            } else {
                Long requestedEnd = parseLong(endToken);
                if (requestedEnd == null || requestedEnd < start) {
                    return null;
                }
                endInclusive = Math.min(requestedEnd, fileSize - 1);
            }

            parsedRange = new ByteRange(start, endInclusive);
        }

        return parsedRange.length() > 0L ? parsedRange : null;
    }

    private static void copyRangeTo(InputStream input, OutputStream output, ByteRange range) throws IOException {
        skipFully(input, range.start());
        byte[] buffer = new byte[8192];
        long remaining = range.length();
        while (remaining > 0L) {
            int bytesRead = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (bytesRead < 0) {
                break;
            }
            output.write(buffer, 0, bytesRead);
            remaining -= bytesRead;
        }
    }

    private static void skipFully(InputStream input, long bytesToSkip) throws IOException {
        long remaining = bytesToSkip;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() == -1) {
                break;
            }
            remaining -= 1L;
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return RangeSupport.<Long>sneakyThrow(exception);
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return RangeSupport.<Long>sneakyThrow(exception);
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static RuntimeException nyxException(ErrorCode errorCode, String message) {
        return RangeSupport.<RuntimeException>sneakyThrow(new NyxException(errorCode, message, Map.of(), null));
    }

    private static <T> T sneakyThrow(Throwable throwable) {
        RangeSupport.<RuntimeException>throwUnchecked(throwable);
        throw new AssertionError("Unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private record ByteRange(long start, long endInclusive) {
        private long length() {
            return endInclusive - start + 1;
        }
    }
}
