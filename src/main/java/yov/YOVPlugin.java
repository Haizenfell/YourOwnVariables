package yov;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import yov.api.YOVApi;
import yov.api.YOVApiImpl;
import yov.cache.VariableCache;
import yov.command.YOVCommand;
import yov.defaults.DefaultVariables;
import yov.husksync.HuskSyncVariableStore;
import yov.husksync.HuskSyncYovListener;
import yov.listener.PapiExpansionListener;
import yov.placeholder.RoundedPlaceholder;
import yov.placeholder.VarPlayerKeyPlaceholder;
import yov.service.VariableService;

import java.util.Map;
import java.util.stream.Collectors;

public class YOVPlugin extends JavaPlugin {

    public static final String PREFIX = "§7[§x§9§8§2§D§4§FYOV§7] §r";

    private static YOVPlugin instance;

    private VariableCache variableCache;
    private VariableService variableService;
    private HuskSyncVariableStore variableStore;
    private YOVApi api;

    private VarPlayerKeyPlaceholder varPlayerKeyPlaceholder;
    private RoundedPlaceholder roundedPlaceholder;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        if (!setupServices()) {
            getLogger().severe("Failed to initialize YOV. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        registerCommand();
        registerRuntimeListeners();
        registerPlaceholders();
    }

    @Override
    public void onDisable() {
        HandlerList.unregisterAll(this);
        unregisterApi();

        try {
            if (varPlayerKeyPlaceholder != null) varPlayerKeyPlaceholder.unregister();
            if (roundedPlaceholder != null) roundedPlaceholder.unregister();
        } catch (Exception ignored) {
        }

        try {
            if (variableStore != null) variableStore.close();
        } catch (Exception ignored) {
        }
    }

    public static YOVPlugin getInstance() {
        return instance;
    }

    public VariableService getVariableService() {
        return variableService;
    }

    public YOVApi getApi() {
        return api;
    }

    public VariableCache getVariableCache() {
        return variableCache;
    }

    public HuskSyncVariableStore getVariableStore() {
        return variableStore;
    }

    public String getPluginVersion() {
        return getDescription().getVersion();
    }

    public String getPluginAuthors() {
        return getDescription().getAuthors().stream().collect(Collectors.joining(", "));
    }

    public void reloadYOV(CommandSender sender) {
        sender.sendMessage(PREFIX + "§eReloading...");

        HandlerList.unregisterAll(this);
        unregisterApi();
        if (variableStore != null) {
            variableStore.close();
        }
        reloadConfig();

        if (!setupServices()) {
            sender.sendMessage(PREFIX + "§cReload failed. See console.");
            return;
        }

        registerCommand();
        registerRuntimeListeners();
        registerPlaceholders();

        sender.sendMessage(PREFIX + "§aReload complete.");
    }

    public void registerPlaceholders() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().severe("PlaceholderAPI is required but not installed.");
            return;
        }

        try {
            if (varPlayerKeyPlaceholder != null) varPlayerKeyPlaceholder.unregister();
            if (roundedPlaceholder != null) roundedPlaceholder.unregister();
        } catch (Exception ignored) {
        }

        varPlayerKeyPlaceholder = new VarPlayerKeyPlaceholder(this, variableService);
        roundedPlaceholder = new RoundedPlaceholder(this, variableService);

        varPlayerKeyPlaceholder.register();
        roundedPlaceholder.register();
    }

    private boolean setupServices() {
        if (Bukkit.getPluginManager().getPlugin("HuskSync") == null) {
            getLogger().severe("HuskSync is required but not installed.");
            return false;
        }

        variableCache = new VariableCache(getLogger());
        variableStore = new HuskSyncVariableStore(this);

        if (!variableStore.connect()) {
            getLogger().severe("Could not connect to HuskSync API.");
            return false;
        }

        variableService = new VariableService(this, variableCache, variableStore, getLogger(), PREFIX);
        loadOnlinePlayers();
        registerApi();
        return true;
    }

    private void loadOnlinePlayers() {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            variableService.replacePlayerVariables(player.getName(), variableStore.readOnline(player));
        }
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("yov");
        if (cmd == null) return;

        YOVCommand executor = new YOVCommand(this, variableService);
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
        cmd.setAliases(java.util.List.of());

        removeCommandAliases(cmd);
    }

    private void registerRuntimeListeners() {
        PapiExpansionListener papiListener = new PapiExpansionListener(this);
        Bukkit.getPluginManager().registerEvents(papiListener, this);
        Bukkit.getPluginManager().registerEvents(new HuskSyncYovListener(variableService, variableStore), this);
        Bukkit.getPluginManager().registerEvents(new DefaultVariables(this, variableService), this);
    }

    private void registerApi() {
        unregisterApi();
        api = new YOVApiImpl(this);
        Bukkit.getServicesManager().register(YOVApi.class, api, this, ServicePriority.Normal);
    }

    private void unregisterApi() {
        if (api == null) {
            return;
        }
        Bukkit.getServicesManager().unregister(YOVApi.class, api);
        api = null;
    }

    private void removeCommandAliases(PluginCommand command) {
        java.util.List<String> aliases = findCommandAliases(Bukkit.getCommandMap().getKnownCommands(), command);
        if (aliases.isEmpty()) {
            return;
        }

        if (!removeCommandMappings(Bukkit.getCommandMap().getKnownCommands(), aliases)) {
            Map<String, Command> mutableCommands = getMutableKnownCommands();
            if (mutableCommands == null || !removeCommandMappings(mutableCommands, aliases)) {
                getLogger().warning("Could not remove command aliases. /yov will work, but namespaced command entries may remain.");
                return;
            }
        }

        syncCommands();
    }

    private java.util.List<String> findCommandAliases(Map<String, Command> knownCommands, PluginCommand command) {
        java.util.List<String> aliases = new java.util.ArrayList<>();
        knownCommands.forEach((key, value) -> {
            if (value == command && !key.equals("yov")) {
                aliases.add(key);
            }
        });
        return aliases;
    }

    private boolean removeCommandMappings(Map<String, Command> knownCommands, java.util.List<String> keys) {
        try {
            for (String key : keys) {
                knownCommands.remove(key);
            }
            return true;
        } catch (UnsupportedOperationException ignored) {
            return false;
        } catch (RuntimeException e) {
            getLogger().warning("Failed to remove command aliases: " + e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Command> getMutableKnownCommands() {
        Class<?> type = Bukkit.getCommandMap().getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("knownCommands");
                field.setAccessible(true);
                Object value = field.get(Bukkit.getCommandMap());
                return value instanceof Map<?, ?> ? (Map<String, Command>) value : null;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                getLogger().warning("Failed to access Paper command map: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    private void syncCommands() {
        try {
            Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
