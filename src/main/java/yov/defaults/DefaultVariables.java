/*
 * This file is part of YourOwnVariables.
 * Copyright (C) 2025 Haizenfell
 *
 * Licensed under the YourOwnVariables Proprietary License.
 * Unauthorized copying, modification, distribution, or reverse engineering
 * of this software is strictly prohibited.
 *
 * Full license text is provided in the LICENSE file.
 */
package yov.defaults;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
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
            } catch (Exception ignored) {}
        }

        defaults = YamlConfiguration.loadConfiguration(file);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String player = p.getName().toLowerCase(Locale.ROOT);

        if (!defaults.contains("variables")) return;

        for (String var : defaults.getConfigurationSection("variables").getKeys(false)) {
            String fullKey = player + "_" + var.toLowerCase(Locale.ROOT);

            if (cache.get(fullKey) != null) continue;

            String value = defaults.getString("variables." + var);
            if (value == null) continue;

            service.setVariable(fullKey, value, null, true);
        }
    }
}
