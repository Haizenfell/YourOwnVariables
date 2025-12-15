package yov.sync;

import org.bukkit.entity.Player;

import java.util.Map;

public interface PlayerVariableSync {

    boolean connect();

    boolean isReady();

    default boolean isEnabled() {
        return isReady();
    }

    void push(Player player, Map<String, String> vars);

    Map<String, String> pull(Player player);
}
