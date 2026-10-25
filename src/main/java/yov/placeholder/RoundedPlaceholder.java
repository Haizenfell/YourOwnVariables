package yov.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.config.VariableLimits;
import yov.service.VariableService;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class RoundedPlaceholder extends PlaceholderExpansion {

    private final YOVPlugin plugin;
    private final VariableService variableService;
    private final String loadingValue;
    private final int maxOutputCharacters;

    public RoundedPlaceholder(YOVPlugin plugin, VariableService variableService) {
        this.plugin = plugin;
        this.variableService = variableService;
        String configured = plugin.getConfig().getString("placeholders.loading-value", "loading");
        this.loadingValue = configured != null ? configured : "loading";
        VariableLimits limits = plugin.getVariableLimits();
        this.maxOutputCharacters = Math.min(65_536, limits != null
                ? limits.maxValueBytes()
                : VariableLimits.DEFAULT_MAX_VALUE_BYTES);
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
        if (player == null) return loadingValue;

        VariableService.LocalValue local = variableService.readLocalValue(
                player.getUniqueId(), parsed.variable());
        if (!local.ready()) return loadingValue;
        String value = local.value();
        if (value == null) return "null";

        try {
            BigDecimal numeric = new BigDecimal(value.trim());
            long scaleDelta = Math.abs((long) numeric.scale() - parsed.decimals());
            long integerCharacters = Math.max(
                    1L, (long) numeric.precision() - numeric.scale() + 1L);
            long estimatedCharacters = integerCharacters
                    + (numeric.signum() < 0 ? 1L : 0L)
                    + (parsed.decimals() > 0 ? parsed.decimals() + 1L : 0L);
            if (scaleDelta > maxOutputCharacters
                    || estimatedCharacters > maxOutputCharacters) {
                return "null";
            }
            String rounded = numeric.setScale(parsed.decimals(), RoundingMode.HALF_UP)
                    .toPlainString();
            return rounded.length() <= maxOutputCharacters ? rounded : "null";
        } catch (NumberFormatException | ArithmeticException ex) {
            return "null";
        }
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
