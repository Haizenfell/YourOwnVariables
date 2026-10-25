package yov.husksync;

import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.adapter.Adaptable;
import net.william278.husksync.data.BukkitData;
import net.william278.husksync.user.BukkitUser;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class YovVariablesData extends BukkitData implements Adaptable {

    private Map<String, String> variables;

    public YovVariablesData() {
        variables = new LinkedHashMap<>();
    }

    public YovVariablesData(@NotNull Map<String, String> variables) {
        setVariables(variables);
    }

    public @NotNull Map<String, String> getVariables() {
        return variables == null ? Collections.emptyMap() : variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = new LinkedHashMap<>();
        if (variables == null) return;

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null) continue;

            String normalized = key.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                this.variables.put(normalized, value);
            }
        }
    }

    @Override
    public void apply(@NotNull BukkitUser user, @NotNull BukkitHuskSync plugin) {
    }
}
