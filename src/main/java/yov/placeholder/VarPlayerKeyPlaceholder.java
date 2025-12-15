package yov.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.util.Locale;

public class VarPlayerKeyPlaceholder extends PlaceholderExpansion {

    private final VariableService variableService;
    private final YOVPlugin plugin;

    public VarPlayerKeyPlaceholder(YOVPlugin plugin, VariableService variableService) {
        this.variableService = variableService;
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "yov_player_key";
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
        if (player == null) return "null";

        String fullKey = (player.getName() + "_" + identifier).toLowerCase(Locale.ROOT);

        String val = variableService.getSynchronizedValue(fullKey);
        return val != null ? val : "null";
    }
}
