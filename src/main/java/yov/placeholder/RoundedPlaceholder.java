package yov.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.cache.VariableCache;

import java.util.Locale;

public class RoundedPlaceholder extends PlaceholderExpansion {

    private final VariableCache cache;

    public RoundedPlaceholder(VariableCache cache) {
        this.cache = cache;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "rounded";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Haizenfell";
    }

    @Override
    public @NotNull String getVersion() {
        return "4.0";
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
                key = player.getName().toLowerCase() + "_" + key.toLowerCase();
            }

            String value = cache.getOrDefault(key.toLowerCase(Locale.ROOT), "null");
            if (value.equals("null")) return "null";

            double num = Double.parseDouble(value);
            String pattern = "%." + decimals + "f";
            return String.format(Locale.ROOT, pattern, num);
        } catch (Exception e) {
            return "null";
        }
    }
}
