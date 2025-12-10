package yov.async;

import yov.storage.BatchCapable;
import yov.storage.StorageBackend;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class WriteQueue {

    private final StorageBackend backend;

    private static final String TOMBSTONE = "__DELETE__";

    private final ConcurrentHashMap<String, String> pending = new ConcurrentHashMap<>();

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "YOV-WriteQueue");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean flushing = new AtomicBoolean(false);

    public WriteQueue(StorageBackend backend) {
        this.backend = backend;
        executor.scheduleAtFixedRate(this::flushSafe, 200, 200, TimeUnit.MILLISECONDS);
    }

    public void enqueueSet(String key, String value) {
        pending.put(key, value);
    }

    public void enqueueDelete(String key) {
        pending.put(key, TOMBSTONE);
    }

    private void flushSafe() {
        try {
            flush();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void flush() throws Exception {
        if (pending.isEmpty()) {
            return;
        }

        if (!flushing.compareAndSet(false, true)) {
            return;
        }

        try {
            Map<String, String> batch = new HashMap<>(pending);

            for (Map.Entry<String, String> entry : batch.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                pending.remove(key, value);
            }

            if (batch.isEmpty()) {
                return;
            }

            if (backend instanceof BatchCapable batchCapable) {
                batchCapable.beginBatch();
                try {
                    for (Map.Entry<String, String> entry : batch.entrySet()) {
                        String key = entry.getKey();
                        String val = entry.getValue();

                        if (TOMBSTONE.equals(val)) {
                            backend.delete(key);
                        } else {
                            backend.set(key, val);
                        }
                    }
                    batchCapable.endBatch();
                } catch (Exception e) {
                    try {
                        batchCapable.endBatch();
                    } catch (Exception ignored) {}
                    throw e;
                }
                return;
            }

            Connection conn;
            try {
                conn = backend.getConnection();
            } catch (Exception e) {
                conn = null;
            }

            if (conn != null) {
                try (Connection c = conn;
                     PreparedStatement insertPS = c.prepareStatement(
                             "INSERT INTO `variables` (`key`, `value`) VALUES (?, ?) " +
                                     "ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)"
                     );
                     PreparedStatement deletePS = c.prepareStatement(
                             "DELETE FROM `variables` WHERE `key` = ?"
                     )) {

                    for (Map.Entry<String, String> entry : batch.entrySet()) {
                        String key = entry.getKey();
                        String val = entry.getValue();

                        if (TOMBSTONE.equals(val)) {
                            deletePS.setString(1, key);
                            deletePS.addBatch();
                        } else {
                            insertPS.setString(1, key);
                            insertPS.setString(2, val);
                            insertPS.addBatch();
                        }
                    }

                    insertPS.executeBatch();
                    deletePS.executeBatch();
                }
                return;
            }

            for (Map.Entry<String, String> entry : batch.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();

                if (TOMBSTONE.equals(val)) {
                    backend.delete(key);
                } else {
                    backend.set(key, val);
                }
            }

        } finally {
            flushing.set(false);
        }
    }

    public void close() {
        try {
            flush();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
        }
    }
}
