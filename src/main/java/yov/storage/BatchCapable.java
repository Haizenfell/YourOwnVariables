package yov.storage;

public interface BatchCapable {
    void beginBatch() throws Exception;
    void endBatch() throws Exception;
}
