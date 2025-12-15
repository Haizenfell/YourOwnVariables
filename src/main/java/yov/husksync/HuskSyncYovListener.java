package yov.husksync;

import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.event.BukkitDataSaveEvent;
import net.william278.husksync.event.BukkitPreSyncEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import yov.YOVPlugin;
import yov.service.VariableService;
import yov.storage.impl.HuskSyncStorage;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class HuskSyncYovListener implements Listener {

    private final BukkitHuskSyncAPI api;
    private final Identifier id;
    private final VariableService service;
    private final HuskSyncStorage storage;

    public HuskSyncYovListener(VariableService service, HuskSyncStorage storage) {
        this.api = BukkitHuskSyncAPI.getInstance();
        this.id = storage.getIdentifier();
        this.service = service;
        this.storage = storage;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreSync(BukkitPreSyncEvent e) {
        try {
            String name = getUsername(e.getUser());
            if (name == null || name.isEmpty()) return;

            String playerLower = name.toLowerCase(Locale.ROOT);

            DataSnapshot.Unpacked unpacked = api.unpackSnapshot(e.getData());
            YovVariablesData data = HuskSyncDataAccess.readYovData(api, unpacked, id);

            Map<String, String> vars = data != null ? data.getVariables() : Map.of();
            Map<String, String> full = new HashMap<>();

            for (Map.Entry<String, String> en : vars.entrySet()) {
                String k = en.getKey();
                String v = en.getValue();
                if (k == null || k.isEmpty()) continue;
                full.put(playerLower + "_" + k.toLowerCase(Locale.ROOT), v);
            }

            service.replacePlayerVariables(playerLower, full);
            service.markPlayerLoaded(playerLower, true);

        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDataSave(BukkitDataSaveEvent e) {
        try {
            String name = getUsername(e.getUser());
            if (name == null || name.isEmpty()) return;

            String playerLower = name.toLowerCase(Locale.ROOT);

            Map<String, String> vars = service.snapshotPlayerVars(playerLower);
            YovVariablesData out = new YovVariablesData(vars);

            e.editData(unpacked -> {
                HuskSyncDataAccess.writeYovData(api, unpacked, id, out);
            });

            Bukkit.getScheduler().runTaskLater(YOVPlugin.getInstance(), () -> {
                if (Bukkit.getPlayerExact(name) != null) return;
                service.markPlayerLoaded(playerLower, false);
                service.unloadPlayer(playerLower);
            }, 1L);

        } catch (Exception ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        String name = e.getPlayer().getName();
        String playerLower = name.toLowerCase(Locale.ROOT);

        Bukkit.getScheduler().runTaskLater(YOVPlugin.getInstance(), () -> {
            if (Bukkit.getPlayerExact(name) != null) return;
            service.markPlayerLoaded(playerLower, false);
            service.unloadPlayer(playerLower);
        }, 40L);
    }

    private String getUsername(Object user) {
        if (user == null) return null;
        for (String m : new String[]{"getUsername", "getName"}) {
            try {
                Method mm = user.getClass().getMethod(m);
                Object r = mm.invoke(user);
                if (r instanceof String s) return s;
            } catch (Exception ignored) {}
        }
        try {
            Method mm = user.getClass().getMethod("getUuid");
            Object r = mm.invoke(user);
            if (r instanceof java.util.UUID uuid) {
                var p = Bukkit.getPlayer(uuid);
                if (p != null) return p.getName();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
