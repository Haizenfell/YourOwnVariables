package yov.sync;

import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.Map;

public class NoopPlayerVariableSync implements PlayerVariableSync {

    @Override
    public boolean connect() {
        return false;
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public void push(Player player, Map<String, String> vars) {
    }

    @Override
    public Map<String, String> pull(Player player) {
        return Collections.emptyMap();
    }
}
