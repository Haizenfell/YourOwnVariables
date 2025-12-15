package yov.listener;

import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import yov.service.VariableService;

public class PreLoginLoadListener implements Listener {

    private final VariableService service;

    public PreLoginLoadListener(VariableService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;

        if (!service.isPreLoginSupported()) {
            return;
        }

        try {
            service.preloadPlayerForLogin(e.getName());
        } catch (Exception ex) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    ChatColor.RED + "Failed to load your data. Try again.");
        }
    }
}
