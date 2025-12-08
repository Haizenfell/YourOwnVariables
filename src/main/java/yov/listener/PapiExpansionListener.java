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
package yov.listener;

import me.clip.placeholderapi.events.ExpansionsLoadedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import yov.YOVPlugin;

public class PapiExpansionListener implements Listener {

    private final YOVPlugin plugin;

    public PapiExpansionListener(YOVPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerAll() {
        plugin.registerPlaceholders();
    }

    @EventHandler
    public void onPapiReload(ExpansionsLoadedEvent event) {
        plugin.getLogger().info("[YOV] PlaceholderAPI reloaded — re-registering expansions...");
        registerAll();
        plugin.getLogger().info("[YOV] Re-register completed.");
    }
}
