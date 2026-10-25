package yov.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class YOVPlayerReadyEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Map<String, String> variables;

    public YOVPlayerReadyEvent(@NotNull Player player, @NotNull Map<String, String> variables) {
        this.player = player;
        this.variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    public @NotNull Map<String, String> getVariables() {
        return variables;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
