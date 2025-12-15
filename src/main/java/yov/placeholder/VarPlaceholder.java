package yov.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.util.Locale;

public class VarPlaceholder extends PlaceholderExpansion {

    private final VariableService variableService;
    private final YOVPlugin plugin;

    public VarPlaceholder(YOVPlugin plugin, VariableService variableService) {
        this.variableService = variableService;
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
            key = player.getName().toLowerCase(Locale.ROOT) + "_" + var.toLowerCase(Locale.ROOT);
        }

        String val = variableService.getSynchronizedValue(key.toLowerCase(Locale.ROOT));
        return val != null ? val : "null";
    }
}
