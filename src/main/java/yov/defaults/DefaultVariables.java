package yov.defaults;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import yov.YOVPlugin;
import yov.cache.VariableCache;
import yov.service.VariableService;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

public class DefaultVariables implements Listener {

    private final YOVPlugin plugin;
    private final VariableCache cache;
    private final VariableService service;
    private FileConfiguration defaults;

    public DefaultVariables(YOVPlugin plugin, VariableCache cache, VariableService service) {
        this.plugin = plugin;
        this.cache = cache;
        this.service = service;

        loadDefaults();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void loadDefaults() {
        File file = new File(plugin.getDataFolder(), "default_variables.yml");

        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            try (InputStream in = plugin.getResource("default_variables.yml")) {
                if (in != null) {
                    Files.copy(in, file.toPath());
                } else {
                    file.createNewFile();
                }
            } catch (Exception ignored) {
            }
        }

        defaults = YamlConfiguration.loadConfiguration(file);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String playerLower = p.getName().toLowerCase(Locale.ROOT);

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    return;
                }
                if (service.isPlayerLoaded(playerLower)) {
                    applyDefaults(p);
                    cancel();
                    return;
                }
                ticks += 1;
                if (ticks >= 100) {
                    applyDefaults(p);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void applyDefaults(Player p) {
        service.syncPlayerFromStorage(p.getName());

        if (!defaults.isConfigurationSection("variables")) {
            return;
        }

        var section = defaults.getConfigurationSection("variables");
        if (section == null) {
            return;
        }

        var keys = section.getKeys(false);
        if (keys == null || keys.isEmpty()) {
            return;
        }

        String player = p.getName().toLowerCase(Locale.ROOT);

        for (String var : keys) {
            String fullKey = player + "_" + var.toLowerCase(Locale.ROOT);

            if (cache.get(fullKey) != null) continue;

            String value = section.getString(var);
            if (value == null) continue;

            service.setVariable(fullKey, value, null, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.flushNow();
    }
}
