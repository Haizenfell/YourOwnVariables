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
    private final String loadingValue;

    public VarPlayerKeyPlaceholder(YOVPlugin plugin, VariableService variableService) {
        this.plugin = plugin;
        this.variableService = variableService;
        String configured = plugin.getConfig().getString("placeholders.loading-value", "loading");
        this.loadingValue = configured != null ? configured : "loading";
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
        if (!identifier.startsWith("player_key:")) return "null";
        if (player == null) return loadingValue;

        String variable = identifier.substring("player_key:".length());
        VariableService.LocalValue local = variableService.readLocalValue(
                player.getUniqueId(), variable);
        if (!local.ready()) return loadingValue;
        return local.value() != null ? local.value() : "null";
    }
}
