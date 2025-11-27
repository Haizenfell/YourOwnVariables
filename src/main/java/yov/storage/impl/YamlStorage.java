/*
 * This file is part of YourOwnVariables.
 * Copyright (C) 2025 Haizenfell
 *
 * Licensed under the YourOwnVariables Proprietary License.
 * Unauthorized copying, modification, distribution, or reverse engineering
 * of this software is strictly prohibited.
 *
 * Full license text is provided in the LICENSE file.
 */
package yov.storage.impl;

import org.bukkit.configuration.file.YamlConfiguration;
import yov.storage.StorageBackend;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
            file.getParentFile().mkdirs();
            file.createNewFile();
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
                e.printStackTrace();
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
        return key.contains("_"); // player_variable
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
            if (yaml.contains("global")) {
                keys.addAll(yaml.getConfigurationSection("global").getKeys(false));
            }

            if (yaml.contains("players")) {
                for (String player : yaml.getConfigurationSection("players").getKeys(false)) {
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
    public void close() throws Exception {
        if (dirty.get()) {
            saveSync();
        }
        scheduler.shutdownNow();
    }
}
