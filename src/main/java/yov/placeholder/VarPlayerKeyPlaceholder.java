package yov.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.service.VariableService;

public class VarPlayerKeyPlaceholder extends PlaceholderExpansion {

    private final YOVPlugin plugin;
    private final VariableService variableService;

    public VarPlayerKeyPlaceholder(YOVPlugin plugin, VariableService variableService) {
        this.plugin = plugin;
        this.variableService = variableService;
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
    public @Nullable String onPlaceholderRequest(@Nullable Player player, @NotNull String identifier) {
        if (player == null || identifier.isBlank()) return "null";
        if (!identifier.startsWith("player_key:")) return "null";

        String variable = identifier.substring("player_key:".length());
        String value = variableService.getPlayerValue(player.getName(), variable);
        return value != null ? value : "null";
    }
}
