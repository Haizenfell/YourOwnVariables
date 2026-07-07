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
        if (identifier.isBlank()) return "null";
        if (identifier.equalsIgnoreCase("player_loaded")) {
            return Boolean.toString(player != null && variableService.isPlayerLoaded(player.getName()));
        }
        if (!identifier.startsWith("player_key:")) return "null";
        if (player == null) return loadingValue();

        String variable = identifier.substring("player_key:".length());
        String value = variableService.getPlayerValue(player.getName(), variable);
        if (value != null) return value;
        if (!variableService.isPlayerLoaded(player.getName())) return loadingValue();
        return "null";
    }

    private String loadingValue() {
        String value = plugin.getConfig().getString("placeholders.loading-value", "loading");
        return value != null ? value : "loading";
    }
}
