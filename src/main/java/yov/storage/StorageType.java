/*
 * This file is part of YourOwnVariables.
 * Copyright (C) 2025 Haizenfell
 *
 * Licensed under the YourOwnVariables Proprietary License.
 * Unauthorized copying, modification, distribution, or reverse engineering
 * of this software is strictly prohibited.
 *
 * Full license text is provided in the LICENSE file.
 */
package yov.storage;

public enum StorageType {
    SQLITE,
    MYSQL,
    MARIADB,
    YAML;

    public static StorageType fromString(String s) {
        if (s == null) return SQLITE;
        return switch (s.toLowerCase()) {
            case "mysql" -> MYSQL;
            case "mariadb" -> MARIADB;
            case "yaml", "yml" -> YAML;
            case "sqlite" -> SQLITE;
            default -> SQLITE;
        };
    }
}
