package yov.storage;

public enum StorageType {
    SQLITE,
    MARIADB,
    YAML;

    public static StorageType fromString(String s) {
        if (s == null) return SQLITE;
        return switch (s.toLowerCase()) {
            case "mariadb" -> MARIADB;
            case "yaml", "yml" -> YAML;
            case "sqlite" -> SQLITE;
            default -> SQLITE;
        };
    }
}