package yov.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class RoundedPlaceholder extends PlaceholderExpansion {

    private final YOVPlugin plugin;
    private final VariableService variableService;

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
    public @Nullable String onPlaceholderRequest(@Nullable Player player, @NotNull String identifier) {
        if (identifier.isBlank()) return "null";

        ParsedRounded parsed = parse(identifier);
        if (parsed == null) return "null";
        if (player == null) return loadingValue();

        String value = variableService.getPlayerValue(player.getName(), parsed.variable());
        if (value == null) {
            if (!variableService.isPlayerLoaded(player.getName())) {
                return loadingValue();
            }
            return "null";
        }

        try {
            return new BigDecimal(value.trim())
                    .setScale(parsed.decimals(), RoundingMode.HALF_UP)
                    .toPlainString();
        } catch (NumberFormatException ex) {
            return "null";
        }
    }

    private String loadingValue() {
        String value = plugin.getConfig().getString("placeholders.loading-value", "loading");
        return value != null ? value : "loading";
    }

    private ParsedRounded parse(String identifier) {
        if (!identifier.startsWith("player_key")) return null;

        String tail = identifier.substring("player_key".length());
        if (tail.startsWith(":")) {
            String variable = tail.substring(1);
            return variable.isBlank() ? null : new ParsedRounded(variable, 0);
        }

        if (!tail.startsWith("_")) return null;
        String[] split = tail.substring(1).split(":", 2);
        if (split.length != 2 || split[1].isBlank()) return null;

        try {
            int decimals = Integer.parseInt(split[0]);
            if (decimals < 0 || decimals > 10) return null;
            return new ParsedRounded(split[1], decimals);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private record ParsedRounded(String variable, int decimals) {
    }
}
