package yov.husksync;

import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.adapter.Adaptable;
import net.william278.husksync.data.BukkitData;
import net.william278.husksync.user.BukkitUser;
import org.jetbrains.annotations.NotNull;
import yov.YOVPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class YovVariablesData extends BukkitData implements Adaptable {

    private Map<String, String> variables;

    public YovVariablesData() {
        this.variables = new LinkedHashMap<>();
    }

    public YovVariablesData(@NotNull Map<String, String> variables) {
        setVariables(variables);
    }

    public @NotNull Map<String, String> getVariables() {
        if (variables == null) return Collections.emptyMap();
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        if (variables == null) {
            this.variables = new LinkedHashMap<>();
        } else {
            this.variables = new LinkedHashMap<>(variables);
        }
    }

    @Override
    public void apply(@NotNull BukkitUser user, @NotNull BukkitHuskSync plugin) {
        YOVPlugin yov = YOVPlugin.getInstance();
        if (yov == null || !yov.isEnabled()) return;
        var svc = yov.getVariableService();
        if (svc == null) return;

        String playerLower = user.getUsername().toLowerCase(java.util.Locale.ROOT);
        Map<String, String> full = new java.util.HashMap<>();
        for (Map.Entry<String, String> e : getVariables().entrySet()) {
            String k = e.getKey();
            if (k == null || k.isEmpty()) continue;
            full.put(playerLower + "_" + k.toLowerCase(java.util.Locale.ROOT), e.getValue());
        }
        svc.replacePlayerVariables(playerLower, full);
    }
}
