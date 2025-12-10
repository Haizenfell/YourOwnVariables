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
import yov.service.VariableService;

import java.util.Locale;

public class RoundedPlaceholder extends PlaceholderExpansion {

    private final VariableService variableService;
    private final YOVPlugin plugin;

    public RoundedPlaceholder(YOVPlugin plugin, VariableService variableService) {
        this.plugin = plugin;
        this.variableService = variableService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rounded";
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
        if (identifier.isEmpty()) return null;

        try {
            int decimals = 0;
            boolean isPlayerKey = identifier.startsWith("player_key");
            String keyPart = identifier;

            if (isPlayerKey) {
                keyPart = identifier.substring("player_key".length());
            }

            if (keyPart.startsWith("_")) {
                String[] split = keyPart.substring(1).split(":", 2);
                try {
                    decimals = Integer.parseInt(split[0]);
                } catch (NumberFormatException ignored) {}
                keyPart = ":" + split[1];
            }

            if (!keyPart.startsWith(":")) return "null";

            String key = keyPart.substring(1);
            if (isPlayerKey && player != null) {
                key = player.getName().toLowerCase(Locale.ROOT) + "_" + key.toLowerCase(Locale.ROOT);
            }

            String value = variableService.getSynchronizedValue(key.toLowerCase(Locale.ROOT));
            if (value == null || value.equals("null")) return "null";

            double num = Double.parseDouble(value);
            String pattern = "%." + decimals + "f";
            return String.format(Locale.ROOT, pattern, num);
        } catch (Exception e) {
            return "null";
        }
    }
}
