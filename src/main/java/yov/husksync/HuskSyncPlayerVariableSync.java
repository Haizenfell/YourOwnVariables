package yov.husksync;

import org.bukkit.entity.Player;
import yov.YOVPlugin;
import yov.sync.PlayerVariableSync;

import java.util.Collections;
import java.util.Map;

public class HuskSyncPlayerVariableSync implements PlayerVariableSync {

    private final HuskSyncYovBridge bridge;
    private final boolean connected;

    public HuskSyncPlayerVariableSync(YOVPlugin plugin) {
        this.bridge = new HuskSyncYovBridge(plugin);
        this.connected = bridge.connect();
    }

    @Override
    public boolean connect() {
        return connected;
    }

    @Override
    public boolean isReady() {
        return connected && bridge.isReady();
    }

    @Override
    public void push(Player player, Map<String, String> vars) {
        if (!isReady()) return;
        bridge.push(player, vars);
    }

    @Override
    public Map<String, String> pull(Player player) {
        if (!isReady()) return Collections.emptyMap();
        return bridge.pull(player);
    }
}
