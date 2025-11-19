package yov.storage;

import java.util.List;

public interface StorageBackend {

    void connect() throws Exception;

    void set(String key, String value) throws Exception;

    String get(String key) throws Exception;

    void delete(String key) throws Exception;

    List<String> getAllKeys() throws Exception;

    void close() throws Exception;
}
