package yov.storage;

public enum StorageType {
    SQLITE,
    MARIADB,
    YAML,
    HUSKSYNC;

    public static StorageType fromString(String s) {
        if (s == null) return SQLITE;
        return switch (s.toLowerCase()) {
            case "mariadb", "mysql" -> MARIADB;
            case "yaml", "yml" -> YAML;
            case "husksync", "husk-sync", "husk_sync" -> HUSKSYNC;
            case "sqlite" -> SQLITE;
            default -> SQLITE;
        };
    }
}
