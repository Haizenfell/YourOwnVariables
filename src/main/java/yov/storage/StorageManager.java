package yov.storage;

import yov.storage.impl.SqlStorage;
import yov.storage.impl.SqliteStorage;
import yov.storage.impl.YamlStorage;

import java.io.File;

public class StorageManager {

    private final StorageBackend backend;

    public StorageManager(File dataFolder,
                          String type,
                          String host,
                          int port,
                          String database,
                          String user,
                          String pass) {

        StorageType storageType = StorageType.fromString(type);

        backend = switch (storageType) {
            case YAML -> new YamlStorage(dataFolder);
            case MYSQL -> new SqlStorage("mysql", host, port, database, user, pass);
            case MARIADB -> new SqlStorage("mariadb", host, port, database, user, pass);
            case SQLITE -> new SqliteStorage(dataFolder);
        };
    }

    public StorageBackend getBackend() {
        return backend;
    }
}
