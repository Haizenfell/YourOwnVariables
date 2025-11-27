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
package yov.cache;

import yov.storage.StorageBackend;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VariableCache {

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private final Logger logger;

    public VariableCache(Logger logger) {
        this.logger = logger;
    }

    public void loadFromDatabase(StorageBackend backend) {
        try {
            int count = 0;
            for (String key : backend.getAllKeys()) {
                String value = backend.get(key);
                if (value != null) {
                    cache.put(key, value);
                    count++;
                }
            }
            logger.info("Loaded " + count + " variables into cache.");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error loading cache from backend", e);
        }
    }

    public Map<String, String> getMap() {
        return cache;
    }

    public String get(String key) {
        return cache.get(key);
    }

    public String getOrDefault(String key, String def) {
        return cache.getOrDefault(key, def);
    }

    public void put(String key, String value) {
        cache.put(key, value);
    }

    public void remove(String key) {
        cache.remove(key);
    }
}
