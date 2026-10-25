package yov;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import yov.api.YOVApi;
import yov.api.YOVApiImpl;
import yov.command.YOVCommand;
import yov.config.VariableLimits;
import yov.defaults.DefaultVariables;
import yov.husksync.HuskSyncVariableStore;
import yov.husksync.HuskSyncYovListener;
import yov.listener.PapiExpansionListener;
import yov.listener.PlayerPreloadListener;
import yov.metrics.YOVMetrics;
import yov.placeholder.RoundedPlaceholder;
import yov.placeholder.VarPlayerKeyPlaceholder;
import yov.service.VariableService;

import java.io.File;

public class YOVPlugin extends JavaPlugin {

    public static final String PREFIX = "§7[§x§9§8§2§D§4§FYOV§7] §r";

    private static YOVPlugin instance;

    private volatile VariableService variableService;
    private volatile HuskSyncVariableStore variableStore;
    private volatile YOVApi api;
    private volatile VariableLimits variableLimits;
    private volatile YOVMetrics metrics;
    private volatile boolean reloadInProgress;
    private volatile RuntimeServices pendingReload;

    private VarPlayerKeyPlaceholder varPlayerKeyPlaceholder;
    private RoundedPlaceholder roundedPlaceholder;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        if (!loadConfigStrictly()) {
            getLogger().severe("Invalid config.yml. Disabling YOV instead of starting with fallback values.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        RuntimeServices runtime = createServices();
        if (runtime == null) {
            getLogger().severe("Failed to initialize YOV. Disabling plugin.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        try {
            primeOnlinePlayers(runtime);
        } catch (RuntimeException error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not prepare online YOV player snapshots. Disabling YOV.", error);
            runtime.service().shutdown();
            runtime.store().close();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        if (!installServices(runtime)) {
            getLogger().severe("Failed to activate YOV cross-server editing. Disabling plugin.");
            runtime.service().shutdown();
            runtime.store().close();
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        runtime.service().flushDefaultBackfills();
        registerCommand();
        registerRuntimeListeners();
        continueOnlinePlayerLoading(runtime);
        if (!registerPlaceholdersChecked()) {
            getLogger().severe("Failed to register required PlaceholderAPI expansions. Disabling YOV.");
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        reloadInProgress = false;
        RuntimeServices pending = pendingReload;
        pendingReload = null;
        if (pending != null) {
            pending.service().shutdown();
            pending.store().close();
        }
        HandlerList.unregisterAll(this);
        if (api instanceof YOVApiImpl implementation) implementation.shutdown();
        if (variableService != null) variableService.shutdown();
        Bukkit.getScheduler().cancelTasks(this);
        unregisterApi();
        unregisterPlaceholders();
        if (variableStore != null) variableStore.close();
        variableService = null;
        variableStore = null;
        variableLimits = null;
        metrics = null;
        instance = null;
    }

    public static YOVPlugin getInstance() {
        return instance;
    }

    public YOVApi getApi() {
        return api;
    }

    public String getPluginVersion() {
        return getPluginMeta().getVersion();
    }

    public String getPluginAuthors() {
        return String.join(", ", getPluginMeta().getAuthors());
    }

    public VariableLimits getVariableLimits() {
        return variableLimits;
    }

    public YOVMetrics getMetrics() {
        return metrics;
    }

    public void reloadYOV(CommandSender sender) {
        if (reloadInProgress) {
            sender.sendMessage(PREFIX + "§eA reload is already waiting for HuskSync writes.");
            return;
        }
        reloadInProgress = true;
        sender.sendMessage(PREFIX + "§eReloading...");

        String previousConfig = getConfig().saveToString();
        if (!loadConfigStrictly()) {
            reloadInProgress = false;
            sender.sendMessage(PREFIX + "§cReload aborted: config.yml is invalid or unreadable.");
            return;
        }

        RuntimeServices replacement = createServices();
        if (replacement == null) {
            restoreReloadConfig(previousConfig);
            reloadInProgress = false;
            sender.sendMessage(PREFIX + "§cReload failed. See console.");
            return;
        }
        pendingReload = replacement;

        VariableService previousService = variableService;
        HuskSyncVariableStore previousStore = variableStore;
        long waitMillis = Math.max(0L, getConfig()
                .getLong("husksync.shutdown-wait-millis", 5000L));
        java.util.concurrent.CompletableFuture<Boolean> serviceDrain = previousService == null
                ? java.util.concurrent.CompletableFuture.completedFuture(true)
                : previousService.prepareCloseForReload(waitMillis);
        java.util.concurrent.CompletableFuture<Boolean> storeDrain = previousStore == null
                ? java.util.concurrent.CompletableFuture.completedFuture(true)
                : previousStore.prepareCloseForReload();

        serviceDrain.thenCombine(storeDrain, (serviceReady, storeReady) ->
                Boolean.TRUE.equals(serviceReady) && Boolean.TRUE.equals(storeReady))
                .whenComplete((drained, error) -> {
            Runnable completion = () -> finishReload(
                    sender, previousService, previousStore, replacement,
                    previousConfig, Boolean.TRUE.equals(drained), error);
            if (Bukkit.isPrimaryThread()) {
                completion.run();
                return;
            }
            try {
                Bukkit.getScheduler().runTask(this, completion);
            } catch (RuntimeException schedulingError) {
                if (previousService != null) previousService.abortCloseForReload();
                if (previousStore != null) previousStore.abortCloseForReload();
                if (pendingReload == replacement) pendingReload = null;
                replacement.service().shutdown();
                replacement.store().close();
                restoreReloadConfig(previousConfig);
                reloadInProgress = false;
                getLogger().warning("Could not finish YOV reload on the main thread: "
                        + schedulingError.getMessage());
            }
        });
    }

    private void finishReload(CommandSender sender,
                              VariableService previousService,
                              HuskSyncVariableStore previousStore,
                              RuntimeServices replacement,
                              String previousConfig,
                              boolean drained,
                              Throwable error) {
        if (!isEnabled() || pendingReload != replacement) {
            if (previousService != null) previousService.abortCloseForReload();
            if (previousStore != null) previousStore.abortCloseForReload();
            if (pendingReload == replacement) pendingReload = null;
            replacement.service().shutdown();
            replacement.store().close();
            restoreReloadConfig(previousConfig);
            reloadInProgress = false;
            return;
        }

        boolean runtimeUnchanged = variableService == previousService && variableStore == previousStore;
        boolean serviceReady = previousService == null || previousService.finishCloseForReload();
        if (error != null || !drained || !runtimeUnchanged || !serviceReady) {
            if (previousService != null) previousService.abortCloseForReload();
            if (previousStore != null) previousStore.abortCloseForReload();
            pendingReload = null;
            replacement.service().shutdown();
            replacement.store().close();
            restoreReloadConfig(previousConfig);
            reloadInProgress = false;
            if (error != null) {
                getLogger().warning("Failed while waiting to reload YOV: " + error.getMessage());
            }
            sender.sendMessage(PREFIX
                    + "§cReload aborted: pending HuskSync writes did not finish in time.");
            return;
        }

        try {
            primeOnlinePlayers(replacement);
        } catch (RuntimeException preparationError) {
            if (previousService != null) previousService.abortCloseForReload();
            if (previousStore != null) previousStore.abortCloseForReload();
            pendingReload = null;
            replacement.service().shutdown();
            replacement.store().close();
            restoreReloadConfig(previousConfig);
            reloadInProgress = false;
            getLogger().log(java.util.logging.Level.WARNING,
                    "Could not prepare replacement YOV snapshots.", preparationError);
            sender.sendMessage(PREFIX + "§cReload aborted while preparing player snapshots.");
            return;
        }

        boolean receiptsTransferred = previousStore == null
                || previousStore.transferReloadStateTo(replacement.store());
        if (!receiptsTransferred
                || (previousStore != null && !previousStore.finishCloseForReload())) {
            if (previousService != null) previousService.abortCloseForReload();
            if (previousStore != null) previousStore.abortCloseForReload();
            pendingReload = null;
            replacement.service().shutdown();
            replacement.store().close();
            restoreReloadConfig(previousConfig);
            reloadInProgress = false;
            sender.sendMessage(PREFIX
                    + "§cReload aborted: the cross-server request ledger changed during reload.");
            return;
        }

        HandlerList.unregisterAll(this);
        unregisterPlaceholders();

        if (!installServices(replacement)) {
            pendingReload = null;
            replacement.service().shutdown();
            replacement.store().close();
            reloadInProgress = false;
            sender.sendMessage(PREFIX + "§cReload failed while activating cross-server editing.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        replacement.service().flushDefaultBackfills();
        if (previousService != null) previousService.shutdownForReload();
        pendingReload = null;

        registerCommand();
        registerRuntimeListeners();
        continueOnlinePlayerLoading(replacement);
        if (!registerPlaceholdersChecked()) {
            reloadInProgress = false;
            sender.sendMessage(PREFIX
                    + "§cReload failed: required PlaceholderAPI expansions could not be registered.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        reloadInProgress = false;
        sender.sendMessage(PREFIX + "§aReload complete.");
    }

    private void restoreReloadConfig(String snapshot) {
        try {
            getConfig().loadFromString(snapshot);
        } catch (InvalidConfigurationException error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not restore the previous YOV configuration after an aborted reload. "
                            + "Disabling YOV to avoid a mixed runtime.", error);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private boolean loadConfigStrictly() {
        File configFile = new File(getDataFolder(), "config.yml");
        YamlConfiguration parsed = new YamlConfiguration();
        try {
            parsed.load(configFile);
            getConfig().loadFromString(parsed.saveToString());
            return true;
        } catch (Exception error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not parse config.yml; the current runtime was left unchanged.", error);
            return false;
        }
    }

    public void registerPlaceholders() {
        if (registerPlaceholdersChecked()) return;
        getLogger().severe("Failed to register required PlaceholderAPI expansions. Disabling YOV.");
        Bukkit.getPluginManager().disablePlugin(this);
    }

    private boolean registerPlaceholdersChecked() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().severe("PlaceholderAPI is required but not installed.");
            return false;
        }

        unregisterPlaceholders();

        varPlayerKeyPlaceholder = new VarPlayerKeyPlaceholder(this, variableService);
        roundedPlaceholder = new RoundedPlaceholder(this, variableService);

        try {
            boolean variablesRegistered = varPlayerKeyPlaceholder.register();
            boolean roundedRegistered = roundedPlaceholder.register();
            if (variablesRegistered && roundedRegistered) return true;
            if (!variablesRegistered) {
                getLogger().severe("Could not register the %yov_player_key:<variable>% expansion.");
            }
            if (!roundedRegistered) {
                getLogger().severe("Could not register the %rounded_player_key...% expansion.");
            }
        } catch (RuntimeException error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "PlaceholderAPI rejected a YOV expansion.", error);
        }
        unregisterPlaceholders();
        return false;
    }

    private RuntimeServices createServices() {
        if (Bukkit.getPluginManager().getPlugin("HuskSync") == null) {
            getLogger().severe("HuskSync is required but not installed.");
            return null;
        }

        VariableLimits newLimits = VariableLimits.from(getConfig());
        YOVMetrics newMetrics = new YOVMetrics();
        HuskSyncVariableStore newStore = null;
        VariableService newService = null;
        try {
            newStore = new HuskSyncVariableStore(this, newLimits, newMetrics);
            if (!newStore.connect()) {
                getLogger().severe("Could not connect to HuskSync API.");
                newStore.close();
                return null;
            }

            newService = new VariableService(
                    this, newStore, getLogger(), PREFIX, newLimits, newMetrics);
            new DefaultVariables(this, newService);
            return new RuntimeServices(newStore, newService, newLimits, newMetrics);
        } catch (RuntimeException | LinkageError error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not construct the YOV runtime.", error);
            if (newService != null) newService.shutdown();
            if (newStore != null) newStore.close();
            return null;
        }
    }

    private boolean installServices(RuntimeServices runtime) {
        try {
            runtime.store().activateOnlineEdits(runtime.service()::applyNetworkEdit);
        } catch (RuntimeException error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Could not activate the YOV Redis delta channel.", error);
            return false;
        }
        variableStore = runtime.store();
        variableService = runtime.service();
        variableLimits = runtime.limits();
        metrics = runtime.metrics();
        if (api == null) registerApi();
        return true;
    }

    private void primeOnlinePlayers(RuntimeServices runtime) {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            var onlineUser = runtime.store().api().getOnlineUser(player.getUniqueId());
            if (onlineUser.isEmpty() || onlineUser.get().isLocked()) {
                runtime.service().waitForSynchronization(player);
            } else {
                runtime.service().bootstrapReadyPlayer(
                        player, runtime.store().readOnline(player), false);
            }
        }
    }

    private void continueOnlinePlayerLoading(RuntimeServices runtime) {
        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            if (!runtime.service().isPlayerReady(player.getUniqueId())) {
                publishOnlinePlayerWhenUnlocked(runtime, player);
            }
        }
    }

    private void publishOnlinePlayerWhenUnlocked(RuntimeServices runtime,
                                                  org.bukkit.entity.Player player) {
        if (!isEnabled() || variableService != runtime.service()
                || variableStore != runtime.store()
                || Bukkit.getPlayer(player.getUniqueId()) != player
                || runtime.service().isPlayerReady(player.getUniqueId())) {
            return;
        }

        var onlineUser = runtime.store().api().getOnlineUser(player.getUniqueId());
        if (onlineUser.isPresent() && !onlineUser.get().isLocked()) {
            try {
                runtime.service().bootstrapReadyPlayer(player, runtime.store().readOnline(player));
            } catch (RuntimeException error) {
                runtime.service().failSynchronization(
                        player, "Failed to publish synchronized YOV variables.");
                getLogger().log(java.util.logging.Level.WARNING,
                        "Could not publish online YOV variables for " + player.getUniqueId(), error);
            }
            return;
        }
        Bukkit.getScheduler().runTaskLater(
                this, () -> publishOnlinePlayerWhenUnlocked(runtime, player), 1L);
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("yov");
        if (cmd == null) return;

        YOVCommand executor = new YOVCommand(this, variableService);
        cmd.setExecutor(executor);
        cmd.setTabCompleter(executor);
    }

    private void registerRuntimeListeners() {
        PapiExpansionListener papiListener = new PapiExpansionListener(this);
        Bukkit.getPluginManager().registerEvents(papiListener, this);
        if (getConfig().getBoolean("husksync.login-preload.enabled", false)) {
            Bukkit.getPluginManager().registerEvents(new PlayerPreloadListener(this, variableService), this);
        }
        Bukkit.getPluginManager().registerEvents(
                new HuskSyncYovListener(this, variableService, variableStore), this);
    }

    private void registerApi() {
        unregisterApi();
        api = new YOVApiImpl(this, () -> variableService);
        Bukkit.getServicesManager().register(YOVApi.class, api, this, ServicePriority.Normal);
    }

    private void unregisterApi() {
        if (api == null) {
            return;
        }
        Bukkit.getServicesManager().unregister(YOVApi.class, api);
        api = null;
    }

    private void unregisterPlaceholders() {
        try {
            if (varPlayerKeyPlaceholder != null) varPlayerKeyPlaceholder.unregister();
            if (roundedPlaceholder != null) roundedPlaceholder.unregister();
        } catch (RuntimeException e) {
            getLogger().warning("Failed to unregister PlaceholderAPI expansions: " + e.getMessage());
        } finally {
            varPlayerKeyPlaceholder = null;
            roundedPlaceholder = null;
        }
    }

    private record RuntimeServices(HuskSyncVariableStore store,
                                   VariableService service,
                                   VariableLimits limits,
                                   YOVMetrics metrics) {
    }
}
