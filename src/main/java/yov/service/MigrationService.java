package yov.service;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import yov.YOVPlugin;
import yov.cache.VariableCache;
import yov.storage.*;
import yov.storage.impl.SqlStorage;
import yov.storage.impl.SqliteStorage;
import yov.storage.impl.YamlStorage;

import java.io.File;
import java.util.List;
import java.util.logging.Level;

public class MigrationService {

    private final YOVPlugin plugin;

    public MigrationService(YOVPlugin plugin) {
        this.plugin = plugin;
    }

    public void migrate(String fromName, String toName, CommandSender sender) {

        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(YOVPlugin.PREFIX + "§cThis command can only be run from console!");
            return;
        }

        StorageType fromType = StorageType.fromString(fromName);
        StorageType toType = StorageType.fromString(toName);

        if (fromType == toType) {
            sender.sendMessage(YOVPlugin.PREFIX + "§cSource and target storage types are identical!");
            return;
        }

        sender.sendMessage(YOVPlugin.PREFIX + "§eStarting migration from §6" +
                fromType + " §eto §6" + toType + "§e...");

        File dataFolder = plugin.getDataFolder();

        String host = plugin.getConfig().getString("storage.mariadb.host", "localhost");
        int port = plugin.getConfig().getInt("storage.mariadb.port", 3306);
        String dbName = plugin.getConfig().getString("storage.mariadb.database", "yov");
        String user = plugin.getConfig().getString("storage.mariadb.username", "root");
        String pass = plugin.getConfig().getString("storage.mariadb.password", "");

        StorageBackend source = createBackend(fromType, dataFolder, host, port, dbName, user, pass);
        StorageBackend target = createBackend(toType, dataFolder, host, port, dbName, user, pass);

        if (source == null || target == null) {
            sender.sendMessage(YOVPlugin.PREFIX + "§cFailed to create backend instances!");
            return;
        }

        try {

            source.connect();
            target.connect();

            sender.sendMessage(YOVPlugin.PREFIX + "§eCollecting entries from source...");

            var entries = source.getAllEntries();
            int total = entries.size();

            sender.sendMessage(YOVPlugin.PREFIX + "§7Found §e" + total + " §7keys.");

            int migrated = 0;

            for (var e : entries.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (key != null && value != null) {
                    target.set(key, value);
                    migrated++;
                }
            }

            sender.sendMessage(YOVPlugin.PREFIX + "§aMigrated " + migrated + " keys.");

            try {
                sender.sendMessage(YOVPlugin.PREFIX + "§eReloading cache...");

                VariableCache cache = plugin.getVariableCache();
                cache.getMap().clear();
                cache.loadFromDatabase(target);

                sender.sendMessage(YOVPlugin.PREFIX + "§aCache successfully reloaded!");

            } catch (Exception ex) {
                sender.sendMessage(YOVPlugin.PREFIX + "§cMigration done, but cache reload failed!");
                plugin.getLogger().log(Level.SEVERE, "Cache reload failed", ex);
            }

            sender.sendMessage(YOVPlugin.PREFIX + "§aMigration complete!");

        } catch (Exception e) {
            sender.sendMessage(YOVPlugin.PREFIX + "§cMigration failed. See console.");
            plugin.getLogger().log(Level.SEVERE, "Migration error", e);

        } finally {
            try { source.close(); } catch (Exception ignored) {}
            try { target.close(); } catch (Exception ignored) {}
        }
    }

    private StorageBackend createBackend(
            StorageType type,
            File dataFolder,
            String host, int port, String database, String user, String pass
    ) {
        return switch (type) {

            case YAML ->
                    new YamlStorage(dataFolder);

            case SQLITE ->
                    new SqliteStorage(dataFolder);

            case MARIADB ->
                    new SqlStorage("mariadb", host, port, database, user, pass);
        };
    }
}
