package yov.service;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import yov.async.WriteQueue;
import yov.cache.VariableCache;
import yov.storage.StorageBackend;
import yov.storage.StorageType;
import yov.sync.PlayerVariableSync;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VariableService {

    private static final class OnlineKey {
        private final Player player;
        private final String playerLower;
        private final String var;

        private OnlineKey(Player player, String playerLower, String var) {
            this.player = player;
            this.playerLower = playerLower;
            this.var = var;
        }
    }

    private final StorageType storageType;
    private final StorageBackend backend;
    private final VariableCache cache;
    private final Logger logger;
    private final String prefix;
    private final WriteQueue writeQueue;
    private final PlayerVariableSync playerSync;

    private final Map<String, Long> localWriteTimes = new ConcurrentHashMap<>();
    private final Map<String, Long> backendSyncTimes = new ConcurrentHashMap<>();

    private static final long LOCAL_WRITE_PROTECT_MS = 200;
    private static final long BACKEND_SYNC_COOLDOWN_MS = 500;

    private volatile boolean shuttingDown = false;

    private final Set<String> loadedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Map<String, String>> rawByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> playerIndex = new ConcurrentHashMap<>();

    public VariableService(StorageType storageType,
                           StorageBackend backend,
                           VariableCache cache,
                           Logger logger,
                           String prefix,
                           WriteQueue writeQueue,
                           PlayerVariableSync playerSync) {
        this.storageType = storageType;
        this.backend = backend;
        this.cache = cache;
        this.logger = logger;
        this.prefix = prefix;
        this.writeQueue = writeQueue;
        this.playerSync = playerSync;
    }

    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }

    public boolean isPreLoginSupported() {
        return false;
    }

    public void preloadPlayerForLogin(String username) {
        markPlayerLoaded(username, true);
    }

    public boolean isPlayerLoaded(String usernameLower) {
        if (usernameLower == null) return true;
        if (storageType != StorageType.HUSKSYNC) return true;
        return loadedPlayers.contains(usernameLower.toLowerCase(Locale.ROOT));
    }

    public void markPlayerLoaded(String usernameLower, boolean loaded) {
        if (usernameLower == null) return;
        String lower = usernameLower.toLowerCase(Locale.ROOT);
        if (loaded) loadedPlayers.add(lower);
        else loadedPlayers.remove(lower);
    }

    public void unloadPlayer(String usernameLower) {
        if (usernameLower == null) return;
        String lower = usernameLower.toLowerCase(Locale.ROOT);

        rawByPlayer.remove(lower);

        Set<String> keys = playerIndex.remove(lower);
        if (keys != null) {
            for (String k : keys) cache.remove(k);
        }
    }

    public void replacePlayerVariables(String playerLower, Map<String, String> fullKeyToValue) {
        if (playerLower == null) return;
        String lower = playerLower.toLowerCase(Locale.ROOT);

        Map<String, String> safe = fullKeyToValue == null ? Map.of() : fullKeyToValue;

        Map<String, String> vars = new HashMap<>();
        Set<String> newKeys = ConcurrentHashMap.newKeySet();

        for (Map.Entry<String, String> e : safe.entrySet()) {
            String fullKey = e.getKey();
            String val = e.getValue();
            if (fullKey == null) continue;
            String fk = fullKey.toLowerCase(Locale.ROOT);
            if (!fk.startsWith(lower + "_")) continue;
            newKeys.add(fk);

            if (val == null) {
                cache.remove(fk);
            } else {
                cache.put(fk, val);
            }

            String var = fk.substring(lower.length() + 1);
            if (!var.isEmpty() && val != null) vars.put(var, val);
            if (!var.isEmpty() && val == null) vars.remove(var);
        }

        Set<String> oldKeys = playerIndex.put(lower, newKeys);
        if (oldKeys != null) {
            for (String k : oldKeys) {
                if (!newKeys.contains(k)) cache.remove(k);
            }
        }

        rawByPlayer.put(lower, new ConcurrentHashMap<>(vars));
        markPlayerLoaded(lower, true);
    }

    public Map<String, String> snapshotPlayerVars(String playerLower) {
        if (playerLower == null) return Map.of();
        String lower = playerLower.toLowerCase(Locale.ROOT);

        Map<String, String> vars = rawByPlayer.get(lower);
        if (vars != null) return new HashMap<>(vars);

        Map<String, String> out = new HashMap<>();
        String prefixKey = lower + "_";
        for (Map.Entry<String, String> e : cache.getMap().entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith(prefixKey)) continue;
            String var = k.substring(prefixKey.length());
            if (!var.isEmpty()) out.put(var, e.getValue());
        }
        return out;
    }

    public void syncPlayerFromStorage(String playerName) {
        if (storageType == StorageType.HUSKSYNC) {
            return;
        }

        try {
            String prefixKey = playerName.toLowerCase(Locale.ROOT) + "_";
            var entries = backend.getEntriesByPrefix(prefixKey);

            for (var e : entries.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k != null && v != null) cache.put(k.toLowerCase(Locale.ROOT), v);
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to sync player variables from storage", e);
        }
    }

    public String getSynchronizedValue(String rawKey) {
        if (rawKey == null) return null;

        String key = rawKey.toLowerCase(Locale.ROOT);

        if (storageType == StorageType.HUSKSYNC) {
            return cache.get(key);
        }

        if (shuttingDown) {
            return cache.get(key);
        }

        long now = System.currentTimeMillis();

        Long lastWrite = localWriteTimes.get(key);
        if (lastWrite != null && now - lastWrite < LOCAL_WRITE_PROTECT_MS) {
            return cache.get(key);
        }

        Long lastSync = backendSyncTimes.get(key);
        if (lastSync != null && now - lastSync < BACKEND_SYNC_COOLDOWN_MS) {
            return cache.get(key);
        }

        backendSyncTimes.put(key, now);

        try {
            String val;
            synchronized (backend) {
                val = backend.get(key);
            }
            if (val != null) {
                cache.put(key, val);
            }
            return val;
        } catch (SQLException ex) {
            return cache.get(key);
        } catch (Exception ex) {
            logger.log(Level.WARNING, "Failed to sync variable '" + key + "' from storage", ex);
            return cache.get(key);
        }
    }

    public void flushNow() {
        if (writeQueue == null) return;
        try {
            writeQueue.flush();
        } catch (Exception e) {
            logger.warning("Failed to flush write queue: " + e.getMessage());
        }
    }

    public void setVariable(String key, String value, CommandSender sender, boolean silent) {
        String k = key.toLowerCase(Locale.ROOT);

        cache.put(k, value);
        localWriteTimes.put(k, System.currentTimeMillis());

        if (storageType == StorageType.HUSKSYNC) {
            pushOrStoreHusk(k, value, false);
        } else {
            if (writeQueue != null) {
                writeQueue.enqueueSet(k, value);
            } else {
                safeBackendSet(k, value);
            }
        }

        if (!silent && sender != null) {
            sender.sendMessage(prefix + "§aVariable '" + k + "' set to '" + value + "'");
        }
    }

    public void deleteVariable(String key, CommandSender sender, boolean silent) {
        String k = key.toLowerCase(Locale.ROOT);

        cache.remove(k);
        localWriteTimes.put(k, System.currentTimeMillis());

        if (storageType == StorageType.HUSKSYNC) {
            pushOrStoreHusk(k, null, true);
        } else {
            if (writeQueue != null) {
                writeQueue.enqueueDelete(k);
            } else {
                safeBackendDelete(k);
            }
        }

        if (!silent && sender != null) {
            sender.sendMessage(prefix + "§aVariable '" + k + "' deleted.");
        }
    }

    public void addVariable(String key, String amount, CommandSender sender, boolean silent) {
        modifyVariable(key, amount, sender, silent, true);
    }

    public void remVariable(String key, String amount, CommandSender sender, boolean silent) {
        modifyVariable(key, amount, sender, silent, false);
    }

    private void modifyVariable(String key, String amountStr, CommandSender sender, boolean silent, boolean isAdd) {
        String k = key.toLowerCase(Locale.ROOT);

        try {
            cache.compute(k, (kk, oldVal) -> {
                String cur = (oldVal == null) ? "0" : oldVal;

                String result;
                if (isWholeNumber(cur) && isWholeNumber(amountStr)) {
                    int curInt = Integer.parseInt(cur);
                    int delta = Integer.parseInt(amountStr);
                    int res = isAdd ? curInt + delta : curInt - delta;
                    result = String.valueOf(res);
                } else {
                    String curNorm = normalizeDecimal(cur);
                    String deltaNorm = normalizeDecimal(amountStr);
                    result = isAdd ? addDecimal(curNorm, deltaNorm) : subDecimal(curNorm, deltaNorm);
                }

                localWriteTimes.put(k, System.currentTimeMillis());

                if (storageType == StorageType.HUSKSYNC) {
                    pushOrStoreHusk(k, result, false);
                } else {
                    if (writeQueue != null) writeQueue.enqueueSet(k, result);
                    else safeBackendSet(k, result);
                }

                return result;
            });

            if (!silent && sender != null) {
                sender.sendMessage(prefix + "§aVariable '" + k + "' " + (isAdd ? "increased" : "decreased") +
                        " by " + amountStr + ". New value: " + cache.get(k));
            }

        } catch (Exception e) {
            if (!silent && sender != null) {
                sender.sendMessage(prefix + "§cError: Invalid number.");
            }
        }
    }

    public void clearPlayerVariables(String playerName, CommandSender sender) {
        if (playerName == null || playerName.isEmpty()) return;
        String playerLower = playerName.toLowerCase(Locale.ROOT);
        String prefixKey = playerLower + "_";

        if (storageType == StorageType.HUSKSYNC) {
            int removed = 0;

            Set<String> keys = playerIndex.remove(playerLower);
            if (keys != null) {
                for (String k : keys) {
                    if (k != null && k.startsWith(prefixKey)) {
                        cache.remove(k);
                        removed++;
                    }
                }
            } else {
                for (String k : cache.getMap().keySet()) {
                    if (k != null && k.startsWith(prefixKey)) {
                        cache.remove(k);
                        removed++;
                    }
                }
            }

            rawByPlayer.put(playerLower, new ConcurrentHashMap<>());
            playerIndex.put(playerLower, ConcurrentHashMap.newKeySet());

            Player p = findOnlinePlayer(playerName);
            if (p != null && playerSync != null && playerSync.isEnabled() && isPlayerLoaded(playerLower)) {
                playerSync.push(p, Map.of());
            } else {
                try {
                    var m = backend.getClass().getMethod("replacePlayerVariables", String.class, Map.class);
                    m.invoke(backend, playerLower, Map.of());
                } catch (Exception ignored) {
                }
            }

            if (sender != null) sender.sendMessage(prefix + "§aRemoved §e" + removed + "§a variables for player §e" + playerName + "§a.");
            return;
        }

        try {
            Map<String, String> entries;
            synchronized (backend) {
                entries = backend.getEntriesByPrefix(prefixKey);
            }
            for (String fullKey : entries.keySet()) {
                cache.remove(fullKey);
                localWriteTimes.put(fullKey, System.currentTimeMillis());
                if (writeQueue != null) writeQueue.enqueueDelete(fullKey);
                else safeBackendDelete(fullKey);
            }
            if (sender != null) sender.sendMessage(prefix + "§aRemoved §e" + entries.size() + "§a variables for player §e" + playerName + "§a.");
        } catch (Exception e) {
            if (sender != null) sender.sendMessage(prefix + "§cFailed to clear player variables. See console.");
            logger.warning("Failed to clear variables for " + playerLower + ": " + e.getMessage());
        }
    }

    private void pushOrStoreHusk(String fullKey, String value, boolean isDelete) {
        if (fullKey == null) return;
        String k = fullKey.toLowerCase(Locale.ROOT);

        if (k.indexOf('_') <= 0) {
            if (isDelete) safeBackendDelete(k);
            else safeBackendSet(k, value);
            return;
        }

        OnlineKey ok = resolveOnlineKey(k);
        if (ok == null) {
            if (isDelete) safeBackendDelete(k);
            else safeBackendSet(k, value);
            return;
        }

        boolean onlineLoaded = playerSync != null && playerSync.isEnabled() && isPlayerLoaded(ok.playerLower);
        if (!onlineLoaded) {
            if (isDelete) safeBackendDelete(k);
            else safeBackendSet(k, value);
            return;
        }

        Map<String, String> map = rawByPlayer.computeIfAbsent(ok.playerLower, x -> new ConcurrentHashMap<>());
        if (isDelete || value == null) map.remove(ok.var);
        else map.put(ok.var, value);

        String full = ok.playerLower + "_" + ok.var;
        Set<String> keySet = playerIndex.computeIfAbsent(ok.playerLower, x -> ConcurrentHashMap.newKeySet());
        if (isDelete || value == null) keySet.remove(full);
        else keySet.add(full);

        playerSync.push(ok.player, map);
    }

    private OnlineKey resolveOnlineKey(String fullKeyLower) {
        if (fullKeyLower == null || fullKeyLower.isEmpty()) return null;

        Player best = null;
        int bestLen = -1;
        String bestLower = null;

        for (Player p : Bukkit.getOnlinePlayers()) {
            String n = p.getName();
            if (n == null || n.isEmpty()) continue;
            String pl = n.toLowerCase(Locale.ROOT);
            String prefix = pl + "_";
            if (!fullKeyLower.startsWith(prefix)) continue;
            if (pl.length() > bestLen) {
                best = p;
                bestLen = pl.length();
                bestLower = pl;
            }
        }

        if (best == null) return null;
        String var = fullKeyLower.substring(bestLen + 1);
        if (var.isEmpty()) return null;
        return new OnlineKey(best, bestLower, var);
    }

    private void safeBackendSet(String key, String value) {
        try {
            synchronized (backend) {
                backend.set(key, value);
            }
        } catch (Exception e) {
            logger.warning("Failed to write key '" + key + "' to backend: " + e.getMessage());
        }
    }

    private void safeBackendDelete(String key) {
        try {
            synchronized (backend) {
                backend.delete(key);
            }
        } catch (Exception e) {
            logger.warning("Failed to delete key '" + key + "' from backend: " + e.getMessage());
        }
    }

    private Player findOnlinePlayer(String name) {
        if (name == null) return null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }

    private boolean isWholeNumber(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String normalizeDecimal(String s) {
        BigDecimal bd = new BigDecimal(s.trim().replace(',', '.'));
        bd = bd.setScale(5, RoundingMode.HALF_UP).stripTrailingZeros();
        String out = bd.toPlainString();
        return out.equals("-0") ? "0" : out;
    }

    private String addDecimal(String a, String b) {
        BigDecimal x = new BigDecimal(a.trim().replace(',', '.'));
        BigDecimal y = new BigDecimal(b.trim().replace(',', '.'));
        BigDecimal r = x.add(y).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros();
        String out = r.toPlainString();
        return out.equals("-0") ? "0" : out;
    }

    private String subDecimal(String a, String b) {
        BigDecimal x = new BigDecimal(a.trim().replace(',', '.'));
        BigDecimal y = new BigDecimal(b.trim().replace(',', '.'));
        BigDecimal r = x.subtract(y).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros();
        String out = r.toPlainString();
        return out.equals("-0") ? "0" : out;
    }
}
