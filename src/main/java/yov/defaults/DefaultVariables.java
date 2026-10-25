package yov.defaults;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DefaultVariables {

    private final YOVPlugin plugin;
    private final VariableService service;
    private FileConfiguration defaults;
    private Map<String, String> cachedDefaults = Map.of();

    public DefaultVariables(YOVPlugin plugin, VariableService service) {
        this.plugin = plugin;
        this.service = service;
        loadDefaults();
        service.setDefaultVariablesSupplier(this::getDefaults);
    }

    private Map<String, String> getDefaults() {
        return cachedDefaults;
    }

    private void rebuildCache() {
        Map<String, String> values = new LinkedHashMap<>();
        if (!defaults.isConfigurationSection("player_variables")) {
            if (defaults.contains("player_variables")) {
                throw new IllegalStateException(
                        "default_variables.yml: player_variables must be a mapping of names to values.");
            }
            cachedDefaults = Map.of();
            return;
        }

        var section = defaults.getConfigurationSection("player_variables");
        if (section == null) {
            cachedDefaults = Map.of();
            return;
        }

        for (String key : section.getKeys(false)) {
            Object configuredValue = section.get(key);
            if (!(configuredValue instanceof String || configuredValue instanceof Number
                    || configuredValue instanceof Boolean)) {
                throw new IllegalStateException(
                        "default_variables.yml: variable '" + key + "' must have a scalar value.");
            }
            String value = String.valueOf(configuredValue);
            String normalized = service.normalizeVariable(key);
            if (normalized == null || value == null) {
                throw new IllegalStateException(
                        "default_variables.yml contains an invalid player variable: " + key);
            }
            values.put(normalized, value);
        }
        service.limits().validateSnapshot(values).ifPresent(violation -> {
            throw new IllegalStateException(
                    "Configured default variables exceed YOV safety limits: " + violation);
        });
        cachedDefaults = values.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(values);
    }

    private void loadDefaults() {
        File file = new File(plugin.getDataFolder(), "default_variables.yml");

        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            try (InputStream in = plugin.getResource("default_variables.yml")) {
                if (in != null) {
                    Files.copy(in, file.toPath());
                } else if (!file.createNewFile()) {
                    Bukkit.getLogger().warning("[YOV] Could not create default_variables.yml");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to create default_variables.yml: " + e.getMessage());
            }
        }

        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Could not parse default_variables.yml; defaults are mandatory.", error);
        }
        defaults = loaded;
        rebuildCache();
    }
}
