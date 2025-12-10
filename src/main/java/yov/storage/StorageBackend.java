package yov.storage;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

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

    Connection getConnection() throws Exception;

    void close() throws Exception;
}
