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
