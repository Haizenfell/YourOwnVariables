package yov.storage.impl;

import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.Identifier;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import yov.YOVPlugin;
import yov.husksync.HuskSyncDataAccess;
import yov.husksync.YovVariablesData;
import yov.husksync.YovVariablesSerializer;
import yov.storage.StorageBackend;

import java.io.File;
import java.sql.Connection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

public class HuskSyncStorage implements StorageBackend {

    private static final class ParsedKey {
        private final String playerLower;
        private final String var;

        private ParsedKey(String playerLower, String var) {
            this.playerLower = playerLower;
            this.var = var;
        }
    }

    private final YOVPlugin plugin;
    private final File dataFolder;

    private BukkitHuskSync huskSync;
    private BukkitHuskSyncAPI api;
    private Identifier id;

    private final ExecutorService offlineExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "YOV-HuskSync-Offlines");
        t.setDaemon(true);
        return t;
    });

    private final Map<String, String> global = new ConcurrentHashMap<>();
    private File globalFile;

    private volatile boolean globalDirty;
    private int globalSaveTaskId = -1;

    public HuskSyncStorage(File dataFolder) {
        this.dataFolder = dataFolder;
        this.plugin = YOVPlugin.getInstance();
    }

    public Identifier getIdentifier() {
        return id;
    }

    public Map<String, String> getGlobalEntries() {
        return new HashMap<>(global);
    }

    public void markPlayerLoaded(String playerLower, boolean loaded) {
    }

    public void replacePlayerVariables(String playerLower, Map<String, String> vars) {
        if (playerLower == null) return;
        String lower = playerLower.toLowerCase(Locale.ROOT);
        Map<String, String> safe = vars == null ? Map.of() : new HashMap<>(vars);

        offlineExecutor.execute(() -> {
            try {
                if (plugin.getVariableService() != null && plugin.getVariableService().isPlayerLoaded(lower)) {
                    return;
                }
                editLatestSnapshot(lower, m -> {
                    m.clear();
                    m.putAll(safe);
                });
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void connect() throws Exception {
        Plugin pl = Bukkit.getPluginManager().getPlugin("HuskSync");
        if (!(pl instanceof BukkitHuskSync hs)) {
            throw new IllegalStateException("HuskSync storage selected, but HuskSync is not installed");
        }

        this.huskSync = hs;
        this.api = BukkitHuskSyncAPI.getInstance();
        this.id = Identifier.from("yov", "variables");

        try {
            api.registerDataSerializer(id, new YovVariablesSerializer(huskSync));
        } catch (Exception ignored) {
        }

        globalFile = new File(dataFolder, "husksync_global.yml");
        if (!globalFile.exists()) {
            dataFolder.mkdirs();
            globalFile.createNewFile();
        }

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(globalFile);
        var sec = yml.getConfigurationSection("global");
        if (sec != null) {
            for (String k : sec.getKeys(false)) {
                String v = sec.getString(k);
                if (v != null) global.put(k.toLowerCase(Locale.ROOT), v);
            }
        }

        startGlobalSaver();
    }

    @Override
    public void set(String key, String value) {
        if (key == null) return;
        String k = key.toLowerCase(Locale.ROOT);

        if (k.indexOf('_') <= 0) {
            global.put(k, value);
            markGlobalDirty();
            return;
        }

        ParsedKey online = resolveOnlineKey(k);
        if (online != null) {
            if (plugin.getVariableService() != null && plugin.getVariableService().isPlayerLoaded(online.playerLower)) {
                return;
            }
            writeOfflineExact(online.playerLower, vars -> vars.put(online.var, value));
            return;
        }

        writeByKey(k, (vars, var) -> vars.put(var, value), () -> {
            global.put(k, value);
            markGlobalDirty();
        });
    }

    @Override
    public String get(String key) throws Exception {
        if (key == null) return null;
        String k = key.toLowerCase(Locale.ROOT);

        if (k.indexOf('_') <= 0) {
            return global.get(k);
        }

        ParsedKey parsed = resolveByApi(k, 2000);
        if (parsed == null) {
            return global.get(k);
        }

        Optional<net.william278.husksync.user.User> user = api.getUser(parsed.playerLower)
                .completeOnTimeout(Optional.empty(), 2, TimeUnit.SECONDS)
                .join();
        if (user.isEmpty()) return null;

        Optional<DataSnapshot.Unpacked> snapOpt = api.getCurrentData(user.get())
                .completeOnTimeout(Optional.empty(), 2, TimeUnit.SECONDS)
                .join();
        if (snapOpt.isEmpty()) return null;

        YovVariablesData data = HuskSyncDataAccess.readYovData(api, snapOpt.get(), id);
        if (data == null) return null;

        return data.getVariables().get(parsed.var);
    }

    @Override
    public void delete(String key) {
        if (key == null) return;
        String k = key.toLowerCase(Locale.ROOT);

        if (k.indexOf('_') <= 0) {
            global.remove(k);
            markGlobalDirty();
            return;
        }

        ParsedKey online = resolveOnlineKey(k);
        if (online != null) {
            if (plugin.getVariableService() != null && plugin.getVariableService().isPlayerLoaded(online.playerLower)) {
                return;
            }
            writeOfflineExact(online.playerLower, vars -> vars.remove(online.var));
            return;
        }

        writeByKey(k, (vars, var) -> vars.remove(var), () -> {
            global.remove(k);
            markGlobalDirty();
        });
    }

    @Override
    public java.util.List<String> getAllKeys() {
        throw new UnsupportedOperationException("HuskSync backend does not support getAllKeys()");
    }

    @Override
    public Map<String, String> getAllEntries() {
        throw new UnsupportedOperationException("HuskSync backend does not support getAllEntries()");
    }

    @Override
    public Map<String, String> getEntriesByPrefix(String prefix) throws Exception {
        if (prefix == null) return Collections.emptyMap();
        String p = prefix.toLowerCase(Locale.ROOT);
        if (!p.endsWith("_")) return Collections.emptyMap();

        String playerLower = p.substring(0, p.length() - 1);

        Optional<net.william278.husksync.user.User> user = api.getUser(playerLower)
                .completeOnTimeout(Optional.empty(), 2, TimeUnit.SECONDS)
                .join();
        if (user.isEmpty()) return Collections.emptyMap();

        Optional<DataSnapshot.Unpacked> snapOpt = api.getCurrentData(user.get())
                .completeOnTimeout(Optional.empty(), 2, TimeUnit.SECONDS)
                .join();
        if (snapOpt.isEmpty()) return Collections.emptyMap();

        YovVariablesData data = HuskSyncDataAccess.readYovData(api, snapOpt.get(), id);
        if (data == null) return Collections.emptyMap();

        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : data.getVariables().entrySet()) {
            String kk = e.getKey();
            String vv = e.getValue();
            if (kk == null || kk.isEmpty()) continue;
            out.put(playerLower + "_" + kk.toLowerCase(Locale.ROOT), vv);
        }
        return out;
    }

    @Override
    public Connection getConnection() {
        return null;
    }

    @Override
    public void close() {
        try {
            if (globalSaveTaskId != -1) Bukkit.getScheduler().cancelTask(globalSaveTaskId);
        } catch (Exception ignored) {
        }

        offlineExecutor.shutdown();
        try {
            offlineExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            offlineExecutor.shutdownNow();
        }

        if (globalDirty) {
            globalDirty = false;
            saveGlobal();
        }
    }

    private void writeByKey(String fullKeyLower, BiConsumer<Map<String, String>, String> mutator, Runnable globalFallback) {
        offlineExecutor.execute(() -> {
            try {
                ParsedKey parsed = resolveByApi(fullKeyLower, 2000);
                if (parsed == null) {
                    globalFallback.run();
                    return;
                }

                if (plugin.getVariableService() != null && plugin.getVariableService().isPlayerLoaded(parsed.playerLower)) {
                    return;
                }

                editLatestSnapshot(parsed.playerLower, vars -> mutator.accept(vars, parsed.var));

            } catch (Exception ignored) {
            }
        });
    }

    private void writeOfflineExact(String playerLower, java.util.function.Consumer<Map<String, String>> mutator) {
        offlineExecutor.execute(() -> {
            try {
                editLatestSnapshot(playerLower, mutator);
            } catch (Exception ignored) {
            }
        });
    }

    private void editLatestSnapshot(String playerLower, java.util.function.Consumer<Map<String, String>> mutator) {
        Optional<net.william278.husksync.user.User> user = api.getUser(playerLower)
                .completeOnTimeout(Optional.empty(), 2, TimeUnit.SECONDS)
                .join();
        if (user.isEmpty()) return;

        api.editLatestSnapshot(user.get(), unpacked -> {
            YovVariablesData d = HuskSyncDataAccess.readYovData(api, unpacked, id);
            Map<String, String> vars = d != null ? new HashMap<>(d.getVariables()) : new HashMap<>();
            mutator.accept(vars);
            YovVariablesData out = d != null ? d : new YovVariablesData();
            out.setVariables(vars);
            HuskSyncDataAccess.writeYovData(api, unpacked, id, out);
        });
    }

    private ParsedKey resolveOnlineKey(String fullKeyLower) {
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
        return new ParsedKey(bestLower, var);
    }

    private ParsedKey resolveByApi(String fullKeyLower, long timeoutMillis) {
        if (fullKeyLower == null || fullKeyLower.isEmpty()) return null;
        int len = fullKeyLower.length();

        for (int i = len - 1; i >= 1; i--) {
            if (fullKeyLower.charAt(i) != '_') continue;
            if (i >= len - 1) continue;

            String candidate = fullKeyLower.substring(0, i);
            String var = fullKeyLower.substring(i + 1);
            if (var.isEmpty()) continue;

            if (isProbablyPlayerName(candidate)) {
                return new ParsedKey(candidate, var.toLowerCase(Locale.ROOT));
            }

            Optional<net.william278.husksync.user.User> user = api.getUser(candidate)
                    .completeOnTimeout(Optional.empty(), timeoutMillis, TimeUnit.MILLISECONDS)
                    .join();
            if (user.isPresent()) {
                return new ParsedKey(candidate, var.toLowerCase(Locale.ROOT));
            }
        }

        return null;
    }

    private void saveGlobal() {
        try {
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(globalFile);
            yml.set("global", null);
            for (Map.Entry<String, String> e : global.entrySet()) {
                yml.set("global." + e.getKey(), e.getValue());
            }
            yml.save(globalFile);
        } catch (Exception ignored) {
        }
    }

    private void startGlobalSaver() {
        long interval = 200L;
        try {
            interval = plugin.getConfig().getLong("storage.husksync.global-save-interval-ticks", 200L);
            if (interval < 20L) interval = 20L;
        } catch (Exception ignored) {
        }

        globalSaveTaskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!globalDirty) return;
            globalDirty = false;
            saveGlobal();
        }, interval, interval).getTaskId();
    }

    private void markGlobalDirty() {
        globalDirty = true;
    }

    private boolean isProbablyPlayerName(String name) {
        if (name == null || name.isEmpty()) return false;

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return true;
        }

        try {
            var m = Bukkit.class.getMethod("getOfflinePlayerIfCached", String.class);
            Object r = m.invoke(null, name);
            if (r instanceof OfflinePlayer op) {
                return op.hasPlayedBefore() || op.isOnline();
            }
        } catch (Throwable ignored) {
        }

        return false;
    }
}
