package yov.api;

import org.bukkit.Bukkit;
import yov.YOVPlugin;
import yov.config.VariableLimits;
import yov.metrics.YOVMetrics;
import yov.service.VariableService;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

public final class YOVApiImpl implements YOVApi {

    private final YOVPlugin plugin;
    private final Supplier<VariableService> serviceSupplier;
    private final Set<TrackedCall<?>> pendingCalls = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingCallCount = new AtomicInteger();
    private volatile boolean closed;

    public YOVApiImpl(YOVPlugin plugin, Supplier<VariableService> serviceSupplier) {
        this.plugin = plugin;
        this.serviceSupplier = serviceSupplier;
    }

    public void shutdown() {
        closed = true;
        IllegalStateException failure = new IllegalStateException("YourOwnVariables is disabled.");
        for (TrackedCall<?> pending : pendingCalls) {
            pending.exposed.completeExceptionally(failure);
            pending.release();
        }
        pendingCalls.clear();
    }

    @Override
    public String getVariable(UUID playerId, String variable) {
        return stableRead(service -> service.getPlayerValue(playerId, variable), null);
    }

    @Override
    public String getVariable(String playerName, String variable) {
        return stableRead(service -> service.getPlayerValue(playerName, variable), null);
    }

    @Override
    public Map<String, String> getVariables(String playerName) {
        return immutableCopy(stableRead(
                service -> service.snapshotPlayerVars(playerName), Map.of()));
    }

    @Override
    public Map<String, String> getVariables(UUID playerId) {
        return immutableCopy(stableRead(
                service -> service.snapshotPlayerVars(playerId), Map.of()));
    }

    @Override
    public CompletableFuture<Map<String, String>> whenPlayerReady(UUID playerId) {
        return callOnMainThread(() -> service().whenPlayerReady(playerId).thenApply(YOVApiImpl::immutableCopy));
    }

    @Override
    public CompletableFuture<Optional<String>> getCurrentVariable(UUID playerId, String variable) {
        return callOnMainThread(() -> service().getCurrentValue(playerId, variable));
    }

    @Override
    public CompletableFuture<Optional<String>> getCurrentVariable(String playerName, String variable) {
        return callOnMainThread(() -> service().getCurrentValue(playerName, variable));
    }

    @Override
    public CompletableFuture<Map<String, String>> getCurrentVariables(UUID playerId) {
        return callOnMainThread(() -> service().getCurrentVariables(playerId).thenApply(YOVApiImpl::immutableCopy));
    }

    @Override
    public CompletableFuture<Map<String, String>> getCurrentVariables(String playerName) {
        return callOnMainThread(() -> service().getCurrentVariables(playerName).thenApply(YOVApiImpl::immutableCopy));
    }

    @Override
    public CompletableFuture<YOVOperationResult> setVariable(UUID playerId, String variable, String value) {
        return callOnMainThread(() -> service().setVariable(playerId, variable, value));
    }

    @Override
    public CompletableFuture<YOVOperationResult> setVariable(String playerName, String variable, String value) {
        return callOnMainThread(() -> service().setVariable(playerName, variable, value));
    }

    @Override
    public CompletableFuture<YOVOperationResult> addVariable(UUID playerId, String variable, BigDecimal amount) {
        return callOnMainThread(() -> service().addVariable(playerId, variable, amount));
    }

    @Override
    public CompletableFuture<YOVOperationResult> addVariable(String playerName, String variable, BigDecimal amount) {
        return callOnMainThread(() -> service().addVariable(playerName, variable, amount));
    }

    @Override
    public CompletableFuture<YOVOperationResult> remVariable(UUID playerId, String variable, BigDecimal amount) {
        return callOnMainThread(() -> service().remVariable(playerId, variable, amount));
    }

    @Override
    public CompletableFuture<YOVOperationResult> remVariable(String playerName, String variable, BigDecimal amount) {
        return callOnMainThread(() -> service().remVariable(playerName, variable, amount));
    }

    @Override
    public CompletableFuture<YOVOperationResult> deleteVariable(UUID playerId, String variable) {
        return callOnMainThread(() -> service().deleteVariable(playerId, variable));
    }

    @Override
    public CompletableFuture<YOVOperationResult> deleteVariable(String playerName, String variable) {
        return callOnMainThread(() -> service().deleteVariable(playerName, variable));
    }

    @Override
    public CompletableFuture<YOVOperationResult> clearVariables(UUID playerId) {
        return callOnMainThread(() -> service().clearPlayerVariables(playerId));
    }

    @Override
    public CompletableFuture<YOVOperationResult> clearVariables(String playerName) {
        return callOnMainThread(() -> service().clearPlayerVariables(playerName));
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isPlayerLoaded(String playerName) {
        return stableRead(service -> service.isPlayerLoaded(playerName), false);
    }

    @Override
    public boolean isPlayerReady(UUID playerId) {
        return stableRead(service -> service.isPlayerReady(playerId), false);
    }

    @Override
    public boolean hasPlayerSnapshot(String playerName) {
        return stableRead(service -> service.hasPlayerSnapshot(playerName), false);
    }

    @Override
    public boolean hasPlayerSnapshot(UUID playerId) {
        return stableRead(service -> service.hasPlayerSnapshot(playerId), false);
    }

    @Override
    public List<String> getKnownVariableNames() {
        return stableRead(VariableService::knownVariableNames, List.of());
    }

    @Override
    public String normalizeVariable(String variable) {
        return stableRead(service -> service.normalizeVariable(variable), null);
    }

    private <T> CompletableFuture<T> callOnMainThread(Supplier<CompletableFuture<T>> operation) {
        if (closed || !plugin.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("YourOwnVariables is disabled."));
        }

        if (Bukkit.isPrimaryThread()) {
            TrackedCall<T> call = newTrackedCall();
            if (call.exposed.isDone()) {
                call.release();
                return call.exposed;
            }
            pipeTracked(getSafely(operation), call);
            return call.exposed;
        }

        TrackedCall<T> call = newTrackedCall();
        if (call.exposed.isDone()) {
            call.release();
            return call.exposed;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (call.exposed.isDone()) {
                    call.release();
                    return;
                }
                pipeTracked(getSafely(operation), call);
            });
        } catch (RuntimeException error) {
            call.exposed.completeExceptionally(error);
            call.release();
        }
        return call.exposed;
    }

    private <T> TrackedCall<T> newTrackedCall() {
        TrackedCall<T> call = new TrackedCall<>();
        VariableLimits limits = plugin.getVariableLimits();
        int maximum = limits != null
                ? limits.maxPendingApiCallsGlobal()
                : VariableLimits.DEFAULT_MAX_PENDING_API_CALLS_GLOBAL;
        int pending = pendingCallCount.incrementAndGet();
        if (pending > maximum) {
            pendingCallCount.decrementAndGet();
            YOVMetrics metrics = plugin.getMetrics();
            if (metrics != null) {
                metrics.apiBackpressureRejected();
            }
            call.released.set(true);
            call.exposed.completeExceptionally(new RejectedExecutionException(
                    "Too many YOV API calls are pending (limit " + maximum + ")."));
            return call;
        }
        pendingCalls.add(call);
        if (closed || !plugin.isEnabled()) {
            call.exposed.completeExceptionally(new IllegalStateException("YourOwnVariables is disabled."));
            call.release();
        }
        return call;
    }

    private <T> void pipeTracked(CompletableFuture<T> source, TrackedCall<T> call) {
        source.whenComplete((value, error) -> {
            if (error != null) call.exposed.completeExceptionally(error);
            else call.exposed.complete(value);
            call.release();
        });
    }

    private <T> CompletableFuture<T> getSafely(Supplier<CompletableFuture<T>> operation) {
        try {
            return operation.get();
        } catch (RuntimeException error) {
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
        return serviceSupplier.get();
    }

    private <T> T stableRead(Function<VariableService, T> reader, T fallback) {
        VariableService selected = serviceOrNull();
        if (selected == null) return fallback;
        T value = reader.apply(selected);
        VariableService latest = serviceOrNull();
        return latest != null && latest != selected ? reader.apply(latest) : value;
    }

    private static Map<String, String> immutableCopy(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    private final class TrackedCall<T> {
        private final CompletableFuture<T> exposed = new CompletableFuture<>();
        private final AtomicBoolean released = new AtomicBoolean();

        private void release() {
            if (!released.compareAndSet(false, true)) return;
            pendingCalls.remove(this);
            pendingCallCount.decrementAndGet();
        }
    }
}
