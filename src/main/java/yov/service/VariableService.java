package yov.service;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import yov.YOVPlugin;
import yov.cache.VariableCache;
import yov.husksync.HuskSyncVariableStore;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class VariableService {

    private static final String DELETE_MARKER = "\u0000DELETE";
    private static final String CLEAR_MARKER = "\u0000CLEAR";

    private final YOVPlugin plugin;
    private final VariableCache cache;
    private final HuskSyncVariableStore store;
    private final Logger logger;
    private final String prefix;

    private final Set<String> loadedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Map<String, String>> variablesByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Map<String, String>> pendingByPlayer = new ConcurrentHashMap<>();
    private volatile Supplier<Map<String, String>> defaultVariablesSupplier = Collections::emptyMap;

    public VariableService(YOVPlugin plugin,
                           VariableCache cache,
                           HuskSyncVariableStore store,
                           Logger logger,
                           String prefix) {
        this.plugin = plugin;
        this.cache = cache;
        this.store = store;
        this.logger = logger;
        this.prefix = prefix;
    }

    public boolean isPlayerLoaded(String playerName) {
        if (playerName == null) return false;
        return loadedPlayers.contains(normalizePlayer(playerName));
    }

    public boolean hasPlayerSnapshot(String playerName) {
        if (playerName == null) return false;
        return variablesByPlayer.containsKey(normalizePlayer(playerName));
    }

    public void setDefaultVariablesSupplier(Supplier<Map<String, String>> supplier) {
        defaultVariablesSupplier = supplier != null ? supplier : Collections::emptyMap;
    }

    public void replacePlayerVariables(String playerName, Map<String, String> variables) {
        String playerLower = normalizePlayer(playerName);
        if (playerLower.isEmpty()) return;

        Map<String, String> normalized = normalizeVariables(variables);
        Map<String, String> pending = pendingByPlayer.remove(playerLower);
        boolean hadPending = pending != null && !pending.isEmpty();
        if (pending != null) {
            applyPending(normalized, pending);
        }

        boolean defaultsChanged = applyDefaultValues(normalized, getDefaultVariables());

        variablesByPlayer.put(playerLower, new ConcurrentHashMap<>(normalized));
        loadedPlayers.add(playerLower);
        rebuildCacheForPlayer(playerLower, normalized);

        Player player = findOnlinePlayer(playerLower);
        if (player != null && (hadPending || defaultsChanged)) {
            store.pushOnline(player, normalized);
        }
    }

    public void unloadPlayer(String playerName) {
        String playerLower = normalizePlayer(playerName);
        if (playerLower.isEmpty()) return;

        loadedPlayers.remove(playerLower);
        variablesByPlayer.remove(playerLower);
        pendingByPlayer.remove(playerLower);
        removeCacheForPlayer(playerLower);
    }

    public Map<String, String> snapshotPlayerVars(String playerName) {
        String playerLower = normalizePlayer(playerName);
        if (playerLower.isEmpty()) return Collections.emptyMap();

        Map<String, String> variables = variablesByPlayer.get(playerLower);
        if (variables == null) return Collections.emptyMap();
        return new LinkedHashMap<>(variables);
    }

    public String getSynchronizedValue(String fullKey) {
        if (fullKey == null) return null;
        return cache.get(fullKey.toLowerCase(Locale.ROOT));
    }

    public String getPlayerValue(String playerName, String variable) {
        String fullKey = toFullKey(playerName, variable);
        return fullKey == null ? null : cache.get(fullKey);
    }

    public void checkVariable(String playerName, String variable, CommandSender sender) {
        String playerLower = normalizePlayer(playerName);
        String var = normalizeVariable(variable);
        if (playerLower.isEmpty() || var == null) {
            sender.sendMessage(prefix + "§cInvalid player or variable.");
            return;
        }

        String cached = getPlayerValue(playerLower, var);
        if (cached != null || isPlayerLoaded(playerLower)) {
            sender.sendMessage(prefix + "§e" + playerLower + "§7/§e" + var + "§7: §f" + (cached != null ? cached : "null"));
            return;
        }

        store.pullCurrent(playerLower).thenAccept(variables -> runSync(() -> {
            String value = variables.get(var);
            sender.sendMessage(prefix + "§e" + playerLower + "§7/§e" + var + "§7: §f" + (value != null ? value : "null"));
        }));
    }

    public void setVariable(String playerName, String variable, String value, CommandSender sender, boolean silent) {
        String playerLower = normalizePlayer(playerName);
        String var = normalizeVariable(variable);
        if (playerLower.isEmpty() || var == null) {
            send(sender, silent, "§cInvalid player or variable.");
            return;
        }

        Player player = findOnlinePlayer(playerLower);
        if (player != null) {
            mutateOnline(player, var, value, false);
            send(sender, silent, "§aSet §e" + var + "§a for §e" + player.getName() + "§a to §f" + value + "§a.");
            return;
        }

        editOffline(playerLower, variables -> {
            variables.put(var, value);
            cache.put(toFullKey(playerLower, var), value);
            return HuskSyncVariableStore.OfflineEditResult.success(variables);
        }, sender, silent, "§aSet §e" + var + "§a for §e" + playerLower + "§a to §f" + value + "§a.");
    }

    public void deleteVariable(String playerName, String variable, CommandSender sender, boolean silent) {
        String playerLower = normalizePlayer(playerName);
        String var = normalizeVariable(variable);
        if (playerLower.isEmpty() || var == null) {
            send(sender, silent, "§cInvalid player or variable.");
            return;
        }

        Player player = findOnlinePlayer(playerLower);
        if (player != null) {
            mutateOnline(player, var, null, true);
            send(sender, silent, "§aDeleted §e" + var + "§a for §e" + player.getName() + "§a.");
            return;
        }

        editOffline(playerLower, variables -> {
            variables.remove(var);
            cache.remove(toFullKey(playerLower, var));
            return HuskSyncVariableStore.OfflineEditResult.success(variables);
        }, sender, silent, "§aDeleted §e" + var + "§a for §e" + playerLower + "§a.");
    }

    public void addVariable(String playerName, String variable, String amount, CommandSender sender, boolean silent) {
        modifyNumber(playerName, variable, amount, sender, silent, true);
    }

    public void remVariable(String playerName, String variable, String amount, CommandSender sender, boolean silent) {
        modifyNumber(playerName, variable, amount, sender, silent, false);
    }

    public void clearPlayerVariables(String playerName, CommandSender sender) {
        String playerLower = normalizePlayer(playerName);
        if (playerLower.isEmpty()) {
            sender.sendMessage(prefix + "§cInvalid player.");
            return;
        }

        Player player = findOnlinePlayer(playerLower);
        if (player != null) {
            Map<String, String> previous = variablesByPlayer.getOrDefault(playerLower, Collections.emptyMap());
            int removed = previous.size();
            variablesByPlayer.put(playerLower, new ConcurrentHashMap<>());
            if (isPlayerLoaded(playerLower)) {
                pendingByPlayer.remove(playerLower);
            } else {
                Map<String, String> pending = new ConcurrentHashMap<>();
                pending.put(CLEAR_MARKER, DELETE_MARKER);
                pendingByPlayer.put(playerLower, pending);
            }
            removeCacheForPlayer(playerLower);
            store.pushOnline(player, Collections.emptyMap());
            sender.sendMessage(prefix + "§aRemoved §e" + removed + "§a variables for §e" + player.getName() + "§a.");
            return;
        }

        store.editOffline(playerLower, variables -> {
            int removed = variables.size();
            variables.clear();
            removeCacheForPlayer(playerLower);
            return new HuskSyncVariableStore.OfflineEditResult(true, String.valueOf(removed), variables);
        }).thenAccept(result -> runSync(() -> {
            if (!result.success()) {
                sender.sendMessage(prefix + "§c" + result.message());
                return;
            }
            sender.sendMessage(prefix + "§aRemoved variables for §e" + playerLower + "§a.");
        }));
    }

    public void applyDefaults(Player player, Map<String, String> defaults) {
        if (player == null || defaults == null || defaults.isEmpty()) return;

        String playerLower = normalizePlayer(player.getName());
        if (!isPlayerLoaded(playerLower)) return;

        Map<String, String> vars = variablesByPlayer.computeIfAbsent(playerLower, ignored -> new ConcurrentHashMap<>());
        boolean changed = applyDefaultValues(vars, defaults);

        if (changed) {
            rebuildCacheForPlayer(playerLower, vars);
            store.pushOnline(player, vars);
        }
    }

    private void modifyNumber(String playerName,
                              String variable,
                              String amount,
                              CommandSender sender,
                              boolean silent,
                              boolean add) {
        String playerLower = normalizePlayer(playerName);
        String var = normalizeVariable(variable);
        BigDecimal delta;

        try {
            delta = parseDecimal(amount);
        } catch (NumberFormatException ex) {
            send(sender, silent, "§cInvalid number: " + amount);
            return;
        }

        Player player = findOnlinePlayer(playerLower);
        if (player != null) {
            String current = getPlayerValue(playerLower, var);
            BigDecimal currentNumber;
            try {
                currentNumber = current == null ? BigDecimal.ZERO : parseDecimal(current);
            } catch (NumberFormatException ex) {
                send(sender, silent, "§cVariable '" + var + "' is not numeric.");
                return;
            }

            String result = formatDecimal(add ? currentNumber.add(delta) : currentNumber.subtract(delta));
            mutateOnline(player, var, result, false);
            send(sender, silent, "§a" + (add ? "Added" : "Removed") + " §e" + amount + "§a for §e" + player.getName() + "§a/§e" + var + "§a. New value: §f" + result + "§a.");
            return;
        }

        editOffline(playerLower, variables -> {
            String current = variables.get(var);
            BigDecimal currentNumber;
            try {
                currentNumber = current == null ? BigDecimal.ZERO : parseDecimal(current);
            } catch (NumberFormatException ex) {
                return HuskSyncVariableStore.OfflineEditResult.fail("Variable '" + var + "' is not numeric.");
            }

            String result = formatDecimal(add ? currentNumber.add(delta) : currentNumber.subtract(delta));
            variables.put(var, result);
            cache.put(toFullKey(playerLower, var), result);
            return new HuskSyncVariableStore.OfflineEditResult(true, result, variables);
        }, sender, silent, null);
    }

    private void mutateOnline(Player player, String variable, String value, boolean delete) {
        String playerLower = normalizePlayer(player.getName());
        String var = normalizeVariable(variable);
        if (var == null) return;

        if (!isPlayerLoaded(playerLower)) {
            pendingByPlayer.computeIfAbsent(playerLower, ignored -> new ConcurrentHashMap<>())
                    .put(var, delete ? DELETE_MARKER : value);
        }

        Map<String, String> vars = variablesByPlayer.computeIfAbsent(playerLower, ignored -> new ConcurrentHashMap<>());
        if (delete) {
            vars.remove(var);
            cache.remove(toFullKey(playerLower, var));
        } else {
            vars.put(var, value);
            cache.put(toFullKey(playerLower, var), value);
        }

        if (isPlayerLoaded(playerLower)) {
            store.pushOnline(player, vars);
        }
    }

    private void editOffline(String playerLower,
                             java.util.function.Function<Map<String, String>, HuskSyncVariableStore.OfflineEditResult> editor,
                             CommandSender sender,
                             boolean silent,
                             String successMessage) {
        store.editOffline(playerLower, editor).thenAccept(result -> runSync(() -> {
            if (!result.success()) {
                send(sender, silent, "§c" + result.message());
                return;
            }

            if (successMessage != null) {
                send(sender, silent, successMessage);
            } else if (!silent && sender != null) {
                sender.sendMessage(prefix + "§aUpdated offline data for §e" + playerLower + "§a. New value: §f" + result.message() + "§a.");
            }
        }));
    }

    private void applyPending(Map<String, String> variables, Map<String, String> pending) {
        if (pending.containsKey(CLEAR_MARKER)) {
            variables.clear();
            pending.remove(CLEAR_MARKER);
        }

        for (Map.Entry<String, String> entry : pending.entrySet()) {
            if (DELETE_MARKER.equals(entry.getValue())) {
                variables.remove(entry.getKey());
            } else {
                variables.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private Map<String, String> getDefaultVariables() {
        try {
            Map<String, String> defaults = defaultVariablesSupplier.get();
            return defaults != null ? defaults : Collections.emptyMap();
        } catch (Exception e) {
            logger.warning("Failed to read default variables: " + e.getMessage());
            return Collections.emptyMap();
        }
    }

    private boolean applyDefaultValues(Map<String, String> variables, Map<String, String> defaults) {
        if (variables == null || defaults == null || defaults.isEmpty()) return false;

        boolean changed = false;
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            String key = normalizeVariable(entry.getKey());
            String value = entry.getValue();
            if (key == null || value == null || variables.containsKey(key)) continue;

            variables.put(key, value);
            changed = true;
        }
        return changed;
    }

    private Map<String, String> normalizeVariables(Map<String, String> variables) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (variables == null) return normalized;

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = normalizeVariable(entry.getKey());
            String value = entry.getValue();
            if (key == null || value == null) continue;
            normalized.put(key, value);
        }
        return normalized;
    }

    private void rebuildCacheForPlayer(String playerLower, Map<String, String> variables) {
        removeCacheForPlayer(playerLower);
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            cache.put(toFullKey(playerLower, entry.getKey()), entry.getValue());
        }
    }

    private void removeCacheForPlayer(String playerLower) {
        String keyPrefix = playerLower + "_";
        cache.getMap().keySet().removeIf(key -> key != null && key.startsWith(keyPrefix));
    }

    private Player findOnlinePlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        Player exact = Bukkit.getPlayerExact(playerName);
        if (exact != null) return exact;

        String lower = normalizePlayer(playerName);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(lower)) return player;
        }
        return null;
    }

    public boolean isKnownOfflinePlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        if (findOnlinePlayer(playerName) != null) return true;

        try {
            Object offline = Bukkit.class
                    .getMethod("getOfflinePlayerIfCached", String.class)
                    .invoke(null, playerName);
            if (offline instanceof org.bukkit.OfflinePlayer offlinePlayer) {
                return offlinePlayer.hasPlayedBefore() || offlinePlayer.isOnline();
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    public String normalizePlayer(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizeVariable(String variable) {
        return store.normalizeVariable(variable);
    }

    public String toFullKey(String playerName, String variable) {
        String playerLower = normalizePlayer(playerName);
        String var = normalizeVariable(variable);
        if (playerLower.isEmpty() || var == null) return null;
        return playerLower + "_" + var;
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null) throw new NumberFormatException("null");
        return new BigDecimal(raw.trim());
    }

    private String formatDecimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }

    private void runSync(Runnable task) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, task);
    }

    private void send(CommandSender sender, boolean silent, String message) {
        if (!silent && sender != null) {
            sender.sendMessage(prefix + message);
        }
    }
}
