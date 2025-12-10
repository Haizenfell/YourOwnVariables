package yov.service;

import org.bukkit.command.CommandSender;
import yov.async.WriteQueue;
import yov.cache.VariableCache;
import yov.storage.StorageBackend;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VariableService {

    private final StorageBackend backend;
    private final VariableCache cache;
    private final Logger logger;
    private final String prefix;
    private final WriteQueue writeQueue;

    private final Map<String, Long> localWriteTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> backendSyncTimes = new ConcurrentHashMap<>();
    private static final long LOCAL_WRITE_PROTECT_MS = 1000L;
    private static final long BACKEND_SYNC_COOLDOWN_MS = 1000L;
    private volatile boolean shuttingDown = false;

    public VariableService(StorageBackend backend,
                           VariableCache cache,
                           Logger logger,
                           String prefix,
                           WriteQueue writeQueue) {
        this.backend = backend;
        this.cache = cache;
        this.logger = logger;
        this.prefix = prefix;
        this.writeQueue = writeQueue;
    }

    public WriteQueue getWriteQueue() {
        return writeQueue;
    }

    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    private void markLocalWrite(String key) {
        if (key != null) {
            localWriteTimes.put(key, System.currentTimeMillis());
        }
    }

    public void syncPlayerFromStorage(String playerName) {
        if (playerName == null || playerName.isEmpty()) return;

        String prefixKey = playerName.toLowerCase(Locale.ROOT) + "_";

        try {
            List<String> keys = backend.getKeysByPrefix(prefixKey);
            Map<String, String> map = cache.getMap();

            List<String> toRemove = new ArrayList<>();
            for (String existingKey : map.keySet()) {
                if (existingKey.startsWith(prefixKey) && !keys.contains(existingKey)) {
                    toRemove.add(existingKey);
                }
            }
            for (String k : toRemove) {
                map.remove(k);
            }

            for (String key : keys) {
                String value = backend.get(key);
                if (value != null) {
                    map.put(key, value);
                } else {
                    map.remove(key);
                }
            }

        } catch (Exception e) {
            logger.log(Level.WARNING,
                    "Failed to sync variables from storage for player " + playerName, e);
        }
    }

    public String getSynchronizedValue(String rawKey) {
        if (rawKey == null) return null;

        String key = rawKey.toLowerCase(Locale.ROOT);
        String cached = cache.get(key);

        if (shuttingDown) {
            return cached;
        }

        long now = System.currentTimeMillis();

        Long lastWrite = localWriteTimes.get(key);
        if (lastWrite != null && now - lastWrite < LOCAL_WRITE_PROTECT_MS) {
            return cached;
        }

        Long lastSync = backendSyncTimes.get(key);
        if (lastSync != null && now - lastSync < BACKEND_SYNC_COOLDOWN_MS) {
            return cached;
        }

        try {
            String value = backend.get(key);
            backendSyncTimes.put(key, now);

            if (value == null) {
                cache.remove(key);
                return null;
            }

            cache.put(key, value);
            return value;

        } catch (Exception e) {
            if (!(e instanceof SQLException sqlEx
                    && sqlEx.getMessage() != null
                    && sqlEx.getMessage().contains("has been closed"))) {
                logger.log(Level.WARNING, "Error loading variable '" + key + "' from backend", e);
            }
            return cached;
        }
    }

    public void flushNow() {
        if (writeQueue == null) return;
        try {
            writeQueue.flush();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to flush write queue", e);
        }
    }

    public void setVariable(String key, String value, CommandSender sender, boolean silent) {
        cache.put(key, value);
        markLocalWrite(key);
        try {
            writeQueue.enqueueSet(key, value);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error saving variable " + key, e);
        }
        if (!silent && sender != null) {
            sender.sendMessage(prefix + "§aVariable '" + key + "' set to '" + value + "'");
        }
    }

    public void deleteVariable(String key, CommandSender sender, boolean silent) {
        cache.remove(key);
        markLocalWrite(key);
        try {
            writeQueue.enqueueDelete(key);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error deleting variable " + key, e);
        }
        if (!silent && sender != null) {
            sender.sendMessage(prefix + "§aVariable '" + key + "' deleted.");
        }
    }

    public void addVariable(String key, String amountStr, CommandSender sender, boolean silent) {
        modifyVariable(key, amountStr, sender, silent, true);
    }

    public void remVariable(String key, String amountStr, CommandSender sender, boolean silent) {
        modifyVariable(key, amountStr, sender, silent, false);
    }

    private boolean isWholeNumber(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void modifyVariable(String key, String amountStr, CommandSender sender,
                                boolean silent, boolean isAdd) {

        cache.compute(key, (k, oldVal) -> {

            if (oldVal == null) oldVal = "0";

            try {
                if (isWholeNumber(oldVal) && isWholeNumber(amountStr)) {
                    int current = Integer.parseInt(oldVal);
                    int delta = Integer.parseInt(amountStr);
                    int result = isAdd ? current + delta : current - delta;

                    markLocalWrite(k);
                    enqueueModify(k, String.valueOf(result));

                    if (!silent && sender != null) {
                        sender.sendMessage(prefix + "§aVariable '" + k + "' " +
                                (isAdd ? "increased" : "decreased") +
                                " by " + delta + ". New value: " + result);
                    }

                    return String.valueOf(result);
                }

                double current = Double.parseDouble(oldVal);
                double delta = Double.parseDouble(amountStr);
                double result = isAdd ? current + delta : current - delta;

                markLocalWrite(k);
                enqueueModify(k, String.valueOf(result));

                if (!silent && sender != null) {
                    sender.sendMessage(prefix + "§aVariable '" + k + "' " +
                            (isAdd ? "increased" : "decreased") +
                            " by " + delta + ". New value: " + result);
                }

                return String.valueOf(result);

            } catch (Exception e) {
                if (!silent && sender != null) {
                    sender.sendMessage(prefix + "§cError: Invalid number.");
                }
                return oldVal;
            }
        });
    }

    private void enqueueModify(String key, String value) {
        writeQueue.enqueueSet(key, value);
    }

    public void clearPlayerVariables(String playerName, CommandSender sender) {
        String prefixKey = playerName.toLowerCase(Locale.ROOT) + "_";

        Map<String, String> map = cache.getMap();
        List<String> toRemove = new ArrayList<>();

        for (String key : map.keySet()) {
            if (key.startsWith(prefixKey)) {
                toRemove.add(key);
            }
        }

        int removed = 0;

        for (String key : toRemove) {
            cache.remove(key);
            markLocalWrite(key);
            try {
                writeQueue.enqueueDelete(key);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error deleting variable " + key + " for userclear", e);
            }
            removed++;
        }

        sender.sendMessage(prefix + "§aRemoved §e" + removed + " §avariables for player §e" + playerName + "§a.");
    }
}
