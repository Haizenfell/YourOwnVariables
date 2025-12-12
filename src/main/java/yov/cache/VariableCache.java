package yov.cache;

import yov.storage.StorageBackend;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
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
            var entries = backend.getAllEntries();

            for (var e : entries.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k != null && v != null) {
                    cache.put(k, v);
                    count++;
                }
            }

            logger.info("Loaded " + count + " variables into cache.");
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error loading cache from backend", e);
        }
    }

    public String compute(String key, BiFunction<String, String, String> func) {
        return cache.compute(key, func);
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
