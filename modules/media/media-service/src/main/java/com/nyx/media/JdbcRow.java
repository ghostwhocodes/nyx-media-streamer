package com.nyx.media;

import java.sql.ResultSet;
import java.sql.SQLException;

final class JdbcRow {
    private JdbcRow() {
    }

    static Integer getNullableInt(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).intValue();
    }

    static Long getNullableLong(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).longValue();
    }

    static Double getNullableDouble(ResultSet resultSet, String columnLabel) throws SQLException {
        Object value = resultSet.getObject(columnLabel);
        return value == null ? null : ((Number) value).doubleValue();
    }
}
