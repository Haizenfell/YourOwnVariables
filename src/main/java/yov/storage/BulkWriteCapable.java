package yov.storage;

import java.util.Map;

public interface BulkWriteCapable {
    void applyBatch(Map<String, String> batch, String tombstone) throws Exception;
}
