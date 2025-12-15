package yov.storage;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface StorageBackend {

    void connect() throws Exception;

    void set(String key, String value) throws Exception;

    String get(String key) throws Exception;

    void delete(String key) throws Exception;

    List<String> getAllKeys() throws Exception;

    default List<String> getKeysByPrefix(String prefix) throws Exception {
        List<String> result = new ArrayList<>();
        if (prefix == null) return result;
        for (String key : getAllKeys()) {
            if (key != null && key.startsWith(prefix)) {
                result.add(key);
            }
        }
        return result;
    }

    default Map<String, String> getAllEntries() throws Exception {
        Map<String, String> out = new HashMap<>();
        for (String key : getAllKeys()) {
            if (key == null) continue;
            String val = get(key);
            if (val != null) out.put(key, val);
        }
        return out;
    }

    default Map<String, String> getEntriesByPrefix(String prefix) throws Exception {
        Map<String, String> out = new HashMap<>();
        if (prefix == null) return out;
        for (String key : getKeysByPrefix(prefix)) {
            if (key == null) continue;
            String val = get(key);
            if (val != null) out.put(key, val);
        }
        return out;
    }

    Connection getConnection() throws Exception;

    void close() throws Exception;
}
