package yov.listener;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class PlayerPreloadListener implements Listener {

    private static final long DEFAULT_TIMEOUT_MILLIS = 5000L;
    private static final long DEFAULT_CLEANUP_DELAY_TICKS = 200L;

    private final YOVPlugin plugin;
    private final VariableService service;
    private final long timeoutMillis;
    private final boolean disconnectOnFailure;
    private final String timeoutMessage;
    private final String failureMessage;
    private final long cleanupDelayTicks;

    public PlayerPreloadListener(YOVPlugin plugin, VariableService service) {
        this.plugin = plugin;
        this.service = service;
        this.timeoutMillis = Math.max(1L, plugin.getConfig()
                .getLong("husksync.login-preload.timeout-millis", DEFAULT_TIMEOUT_MILLIS));
        this.disconnectOnFailure = plugin.getConfig()
                .getBoolean("husksync.login-preload.disconnect-on-failure", true);
        this.timeoutMessage = configuredMessage(
                "husksync.login-preload.timeout-message");
        this.failureMessage = configuredMessage(
                "husksync.login-preload.failure-message");
        this.cleanupDelayTicks = Math.max(1L, plugin.getConfig().getLong(
                "husksync.login-preload.unused-cleanup-delay-ticks",
                DEFAULT_CLEANUP_DELAY_TICKS));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        String playerName = event.getName();
        VariableService.PreloadAttempt attempt = service.preloadCurrentVariables(
                event.getUniqueId(), playerName);

        try {
            attempt.completion().get(timeoutMillis, TimeUnit.MILLISECONDS);
            scheduleUnusedPreloadCleanup(attempt);
        } catch (TimeoutException e) {
            service.cancelPreload(attempt, "YOV preload timed out.");
            plugin.getLogger().warning("Timed out preloading YOV variables for " + playerName + ".");
            disallowIfConfigured(event, timeoutMessage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            service.cancelPreload(attempt, "YOV preload was interrupted.");
            plugin.getLogger().warning("Interrupted while preloading YOV variables for " + playerName + ".");
            disallowIfConfigured(event, failureMessage);
        } catch (Exception e) {
            service.cancelPreload(attempt, "YOV preload failed.");
            plugin.getLogger().warning("Failed to preload YOV variables for " + playerName + ": " + rootMessage(e));
            disallowIfConfigured(event, failureMessage);
        }
    }

    private void disallowIfConfigured(AsyncPlayerPreLoginEvent event, String message) {
        if (!disconnectOnFailure) return;
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text(message));
    }

    private void scheduleUnusedPreloadCleanup(VariableService.PreloadAttempt attempt) {
        Bukkit.getScheduler().runTaskLater(
                plugin, () -> service.cleanupUnusedPreload(attempt), cleanupDelayTicks);
    }

    private String configuredMessage(String path) {
        String message = plugin.getConfig().getString(
                path, "Your data is still loading. Please reconnect.");
        return message != null ? message : "Your data is still loading. Please reconnect.";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }
}
