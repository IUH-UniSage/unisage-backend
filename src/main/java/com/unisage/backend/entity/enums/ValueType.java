package com.unisage.backend.entity.enums;

/**
 * How {@link com.unisage.backend.entity.SystemConfig#getValue()} (always stored as a plain
 * string/TEXT column) should be parsed and validated. Checked in
 * {@code SystemConfigServiceImpl} before persisting an update — see UNISAGE-64.
 */
public enum ValueType {
    STRING,
    NUMBER,
    BOOLEAN,
    JSON
}
