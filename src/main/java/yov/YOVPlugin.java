package yov;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import yov.async.WriteQueue;
import yov.cache.VariableCache;
import yov.command.YOVCommand;
import yov.defaults.DefaultVariables;
import yov.listener.PapiExpansionListener;
import yov.listener.PreLoginLoadListener;
import yov.placeholder.RoundedPlaceholder;
import yov.placeholder.VarPlaceholder;
import yov.placeholder.VarPlayerKeyPlaceholder;
import yov.service.MigrationService;
import yov.service.VariableService;
import yov.storage.StorageBackend;
import yov.storage.StorageManager;
import yov.storage.StorageType;
import yov.sync.NoopPlayerVariableSync;
import yov.sync.PlayerVariableSync;

import java.util.Locale;
import java.util.stream.Collectors;

public class YOVPlugin extends JavaPlugin {

    public static final String PREFIX = "§7[§x§9§8§2§D§4§FYOV§7] §r";

    private static YOVPlugin instance;

    private VariableCache variableCache;
    private VariableService variableService;
    private MigrationService migrationService;

    private StorageBackend backend;
    private WriteQueue writeQueue;
    private StorageType storageType;

    private PlayerVariableSync playerSync = new NoopPlayerVariableSync();

    private VarPlaceholder varPlaceholder;
    private VarPlayerKeyPlaceholder varPlayerKeyPlaceholder;
    private RoundedPlaceholder roundedPlaceholder;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        if (!setupServices()) {
            getLogger().severe("Failed to initialize storage. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        registerCommand();
        refreshRuntimeListeners();
    }

    @Override
    public void onDisable() {
        try {
            if (variableService != null) {
                variableService.setShuttingDown(true);
                variableService.flushNow();
            }
        } catch (Exception ignored) {
        }

        try {
            if (writeQueue != null) writeQueue.close();
        } catch (Exception ignored) {
        }

        try {
            if (backend != null) backend.close();
        } catch (Exception ignored) {
        }

        HandlerList.unregisterAll(this);
    }

    public static YOVPlugin getInstance() {
        return instance;
    }

    public VariableService getVariableService() {
        return variableService;
    }

    public VariableCache getVariableCache() {
        return variableCache;
    }

    public String getPluginVersion() {
        return getDescription().getVersion();
    }

    public String getPluginAuthors() {
        return getDescription().getAuthors().stream().collect(Collectors.joining(", "));
    }

    public String getStorageType() {
        return storageType != null ? storageType.name().toLowerCase(Locale.ROOT) : "unknown";
    }

    public void reloadYOV(CommandSender sender) {
        sender.sendMessage(PREFIX + "§eReloading...");

        HandlerList.unregisterAll(this);

        try {
            if (variableService != null) {
                variableService.setShuttingDown(true);
                variableService.flushNow();
            }
        } catch (Exception ignored) {
        }

        try {
            if (writeQueue != null) writeQueue.close();
        } catch (Exception ignored) {
        }

        try {
            if (backend != null) backend.close();
        } catch (Exception ignored) {
        }

        reloadConfig();

        if (!setupServices()) {
            sender.sendMessage(PREFIX + "§cReload failed: storage init error. See console.");
            return;
        }

        registerCommand();
        refreshRuntimeListeners();

        sender.sendMessage(PREFIX + "§aReload complete! Storage: §e" + getStorageType());
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("yov");
        if (cmd == null) return;

        YOVCommand executor = new YOVCommand(this, variableService, variableCache, migrationService);
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    public void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return;

        try {
            if (varPlaceholder != null) varPlaceholder.unregister();
            if (varPlayerKeyPlaceholder != null) varPlayerKeyPlaceholder.unregister();
            if (roundedPlaceholder != null) roundedPlaceholder.unregister();
        } catch (Exception ignored) {
        }

        varPlaceholder = new VarPlaceholder(this, variableService);
        varPlayerKeyPlaceholder = new VarPlayerKeyPlaceholder(this, variableService);
        roundedPlaceholder = new RoundedPlaceholder(this, variableService);

        varPlaceholder.register();
        varPlayerKeyPlaceholder.register();
        roundedPlaceholder.register();
    }

    private void refreshRuntimeListeners() {
        HandlerList.unregisterAll(this);

        PapiExpansionListener papiListener = new PapiExpansionListener(this);
        Bukkit.getPluginManager().registerEvents(papiListener, this);

        Bukkit.getPluginManager().registerEvents(new PreLoginLoadListener(variableService), this);

        new DefaultVariables(this, variableCache, variableService);

        if (storageType == StorageType.HUSKSYNC) {
            try {
                Class<?> storageCls = Class.forName("yov.storage.impl.HuskSyncStorage");
                if (storageCls.isInstance(backend)) {
                    Object listener = Class.forName("yov.husksync.HuskSyncYovListener")
                            .getConstructor(Class.forName("yov.service.VariableService"), storageCls)
                            .newInstance(variableService, backend);
                    Bukkit.getPluginManager().registerEvents((org.bukkit.event.Listener) listener, this);
                }
            } catch (Throwable e) {
                getLogger().severe("Failed to register HuskSync listener: " + e.getMessage());
                e.printStackTrace();
            }
        }

        papiListener.registerAll();
    }

    private boolean setupServices() {
        variableCache = new VariableCache(getLogger());

        storageType = StorageType.fromString(getConfig().getString("storage.type", "sqlite"));

        if (storageType == StorageType.HUSKSYNC && Bukkit.getPluginManager().getPlugin("HuskSync") == null) {
            getLogger().severe("HuskSync storage selected, but HuskSync is not installed");
            return false;
        }

        String host = getConfig().getString("storage.mariadb.host", "localhost");
        int port = getConfig().getInt("storage.mariadb.port", 3306);
        String dbName = getConfig().getString("storage.mariadb.database", "yov");
        String user = getConfig().getString("storage.mariadb.username", "root");
        String pass = getConfig().getString("storage.mariadb.password", "");

        StorageManager storageManager = new StorageManager(getDataFolder(), storageType.name(), host, port, dbName, user, pass);
        backend = storageManager.getBackend();

        try {
            backend.connect();
        } catch (Exception e) {
            getLogger().severe("Failed to connect to storage backend (" + storageType + "): " + e.getMessage());
            e.printStackTrace();
            return false;
        }

        writeQueue = (storageType == StorageType.HUSKSYNC) ? null : new WriteQueue(backend);

        if (storageType != StorageType.HUSKSYNC) {
            variableCache.loadFromDatabase(backend);
            playerSync = new NoopPlayerVariableSync();
        } else {
            try {
                var m = backend.getClass().getMethod("getGlobalEntries");
                Object r = m.invoke(backend);
                if (r instanceof java.util.Map<?, ?> map) {
                    for (var e : map.entrySet()) {
                        Object k = e.getKey();
                        Object v = e.getValue();
                        if (k instanceof String ks && v instanceof String vs) {
                            variableCache.put(ks.toLowerCase(Locale.ROOT), vs);
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                Class<?> cls = Class.forName("yov.husksync.HuskSyncPlayerVariableSync");
                playerSync = (PlayerVariableSync) cls.getConstructor(YOVPlugin.class).newInstance(this);
                if (!playerSync.isEnabled()) {
                    getLogger().severe("HuskSync storage selected, but HuskSync integration could not be initialized.");
                    return false;
                }
            } catch (Throwable e) {
                getLogger().severe("Failed to initialize HuskSync integration: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }

        variableService = new VariableService(storageType, backend, variableCache, getLogger(), PREFIX, writeQueue, playerSync);
        migrationService = new MigrationService(this);

        return true;
    }
}
