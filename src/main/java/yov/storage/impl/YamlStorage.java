package yov.storage.impl;

import org.bukkit.configuration.file.YamlConfiguration;
import yov.storage.StorageBackend;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class YamlStorage implements StorageBackend {

    private final File file;
    private YamlConfiguration yaml;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "YOV-YamlSaver");
                t.setDaemon(true);
                return t;
            });

    private final Object lock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean saveScheduled = new AtomicBoolean(false);

    private static final long SAVE_DELAY_MS = 2000L;

    public YamlStorage(File dataFolder) {
        this.file = new File(dataFolder, "variables.yml");
    }

    @Override
    public void connect() throws Exception {
        if (!file.exists()) {
            File parent = file.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IOException("Failed to create directory: " + parent.getAbsolutePath());
            }

            if (!file.exists() && !file.createNewFile()) {
                throw new IOException("Failed to create file: " + file.getAbsolutePath());
            }
        }

        yaml = YamlConfiguration.loadConfiguration(file);

        if (!yaml.contains("global")) yaml.createSection("global");
        if (!yaml.contains("players")) yaml.createSection("players");

        saveSync();
    }

    private void markDirty() {
        dirty.set(true);
        if (saveScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::saveIfDirty, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void saveIfDirty() {
        synchronized (lock) {
            if (!dirty.get()) {
                saveScheduled.set(false);
                return;
            }
            try {
                yaml.save(file);
            } catch (IOException e) {
                Logger.getLogger("YOV").log(Level.SEVERE, "Failed to save YAML file " + file.getName(), e);
            } finally {
                dirty.set(false);
                saveScheduled.set(false);
            }
        }
    }

    private void saveSync() throws IOException {
        synchronized (lock) {
            yaml.save(file);
            dirty.set(false);
            saveScheduled.set(false);
        }
    }

    private boolean isPlayerKey(String key) {
        return key.contains("_");
    }

    private String getPlayerFromKey(String key) {
        return key.substring(0, key.indexOf("_"));
    }

    private String getVariableFromKey(String key) {
        return key.substring(key.indexOf("_") + 1);
    }

    @Override
    public void set(String key, String value) throws Exception {
        synchronized (lock) {
            if (isPlayerKey(key)) {
                String player = getPlayerFromKey(key);
                String var = getVariableFromKey(key);
                yaml.set("players." + player + "." + var, value);
            } else {
                yaml.set("global." + key, value);
            }
        }
        markDirty();
    }

    @Override
    public String get(String key) throws Exception {
        synchronized (lock) {
            if (isPlayerKey(key)) {
                String player = getPlayerFromKey(key);
                String var = getVariableFromKey(key);
                return yaml.getString("players." + player + "." + var);
            } else {
                return yaml.getString("global." + key);
            }
        }
    }
    @Override
    public java.util.Map<String, String> getAllEntries() {
        java.util.Map<String, String> out = new java.util.HashMap<>();

        synchronized (lock) {
            var globalSec = yaml.getConfigurationSection("global");
            if (globalSec != null) {
                for (String k : globalSec.getKeys(false)) {
                    String v = yaml.getString("global." + k);
                    if (k != null && v != null) out.put(k, v);
                }
            }

            var playersSec = yaml.getConfigurationSection("players");
            if (playersSec != null) {
                for (String player : playersSec.getKeys(false)) {
                    var sec = yaml.getConfigurationSection("players." + player);
                    if (sec == null) continue;

                    for (String var : sec.getKeys(false)) {
                        String v = yaml.getString("players." + player + "." + var);
                        String k = player + "_" + var;
                        if (v != null) out.put(k, v);
                    }
                }
            }
        }

        return out;
    }

    @Override
    public java.util.Map<String, String> getEntriesByPrefix(String prefix) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (prefix == null) return out;

        synchronized (lock) {
            if (prefix.endsWith("_")) {
                String player = prefix.substring(0, prefix.length() - 1);
                var sec = yaml.getConfigurationSection("players." + player);
                if (sec != null) {
                    for (String var : sec.getKeys(false)) {
                        String v = yaml.getString("players." + player + "." + var);
                        if (v != null) out.put(player + "_" + var, v);
                    }
                }
                return out;
            }

            var globalSec = yaml.getConfigurationSection("global");
            if (globalSec != null) {
                for (String k : globalSec.getKeys(false)) {
                    if (!k.startsWith(prefix)) continue;
                    String v = yaml.getString("global." + k);
                    if (v != null) out.put(k, v);
                }
            }

            var playersSec = yaml.getConfigurationSection("players");
            if (playersSec != null) {
                for (String player : playersSec.getKeys(false)) {
                    String fullPrefix = player + "_";
                    if (!(prefix.startsWith(fullPrefix) || fullPrefix.startsWith(prefix))) continue;

                    var sec = yaml.getConfigurationSection("players." + player);
                    if (sec == null) continue;

                    for (String var : sec.getKeys(false)) {
                        String k = player + "_" + var;
                        if (!k.startsWith(prefix)) continue;
                        String v = yaml.getString("players." + player + "." + var);
                        if (v != null) out.put(k, v);
                    }
                }
            }
        }

        return out;
    }

    @Override
    public void delete(String key) throws Exception {
        synchronized (lock) {
            if (isPlayerKey(key)) {
                String player = getPlayerFromKey(key);
                String var = getVariableFromKey(key);

                yaml.set("players." + player + "." + var, null);

                String secPath = "players." + player;
                if (yaml.getConfigurationSection(secPath) != null &&
                        yaml.getConfigurationSection(secPath).getKeys(false).isEmpty()) {
                    yaml.set(secPath, null);
                }

            } else {
                yaml.set("global." + key, null);
            }
        }
        markDirty();
    }

    @Override
    public List<String> getAllKeys() {
        List<String> keys = new ArrayList<>();

        synchronized (lock) {
            var globalSec = yaml.getConfigurationSection("global");
            if (globalSec != null) {
                keys.addAll(globalSec.getKeys(false));
            }

            var playersSec = yaml.getConfigurationSection("players");
            if (playersSec != null) {
                for (String player : playersSec.getKeys(false)) {
                    var sec = yaml.getConfigurationSection("players." + player);
                    if (sec == null) continue;

                    for (String var : sec.getKeys(false)) {
                        keys.add(player + "_" + var);
                    }
                }
            }
        }

        return keys;
    }

    @Override
    public Connection getConnection() {
        return null;
    }

    @Override
    public void close() {
        try {
            if (dirty.get()) {
                saveSync();
            }
        } catch (IOException e) {
            Logger.getLogger("YOV").log(Level.SEVERE, "Failed to save YAML during close()", e);
        }

        scheduler.shutdownNow();
    }
}
