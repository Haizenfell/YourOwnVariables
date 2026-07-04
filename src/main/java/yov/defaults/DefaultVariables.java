package yov.defaults;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitRunnable;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultVariables implements Listener {

    private final YOVPlugin plugin;
    private final VariableService service;
    private FileConfiguration defaults;

    public DefaultVariables(YOVPlugin plugin, VariableService service) {
        this.plugin = plugin;
        this.service = service;
        loadDefaults();
        service.setDefaultVariablesSupplier(this::getDefaults);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Map<String, String> defaultValues = getDefaults();
            for (Player player : Bukkit.getOnlinePlayers()) {
                service.applyDefaults(player, defaultValues);
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int timeoutTicks = Math.max(1, plugin.getConfig().getInt("defaults.apply-timeout-ticks", 100));

        new BukkitRunnable() {
            private int waited;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                if (service.isPlayerLoaded(player.getName())) {
                    service.applyDefaults(player, getDefaults());
                    cancel();
                    return;
                }

                if (waited >= timeoutTicks) {
                    plugin.getLogger().warning("Default variables were not applied to " + player.getName()
                            + " because HuskSync data was not loaded after " + timeoutTicks + " ticks.");
                    cancel();
                    return;
                }

                waited++;
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private Map<String, String> getDefaults() {
        Map<String, String> values = new LinkedHashMap<>();
        if (!defaults.isConfigurationSection("player_variables")) return values;

        var section = defaults.getConfigurationSection("player_variables");
        if (section == null) return values;

        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (key != null && value != null) {
                values.put(key, value);
            }
        }
        return values;
    }

    private void loadDefaults() {
        File file = new File(plugin.getDataFolder(), "default_variables.yml");

        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            try (InputStream in = plugin.getResource("default_variables.yml")) {
                if (in != null) {
                    Files.copy(in, file.toPath());
                } else if (!file.createNewFile()) {
                    Bukkit.getLogger().warning("[YOV] Could not create default_variables.yml");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to create default_variables.yml: " + e.getMessage());
            }
        }

        defaults = YamlConfiguration.loadConfiguration(file);
    }
}
