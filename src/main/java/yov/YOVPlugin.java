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
package yov;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import yov.async.WriteQueue;
import yov.cache.VariableCache;
import yov.command.YOVCommand;
import yov.defaults.DefaultVariables;
import yov.listener.PapiExpansionListener;
import yov.placeholder.RoundedPlaceholder;
import yov.placeholder.VarPlaceholder;
import yov.placeholder.VarPlayerKeyPlaceholder;
import yov.service.MigrationService;
import yov.service.VariableService;
import yov.storage.StorageBackend;
import yov.storage.StorageManager;

import java.util.List;
import java.util.logging.Level;

public class YOVPlugin extends JavaPlugin {

    public static final String PREFIX = "§7[§x§9§8§2§D§4§FYOV§7] ";

    private StorageManager storageManager;
    private StorageBackend backend;

    private VariableCache cache;
    private VariableService variableService;
    private MigrationService migrationService;

    private PapiExpansionListener papiListener;
    private WriteQueue writeQueue;

    public VariableCache getVariableCache() {
        return cache;
    }

    public VariableService getVariableService() {
        return variableService;
    }

    public String getStorageType() {
        if (backend == null) return "UNKNOWN";

        return backend.getClass().getSimpleName()
                .replace("Storage", "")
                .toUpperCase();
    }

    public @NotNull String getPluginVersion() {
        return getDescription().getVersion();
    }

    public @NotNull String getPluginName() {
        return getDescription().getName();
    }

    public @NotNull String getPluginAuthors() {
        List<String> authors = getDescription().getAuthors();
        if (authors == null || authors.isEmpty()) {
            return "Unknown";
        }
        return String.join(", ", authors);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        papiListener = new PapiExpansionListener(this);
        Bukkit.getPluginManager().registerEvents(papiListener, this);

        if (!setupServices()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        PluginCommand cmd = getCommand("yov");
        if (cmd != null) {
            YOVCommand executor = new YOVCommand(this, variableService, cache, migrationService);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            registerPlaceholders();
            getLogger().info("PlaceholderAPI hook enabled.");
        }

        new DefaultVariables(this, cache, variableService);

        getLogger().info("YOV enabled.");
    }

    @Override
    public void onDisable() {

        if (variableService != null) {
            variableService.setShuttingDown(true);
        }

        try {
            if (variableService != null && variableService.getWriteQueue() != null) {
                variableService.getWriteQueue().close();
            }
        } catch (Exception ignored) {}

        try {
            if (backend != null) backend.close();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error closing backend", e);
        }
        getLogger().info("YOV disabled.");
    }

    public void registerPlaceholders() {
        if (variableService == null) {
            getLogger().warning("Tried to register placeholders before VariableService was initialized.");
            return;
        }

        new VarPlaceholder(this, variableService).register();
        new VarPlayerKeyPlaceholder(this, variableService).register();
        new RoundedPlaceholder(this, variableService).register();
    }

    private boolean setupServices() {

        if (writeQueue != null) {
            try {
                writeQueue.close();
            } catch (Exception ignored) {}
            writeQueue = null;
        }

        if (backend != null) {
            try {
                backend.close();
            } catch (Exception ignored) {}
            backend = null;
        }

        String type = getConfig().getString("storage.type", "sqlite");
        String host = getConfig().getString("storage.mariadb.host", "localhost");
        int port = getConfig().getInt("storage.mariadb.port", 3306);
        String dbName = getConfig().getString("storage.mariadb.database", "yov");
        String user = getConfig().getString("storage.mariadb.username", "root");
        String pass = getConfig().getString("storage.mariadb.password", "");

        try {
            storageManager = new StorageManager(getDataFolder(), type, host, port, dbName, user, pass);
            backend = storageManager.getBackend();
            backend.connect();

            if (cache == null) cache = new VariableCache(getLogger());
            else cache.getMap().clear();

            cache.loadFromDatabase(backend);

            writeQueue = new WriteQueue(backend);

            variableService = new VariableService(
                    backend,
                    cache,
                    getLogger(),
                    PREFIX,
                    writeQueue
            );

            if (migrationService == null)
                migrationService = new MigrationService(this);

            getLogger().info("Storage initialized successfully using " + type.toUpperCase());
            return true;

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize storage", e);
            return false;
        }
    }

    public void reloadYOV(CommandSender sender) {

        sender.sendMessage(PREFIX + "§eReloading config and storage...");

        reloadConfig();

        if (!setupServices()) {
            sender.sendMessage(PREFIX + "§cReload failed. Check console.");
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            registerPlaceholders();
        }

        PluginCommand cmd = getCommand("yov");
        if (cmd != null) {
            YOVCommand executor = new YOVCommand(this, variableService, cache, migrationService);
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }

        sender.sendMessage(PREFIX + "§aReload complete!");
    }
}
