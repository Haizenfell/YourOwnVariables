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
