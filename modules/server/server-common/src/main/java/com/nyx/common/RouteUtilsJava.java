package com.nyx.common;

import com.nyx.http.AuthMode;
import com.nyx.http.Route;
import com.nyx.http.RoutingCall;
import com.nyx.http.UserIdPrincipal;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class RouteUtilsJava {
    public static final int MAX_PAGE = 10_000;

    private RouteUtilsJava() {
    }

    public static void enforceUserRateLimit(RoutingCall call, QuotaService quotaService) {
        UserIdPrincipal principal = call.principal(UserIdPrincipal.class);
        if (principal == null || quotaService == null) {
            return;
        }
        if (!quotaService.consumeRateToken(principal.getName())) {
            throw sneakyNyxException(ErrorCode.QUOTA_EXCEEDED, "Per-user request rate limit exceeded");
        }
    }

    public static void optionalAuth(Route route, List<String> authProviders, Consumer<? super Route> block) {
        block.accept(route.withAuth(AuthMode.OPTIONAL, authProviders));
    }

    public static void requireAuth(Route route, List<String> authProviders, Consumer<? super Route> block) {
        block.accept(route.withAuth(AuthMode.REQUIRED, authProviders));
    }

    public static int getPageParam(RoutingCall call, int defaultValue) {
        String raw = call.getQueryParameters().get("page");
        int page = raw != null ? parseInt(raw, defaultValue) : defaultValue;
        if (page < 1 || page > MAX_PAGE) {
            throw sneakyNyxException(ErrorCode.INVALID_REQUEST, "Page must be between 1 and " + MAX_PAGE);
        }
        return page;
    }

    public static int getLimitParam(RoutingCall call, int defaultValue, int max) {
        String raw = call.getQueryParameters().get("limit");
        int limit = raw != null ? parseInt(raw, defaultValue) : defaultValue;
        if (limit < 1 || limit > max) {
            throw sneakyNyxException(ErrorCode.INVALID_REQUEST, "Limit must be between 1 and " + max);
        }
        return limit;
    }

    public static long pageOffset(int page, int limit) {
        return (page - 1L) * limit;
    }

    public static int pageStartIndex(int page, int limit, int total) {
        return (int) Math.min(pageOffset(page, limit), total);
    }

    public static int pageEndIndex(int start, int limit, int total) {
        return (int) Math.min((long) start + limit, total);
    }

    public static String getRequiredParam(RoutingCall call, String name) {
        String value = call.getQueryParameters().get(name);
        if (value == null) {
            throw sneakyNyxException(ErrorCode.INVALID_REQUEST, "Missing required parameter: " + name);
        }
        return value;
    }

    public static String getRequiredPathParam(RoutingCall call, String name) {
        String value = call.getPathParameters().get(name);
        if (value == null) {
            throw sneakyNyxException(ErrorCode.INVALID_REQUEST, "Missing required path parameter: " + name);
        }
        return value;
    }

    public static Path resolvePathParam(String virtualPath, PathSecurity pathSecurity, VirtualPathResolver resolver) {
        Path absolute = resolver.resolveToAbsolute(virtualPath);
        pathSecurity.validate(absolute.toString());
        return absolute;
    }

    public static Path resolveDirParam(String virtualPath, PathSecurity pathSecurity, VirtualPathResolver resolver) {
        Path absolute = resolver.resolveToAbsolute(virtualPath);
        pathSecurity.validateDirectory(absolute.toString());
        return absolute;
    }

    private static int parseInt(String raw, int defaultValue) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private static RuntimeException sneakyNyxException(ErrorCode errorCode, String message) {
        return RouteUtilsJava.<RuntimeException>sneakyThrow(new NyxException(errorCode, message));
    }

    private static <T> T sneakyThrow(Throwable throwable) {
        RouteUtilsJava.<RuntimeException>throwUnchecked(throwable);
        throw new AssertionError("Unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable throwable) throws E {
        throw (E) throwable;
    }
}
