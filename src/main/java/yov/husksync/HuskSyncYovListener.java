package yov.husksync;

import net.william278.husksync.event.BukkitPreSyncEvent;
import net.william278.husksync.event.BukkitSyncCompleteEvent;
import net.william278.husksync.user.BukkitUser;
import net.william278.husksync.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.util.Map;
import java.util.UUID;

public class HuskSyncYovListener implements Listener {

    private final YOVPlugin plugin;
    private final VariableService service;
    private final HuskSyncVariableStore store;
    private final long unloadDelayTicks;

    public HuskSyncYovListener(YOVPlugin plugin,
                               VariableService service,
                               HuskSyncVariableStore store) {
        this.plugin = plugin;
        this.service = service;
        this.store = store;
        this.unloadDelayTicks = Math.max(1L, plugin.getConfig()
                .getLong("husksync.unload-delay-ticks", 40L));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        service.startPlayerSession(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreSync(BukkitPreSyncEvent event) {
        Player player = sessionPlayer(event.getUser());
        if (!isCurrentSession(player)) return;
        try {
            event.editData(unpacked -> {
                Map<String, String> incoming = store.readVariables(unpacked);
                Map<String, String> prepared = service.prepareIncomingSnapshot(
                        player, event.getData().getId(), incoming);
                store.writeVariables(unpacked, prepared);
            });
        } catch (Exception e) {
            event.setCancelled(true);
            service.failSynchronization(player, "Failed to prepare YOV variables for synchronization.");
            plugin.getLogger().warning(
                    "Failed to prepare YOV variables in HuskSync pre-sync data: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreSyncFinished(BukkitPreSyncEvent event) {
        Player player = sessionPlayer(event.getUser());
        if (!isCurrentSession(player)) return;
        long syncToken = service.synchronizationToken(player);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (event.isCancelled() && isCurrentSession(player)) {
                try {
                    service.cancelSynchronization(
                            player,
                            syncToken,
                            "HuskSync player synchronization was cancelled.");
                } catch (RuntimeException error) {
                    service.failSynchronization(
                            player, "Failed to restore YOV variables after cancelled synchronization.");
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "Failed to restore YOV variables for " + player.getUniqueId(), error);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSyncComplete(BukkitSyncCompleteEvent event) {
        if (!(event.getUser() instanceof BukkitUser user)) return;
        Player player = user.getPlayer();
        if (!isCurrentSession(player)) return;
        try {
            long syncToken = service.acknowledgeSynchronizationComplete(player);
            if (syncToken < 0L) return;
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> publishWhenUnlocked(user, player, syncToken));
        } catch (Exception e) {
            service.failSynchronization(player, "Failed to publish synchronized YOV variables.");
            plugin.getLogger().warning(
                    "Failed to publish synchronized YOV variables: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> service.disconnectPlayer(player));

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(playerId) != null) return;
            service.unloadPlayer(player);
        }, unloadDelayTicks);
    }

    private void publishWhenUnlocked(BukkitUser user, Player player, long syncToken) {
        if (!isCurrentSession(player) || service.synchronizationToken(player) != syncToken) return;
        if (user.isLocked()) {
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> publishWhenUnlocked(user, player, syncToken),
                    1L);
            return;
        }
        try {
            service.completeSynchronization(player, store.readVariables(user));
        } catch (Exception e) {
            service.failSynchronization(player, "Failed to publish synchronized YOV variables.");
            plugin.getLogger().warning(
                    "Failed to publish synchronized YOV variables: " + e.getMessage());
        }
    }

    private Player sessionPlayer(User user) {
        return user instanceof BukkitUser bukkitUser ? bukkitUser.getPlayer() : null;
    }

    private boolean isCurrentSession(Player player) {
        return player != null && Bukkit.getPlayer(player.getUniqueId()) == player;
    }
}
