package yov.api;

import org.bukkit.Bukkit;
import yov.YOVPlugin;
import yov.service.VariableService;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class YOVApiImpl implements YOVApi {

    private final YOVPlugin plugin;

    public YOVApiImpl(YOVPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getVariable(String playerName, String variable) {
        VariableService service = serviceOrNull();
        return service == null ? null : service.getPlayerValue(playerName, variable);
    }

    @Override
    public Map<String, String> getVariables(String playerName) {
        VariableService service = serviceOrNull();
        return service == null ? Map.of() : immutableCopy(service.snapshotPlayerVars(playerName));
    }

    @Override
    public CompletableFuture<Optional<String>> getCurrentVariable(String playerName, String variable) {
        return getSafely(() -> service().getCurrentValue(playerName, variable));
    }

    @Override
    public CompletableFuture<Map<String, String>> getCurrentVariables(String playerName) {
        return getSafely(() -> service().getCurrentVariables(playerName).thenApply(YOVApiImpl::immutableCopy));
    }

    @Override
    public CompletableFuture<YOVOperationResult> setVariable(String playerName, String variable, String value) {
        return callOnMainThread(() -> service().setVariable(playerName, variable, value));
    }

    @Override
    public CompletableFuture<YOVOperationResult> addVariable(String playerName, String variable, BigDecimal amount) {
        return callOnMainThread(() -> service().addVariable(playerName, variable, amount));
    }

    @Override
    public CompletableFuture<YOVOperationResult> remVariable(String playerName, String variable, BigDecimal amount) {
        return callOnMainThread(() -> service().remVariable(playerName, variable, amount));
    }

    @Override
    public CompletableFuture<YOVOperationResult> deleteVariable(String playerName, String variable) {
        return callOnMainThread(() -> service().deleteVariable(playerName, variable));
    }

    @Override
    public CompletableFuture<YOVOperationResult> clearVariables(String playerName) {
        return callOnMainThread(() -> service().clearPlayerVariables(playerName));
    }

    @Override
    public boolean isPlayerLoaded(String playerName) {
        VariableService service = serviceOrNull();
        return service != null && service.isPlayerLoaded(playerName);
    }

    @Override
    public boolean hasPlayerSnapshot(String playerName) {
        VariableService service = serviceOrNull();
        return service != null && service.hasPlayerSnapshot(playerName);
    }

    @Override
    public List<String> getKnownVariableNames() {
        VariableService service = serviceOrNull();
        return service == null ? List.of() : service.knownVariableNames();
    }

    @Override
    public String normalizeVariable(String variable) {
        VariableService service = serviceOrNull();
        return service == null ? null : service.normalizeVariable(variable);
    }

    private <T> CompletableFuture<T> callOnMainThread(Supplier<CompletableFuture<T>> operation) {
        if (!plugin.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("YourOwnVariables is disabled."));
        }

        if (Bukkit.isPrimaryThread()) {
            return getSafely(operation);
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTask(plugin, () -> getSafely(operation).whenComplete((value, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                } else {
                    result.complete(value);
                }
            }));
        } catch (RuntimeException error) {
            result.completeExceptionally(error);
        }
        return result;
    }

    private <T> CompletableFuture<T> getSafely(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private VariableService service() {
        VariableService service = serviceOrNull();
        if (service == null) {
            throw new IllegalStateException("YourOwnVariables service is not initialized.");
        }
        return service;
    }

    private VariableService serviceOrNull() {
        return plugin.getVariableService();
    }

    private static Map<String, String> immutableCopy(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }
}
