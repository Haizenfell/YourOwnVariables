package yov.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.logging.Logger;

public class VariableCache {

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    @SuppressWarnings("unused")
    private final Logger logger;

    public VariableCache(Logger logger) {
        this.logger = logger;
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
