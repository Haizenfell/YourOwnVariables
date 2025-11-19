package yov.service;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import yov.cache.VariableCache;

import java.io.File;
import java.util.Map;
import java.util.logging.Level;

public class ExportService {

    private final Plugin plugin;
    private final VariableCache cache;
    private final String prefix;

    public ExportService(Plugin plugin, VariableCache cache, String prefix) {
        this.plugin = plugin;
        this.cache = cache;
        this.prefix = prefix;
    }

    public void exportToYamlAsync(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, String> entry : cache.getMap().entrySet()) {
                yaml.set(entry.getKey(), entry.getValue());
            }
            try {
                yaml.save(new File(plugin.getDataFolder(), "variables.yml"));
                sender.sendMessage(prefix + "§aExport complete!");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Error exporting variables to YAML", e);
                sender.sendMessage(prefix + "§cExport error!");
            }
        });
    }
}
