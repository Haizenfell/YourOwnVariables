package yov.husksync;

import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataHolder;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.user.BukkitUser;
import net.william278.husksync.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import yov.YOVPlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class HuskSyncVariableStore {

    public record OfflineEditResult(boolean success, String message, Map<String, String> variables) {
        public static OfflineEditResult success(Map<String, String> variables) {
            return new OfflineEditResult(true, "ok", variables);
        }

        public static OfflineEditResult fail(String message) {
            return new OfflineEditResult(false, message, Collections.emptyMap());
        }
    }

    private final YOVPlugin plugin;

    private BukkitHuskSync huskSync;
    private BukkitHuskSyncAPI api;
    private Identifier identifier;

    public HuskSyncVariableStore(YOVPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        Plugin installed = Bukkit.getPluginManager().getPlugin("HuskSync");
        if (!(installed instanceof BukkitHuskSync bukkitHuskSync)) {
            return false;
        }

        huskSync = bukkitHuskSync;
        api = BukkitHuskSyncAPI.getInstance();
        identifier = Identifier.from("yov", "variables");

        try {
            api.registerDataSerializer(identifier, new YovVariablesSerializer(huskSync));
        } catch (Exception e) {
            plugin.getLogger().fine("YOV HuskSync serializer was already registered or could not be registered: " + e.getMessage());
        }

        return true;
    }

    public void close() {
        huskSync = null;
        api = null;
        identifier = null;
    }

    public boolean isReady() {
        return huskSync != null && api != null && identifier != null;
    }

    public BukkitHuskSyncAPI api() {
        return api;
    }

    public Identifier identifier() {
        return identifier;
    }

    public Map<String, String> readVariables(DataHolder holder) {
        if (holder == null || identifier == null) return Collections.emptyMap();

        Optional<? extends Data> data = holder.getData(identifier);
        if (data.isPresent() && data.get() instanceof YovVariablesData yovData) {
            return normalize(yovData.getVariables());
        }

        Data direct = holder.getData().get(identifier);
        if (direct instanceof YovVariablesData yovData) {
            return normalize(yovData.getVariables());
        }

        return Collections.emptyMap();
    }

    public void writeVariables(DataHolder holder, Map<String, String> variables) {
        if (holder == null || identifier == null) return;
        holder.setData(identifier, new YovVariablesData(normalize(variables)));
    }

    public void pushOnline(Player player, Map<String, String> variables) {
        if (!isReady() || player == null) return;

        BukkitUser user = api.getUser(player);
        user.setData(identifier, new YovVariablesData(normalize(variables)));
    }

    public Map<String, String> readOnline(Player player) {
        if (!isReady() || player == null) return Collections.emptyMap();
        return readVariables(api.getUser(player));
    }

    public CompletableFuture<Map<String, String>> pullCurrent(String playerName) {
        if (!isReady() || playerName == null || playerName.isBlank()) {
            return CompletableFuture.completedFuture(Collections.emptyMap());
        }

        return api.getUser(playerName)
                .thenCompose(optionalUser -> optionalUser
                        .map(user -> api.getCurrentData(user).thenApply(this::readOptionalSnapshot))
                        .orElseGet(() -> CompletableFuture.completedFuture(Collections.emptyMap())))
                .exceptionally(error -> {
                    plugin.getLogger().warning("Failed to pull HuskSync data for " + playerName + ": " + error.getMessage());
                    return Collections.emptyMap();
                });
    }

    public CompletableFuture<OfflineEditResult> editOffline(String playerName,
                                                            Function<Map<String, String>, OfflineEditResult> editor) {
        if (!isReady() || playerName == null || playerName.isBlank()) {
            return CompletableFuture.completedFuture(OfflineEditResult.fail("HuskSync is not ready."));
        }

        String requestedName = playerName;
        return api.getUser(playerName)
                .thenCompose(optionalUser -> optionalUser
                        .map(user -> editCurrentData(user, editor))
                        .orElseGet(() -> CompletableFuture.completedFuture(
                                OfflineEditResult.fail("Player '" + requestedName + "' was not found in HuskSync."))))
                .exceptionally(error -> {
                    plugin.getLogger().warning("Failed to edit HuskSync data for " + requestedName + ": " + error.getMessage());
                    return OfflineEditResult.fail("Failed to edit HuskSync data. See console.");
                });
    }

    private CompletableFuture<OfflineEditResult> editCurrentData(User user,
                                                                 Function<Map<String, String>, OfflineEditResult> editor) {
        return api.getCurrentData(user).thenApply(optionalSnapshot -> {
            if (optionalSnapshot.isEmpty()) {
                return OfflineEditResult.fail("Player '" + user.getName() + "' has no HuskSync data.");
            }

            DataSnapshot.Unpacked snapshot = optionalSnapshot.get();
            Map<String, String> variables = new LinkedHashMap<>(readVariables(snapshot));
            OfflineEditResult result = editor.apply(variables);
            if (!result.success()) {
                return result;
            }

            writeVariables(snapshot, variables);
            snapshot.setId(java.util.UUID.randomUUID());
            api.setCurrentData(user, snapshot);
            return new OfflineEditResult(true, result.message(), variables);
        });
    }

    private Map<String, String> readOptionalSnapshot(Optional<DataSnapshot.Unpacked> optionalSnapshot) {
        return optionalSnapshot.map(this::readVariables).orElseGet(Collections::emptyMap);
    }

    public Map<String, String> normalize(Map<String, String> variables) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (variables == null || variables.isEmpty()) return normalized;

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = normalizeVariable(entry.getKey());
            String value = entry.getValue();
            if (key == null || value == null) continue;
            normalized.put(key, value);
        }

        return normalized;
    }

    public String normalizeVariable(String variable) {
        if (variable == null) return null;
        String normalized = variable.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
