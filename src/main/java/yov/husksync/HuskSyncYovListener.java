package yov.husksync;

import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.event.BukkitDataSaveEvent;
import net.william278.husksync.event.BukkitPreSyncEvent;
import net.william278.husksync.user.User;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.util.Locale;
import java.util.Map;

public class HuskSyncYovListener implements Listener {

    private final VariableService service;
    private final HuskSyncVariableStore store;
    private final BukkitHuskSyncAPI api;

    public HuskSyncYovListener(VariableService service, HuskSyncVariableStore store) {
        this.service = service;
        this.store = store;
        this.api = store.api();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreSync(BukkitPreSyncEvent event) {
        try {
            String playerName = event.getUser().getUsername();
            DataSnapshot.Unpacked unpacked = api.unpackSnapshot(event.getData());
            Map<String, String> variables = store.readVariables(unpacked);
            service.replacePlayerVariables(playerName, variables);
        } catch (Exception e) {
            YOVPlugin.getInstance().getLogger().warning("Failed to read YOV variables from HuskSync pre-sync data: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDataSave(BukkitDataSaveEvent event) {
        try {
            User user = event.getUser();
            String playerName = user.getName();
            if (!service.hasPlayerSnapshot(playerName)) return;

            Map<String, String> variables = service.snapshotPlayerVars(playerName);
            event.editData(unpacked -> store.writeVariables(unpacked, variables));
        } catch (Exception e) {
            YOVPlugin.getInstance().getLogger().warning("Failed to write YOV variables into HuskSync save data: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        String name = event.getPlayer().getName();
        String lower = name.toLowerCase(Locale.ROOT);
        long delay = Math.max(1L, YOVPlugin.getInstance().getConfig().getLong("husksync.unload-delay-ticks", 40L));

        Bukkit.getScheduler().runTaskLater(YOVPlugin.getInstance(), () -> {
            if (Bukkit.getPlayerExact(name) != null) return;
            service.unloadPlayer(lower);
        }, delay);
    }
}
