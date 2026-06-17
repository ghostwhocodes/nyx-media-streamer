package com.nyx.qloud;

final class QloudScalars {
    private QloudScalars() {
    }

    static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    static String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }
}
