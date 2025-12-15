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
            case SQLITE -> new SqliteStorage(dataFolder);
            case MARIADB -> new SqlStorage("mariadb", host, port, database, user, pass);
            case HUSKSYNC -> createHuskSyncBackend(dataFolder);
        };
    }

    private StorageBackend createHuskSyncBackend(File dataFolder) {
        try {
            Class<?> cls = Class.forName("yov.storage.impl.HuskSyncStorage");
            return (StorageBackend) cls.getConstructor(File.class).newInstance(dataFolder);
        } catch (Throwable t) {
            throw new IllegalStateException("HuskSync storage selected, but HuskSync integration classes could not be loaded", t);
        }
    }

    public StorageBackend getBackend() {
        return backend;
    }
}
