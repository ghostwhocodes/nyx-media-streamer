package com.nyx.qloud;

import com.nyx.common.VirtualPathResolver;

final class QloudPathPolicy {
    private QloudPathPolicy() {
    }

    static String toNyxPath(String qloudPath) {
        if (qloudPath == null || qloudPath.isBlank() || "/".equals(qloudPath)) {
            return "";
        }
        return qloudPath.startsWith("/") ? qloudPath.substring(1) : qloudPath;
    }

    static String toQloudPath(String nyxPath) {
        if (nyxPath == null || nyxPath.isBlank() || "/".equals(nyxPath)) {
            return "/";
        }
        return nyxPath.startsWith("/") ? nyxPath : "/" + nyxPath;
    }

    static String parentNyxPath(String nyxPath) {
        if (nyxPath == null || nyxPath.isBlank()) {
            return "";
        }
        int slash = nyxPath.lastIndexOf('/');
        return slash <= 0 ? "" : nyxPath.substring(0, slash);
    }

    static String browsePath(String nyxPath, VirtualPathResolver virtualPathResolver) {
        if (nyxPath == null || !nyxPath.isBlank() || virtualPathResolver.getRoots().size() != 1) {
            return nyxPath;
        }
        return virtualPathResolver.getRoots().getFirst().displayName();
    }
}
