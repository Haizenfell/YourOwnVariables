package yov.listener;

import me.clip.placeholderapi.events.ExpansionsLoadedEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import yov.YOVPlugin;
import yov.cache.VariableCache;
import yov.placeholder.RoundedPlaceholder;
import yov.placeholder.VarPlaceholder;
import yov.placeholder.VarPlayerKeyPlaceholder;

public class PapiExpansionListener implements Listener {

    private final YOVPlugin plugin;

    public PapiExpansionListener(YOVPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerAll() {
        VariableCache cache = plugin.getVariableCache();

        new VarPlaceholder(cache).register();
        new VarPlayerKeyPlaceholder(cache).register();
        new RoundedPlaceholder(cache).register();
    }

    @EventHandler
    public void onPapiReload(ExpansionsLoadedEvent event) {
        plugin.getLogger().info("[YOV] PlaceholderAPI reloaded — re-registering expansions...");
        registerAll();
        plugin.getLogger().info("[YOV] Re-register completed.");
    }
}
