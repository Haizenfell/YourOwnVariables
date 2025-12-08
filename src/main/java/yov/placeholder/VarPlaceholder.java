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
package yov.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.cache.VariableCache;

public class VarPlaceholder extends PlaceholderExpansion {

    private final VariableCache cache;
    private final YOVPlugin plugin;

    public VarPlaceholder(YOVPlugin plugin, VariableCache cache) {
        this.cache = cache;
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "yov";
    }

    @Override
    public @NotNull String getAuthor() {
        return plugin.getPluginAuthors();
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(@Nullable Player player, @NotNull String identifier) {
        String key = identifier;

        if (identifier.startsWith("player_key:")) {
            if (player == null) return "null";
            String var = identifier.substring("player_key:".length());
            key = player.getName().toLowerCase() + "_" + var.toLowerCase();
        }

        String val = cache.getOrDefault(key.toLowerCase(), "null");
        return val;
    }
}
