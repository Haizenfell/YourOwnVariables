package yov;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import yov.cache.VariableCache;
import yov.command.YOVCommand;
import yov.listener.PapiExpansionListener;
import yov.placeholder.RoundedPlaceholder;
import yov.placeholder.VarPlaceholder;
import yov.placeholder.VarPlayerKeyPlaceholder;
import yov.service.MigrationService;
import yov.service.VariableService;
import yov.storage.StorageBackend;
import yov.storage.StorageManager;

import java.util.logging.Level;

public class YOVPlugin extends JavaPlugin {

    public static final String PREFIX = "§7[§x§9§8§2§D§4§FYOV§7] ";

    private StorageManager storageManager;
    private StorageBackend backend;

    private VariableCache cache;
    private VariableService variableService;
    private MigrationService migrationService;

    private PapiExpansionListener papiListener;

    public VariableCache getVariableCache() {
        return cache;
    }

    public VariableService getVariableService() {
        return variableService;
    }

    public StorageBackend getBackend() {
        return backend;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        papiListener = new PapiExpansionListener(this);
        Bukkit.getPluginManager().registerEvents(papiListener, this);

        if (!initStorageAndServices()) {
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

        getLogger().info("YOV enabled.");
    }

    @Override
    public void onDisable() {
        try {
            if (backend != null) backend.close();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error closing backend", e);
        }
        getLogger().info("YOV disabled.");
    }

    private boolean initStorageAndServices() {
        String type = getConfig().getString("storage.type", "sqlite");
        String host = getConfig().getString("storage.mysql.host", "localhost");
        int port = getConfig().getInt("storage.mysql.port", 3306);
        String dbName = getConfig().getString("storage.mysql.database", "yov");
        String user = getConfig().getString("storage.mysql.username", "root");
        String pass = getConfig().getString("storage.mysql.password", "");

        try {
            storageManager = new StorageManager(getDataFolder(), type, host, port, dbName, user, pass);

            backend = storageManager.getBackend();
            backend.connect();

            if (cache == null) {
                cache = new VariableCache(getLogger());
            } else {
                cache.getMap().clear();
            }
            cache.loadFromDatabase(backend);

            if (variableService == null) {
                variableService = new VariableService(backend, cache, getLogger(), PREFIX);
            } else {
                variableService.setBackend(backend);
            }

            if (migrationService == null)
                migrationService = new MigrationService(this);

            getLogger().info("Connected to " + type.toUpperCase() + " storage successfully.");
            return true;

        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialize storage backend", e);
            return false;
        }
    }

    public void registerPlaceholders() {
        new VarPlaceholder(cache).register();
        new VarPlayerKeyPlaceholder(cache).register();
        new RoundedPlaceholder(cache).register();
    }

    public void reloadYOV(CommandSender sender) {
        sender.sendMessage(PREFIX + "§eReloading config and storage...");

        reloadConfig();

        try {
            if (backend != null) backend.close();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error closing backend during reload", e);
        }

        if (!initStorageAndServices()) {
            sender.sendMessage(PREFIX + "§cReload failed. See console.");
            return;
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            registerPlaceholders();
        }

        sender.sendMessage(PREFIX + "§aReload complete!");
    }
}
