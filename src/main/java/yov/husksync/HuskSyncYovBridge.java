package yov.husksync;

import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.user.BukkitUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import yov.YOVPlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class HuskSyncYovBridge {

    private final YOVPlugin plugin;

    private BukkitHuskSync huskSync;
    private BukkitHuskSyncAPI api;
    private Identifier id;

    public HuskSyncYovBridge(YOVPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        Plugin pl = Bukkit.getPluginManager().getPlugin("HuskSync");
        if (!(pl instanceof BukkitHuskSync hs)) {
            return false;
        }

        this.huskSync = hs;
        this.api = BukkitHuskSyncAPI.getInstance();
        this.id = Identifier.from("yov", "variables");

        try {
            api.registerDataSerializer(id, new YovVariablesSerializer(huskSync));
        } catch (Exception e) {
            plugin.getLogger().warning("[YOV] Failed to register HuskSync data serializer: " + e.getMessage());
        }

        return true;
    }

    public Identifier getId() {
        return id;
    }

    public boolean isReady() {
        return api != null && huskSync != null && id != null;
    }

    public void push(Player player, Map<String, String> vars) {
        if (!isReady() || player == null) return;
        BukkitUser user = BukkitUser.adapt(player, huskSync);
        java.util.Map<String, String> safe = vars == null ? java.util.Collections.emptyMap() : new java.util.HashMap<>(vars);
        user.setData(id, new YovVariablesData(safe));
    }

    public Map<String, String> pull(Player player) {
        if (!isReady() || player == null) return Collections.emptyMap();
        BukkitUser user = BukkitUser.adapt(player, huskSync);

        return user.getData(id)
                .filter(YovVariablesData.class::isInstance)
                .map(YovVariablesData.class::cast)
                .map(YovVariablesData::getVariables)
                .map(m -> (Map<String, String>) new HashMap<>(m))
                .orElseGet(Collections::emptyMap);
    }

}
