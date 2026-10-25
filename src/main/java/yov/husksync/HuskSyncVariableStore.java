package yov.husksync;

import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.api.BukkitHuskSyncAPI;
import net.william278.husksync.data.Data;
import net.william278.husksync.data.DataHolder;
import net.william278.husksync.data.DataSnapshot;
import net.william278.husksync.data.Identifier;
import net.william278.husksync.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import yov.YOVPlugin;
import yov.config.VariableLimits;
import yov.metrics.YOVMetrics;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;

public class HuskSyncVariableStore {

    public record OfflineEditResult(boolean success, String message, Map<String, String> variables) {
        public static OfflineEditResult success(Map<String, String> variables) {
            return new OfflineEditResult(true, "ok", variables);
        }

        public static OfflineEditResult fail(String message) {
            return new OfflineEditResult(false, message, Collections.emptyMap());
        }
    }

    private final YOVPlugin plugin;
    private final VariableLimits limits;
    private final YOVMetrics metrics;
    private final long shutdownWaitMillis;
    private final long userLookupTimeoutMillis;
    private final long dataReadTimeoutMillis;
    private final Object lifecycleLock = new Object();
    private final Map<UUID, CompletableFuture<Void>> offlineEditQueues = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<OfflineEditResult>> pendingOfflineEdits = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> pendingOfflineByPlayer = new HashMap<>();
    private int pendingOfflineCount;
    private CompletableFuture<Void> offlineEditReservationTail = CompletableFuture.completedFuture(null);

    private volatile BukkitHuskSync huskSync;
    private volatile BukkitHuskSyncAPI api;
    private volatile Identifier identifier;
    private volatile YovRedisDeltaBus deltaBus;
    private volatile boolean acceptingOfflineEdits;
    private volatile boolean closed;

    public HuskSyncVariableStore(YOVPlugin plugin) {
        this(plugin, VariableLimits.from(plugin.getConfig()), new YOVMetrics());
    }

    public HuskSyncVariableStore(YOVPlugin plugin, VariableLimits limits, YOVMetrics metrics) {
        this.plugin = plugin;
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
        this.metrics = java.util.Objects.requireNonNull(metrics, "metrics");
        this.shutdownWaitMillis = Math.max(0L, plugin.getConfig()
                .getLong("husksync.shutdown-wait-millis", 5000L));
        this.userLookupTimeoutMillis = Math.max(1L, plugin.getConfig()
                .getLong("husksync.offline-user-lookup-timeout-millis", 5000L));
        this.dataReadTimeoutMillis = Math.max(1L, plugin.getConfig()
                .getLong("husksync.offline-data-read-timeout-millis", 5000L));
    }

    public boolean connect() {
        Plugin installed = Bukkit.getPluginManager().getPlugin("HuskSync");
        if (!(installed instanceof BukkitHuskSync bukkitHuskSync) || !installed.isEnabled()) {
            huskSync = null;
            api = null;
            identifier = null;
            acceptingOfflineEdits = false;
            return false;
        }

        try {
            BukkitHuskSyncAPI connectedApi = BukkitHuskSyncAPI.getInstance();
            Identifier connectedIdentifier = Identifier.from("yov", "variables");
            var registeredSerializer = connectedApi.getDataSerializer(connectedIdentifier);
            if (registeredSerializer.isEmpty()) {
                connectedApi.registerDataSerializer(
                        connectedIdentifier, new YovVariablesSerializer(bukkitHuskSync));
                registeredSerializer = connectedApi.getDataSerializer(connectedIdentifier);
            }
            if (registeredSerializer.isEmpty()) {
                throw new IllegalStateException("HuskSync did not register the YOV data serializer.");
            }
            if (registeredSerializer.get().getClass() != YovVariablesSerializer.class) {
                throw new IllegalStateException(
                        "A different serializer is already registered for yov:variables.");
            }

            YovRedisDeltaBus connectedDeltaBus = new YovRedisDeltaBus(
                    plugin, bukkitHuskSync, metrics);
            connectedDeltaBus.initialize();

            huskSync = bukkitHuskSync;
            api = connectedApi;
            identifier = connectedIdentifier;
            deltaBus = connectedDeltaBus;
            acceptingOfflineEdits = true;
            closed = false;
            return true;
        } catch (Exception | LinkageError error) {
            YovRedisDeltaBus failedBus = deltaBus;
            if (failedBus != null) failedBus.close();
            huskSync = null;
            api = null;
            identifier = null;
            deltaBus = null;
            acceptingOfflineEdits = false;
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to connect YOV storage and its delta channel to HuskSync.", error);
            return false;
        }
    }

    public void activateOnlineEdits(BiFunction<UUID, YovDeltaOperation,
            CompletableFuture<OfflineEditResult>> handler) {
        if (handler == null) throw new IllegalArgumentException("Online edit handler must not be null.");
        YovRedisDeltaBus current = deltaBus;
        if (current == null || closed) {
            throw new IllegalStateException("YOV storage is not ready for cross-server edits.");
        }
        current.activate(handler::apply);
    }

    public boolean transferReloadStateTo(HuskSyncVariableStore replacement) {
        if (replacement == null || replacement == this) return replacement == this;
        YovRedisDeltaBus current = deltaBus;
        YovRedisDeltaBus target = replacement.deltaBus;
        return current != null && target != null && current.transferReceiptsTo(target);
    }

    public void close() {
        closeInternal();
    }

    public CompletableFuture<Boolean> prepareCloseForReload() {
        CompletableFuture<?>[] pending;
        long waitMillis;
        synchronized (lifecycleLock) {
            if (closed) return CompletableFuture.completedFuture(true);
            acceptingOfflineEdits = false;
            YovRedisDeltaBus currentBus = deltaBus;
            if (currentBus != null) currentBus.beginDrain();
            pending = closeWaiters();
            waitMillis = shutdownWaitMillis;
        }

        if (pending.length == 0 || allDone(pending)) {
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> drained = CompletableFuture.allOf(pending)
                .handle((ignored, error) -> allDone(pending));
        CompletableFuture<Boolean> timeout = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(waitMillis, TimeUnit.MILLISECONDS)
                .execute(() -> timeout.complete(false));
        return drained.applyToEither(timeout, result -> result).thenApply(success -> {
            if (success) return true;

            plugin.getLogger().warning("Timed out waiting for " + pendingOfflineEdits.size()
                    + " pending HuskSync edit(s); live reload was aborted.");
            synchronized (lifecycleLock) {
                if (!closed) {
                    acceptingOfflineEdits = true;
                    YovRedisDeltaBus currentBus = deltaBus;
                    if (currentBus != null) currentBus.abortDrain();
                }
            }
            return false;
        });
    }

    public boolean finishCloseForReload() {
        synchronized (lifecycleLock) {
            if (closed) return true;
            if (acceptingOfflineEdits
                    || !offlineEditReservationTail.isDone()
                    || pendingOfflineEdits.stream().anyMatch(operation -> !operation.isDone())
                    || hasPendingDeltaOperations()) {
                return false;
            }
            closed = true;
            acceptingOfflineEdits = false;
        }
        releaseResources();
        return true;
    }

    public void abortCloseForReload() {
        synchronized (lifecycleLock) {
            if (!closed) {
                acceptingOfflineEdits = true;
                YovRedisDeltaBus currentBus = deltaBus;
                if (currentBus != null) currentBus.abortDrain();
            }
        }
    }

    private boolean closeInternal() {
        CompletableFuture<?>[] pending;
        synchronized (lifecycleLock) {
            if (closed) return true;
            acceptingOfflineEdits = false;
            YovRedisDeltaBus currentBus = deltaBus;
            if (currentBus != null) currentBus.beginDrain();
            pending = closeWaiters();
        }
        boolean drained = true;
        if (pending.length > 0) {
            long waitMillis = shutdownWaitMillis;
            try {
                CompletableFuture.allOf(pending).get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                drained = false;
                plugin.getLogger().warning("Timed out waiting for " + pendingOfflineEdits.size()
                        + " pending HuskSync edit(s) while closing YOV storage.");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                drained = false;
            } catch (Exception error) {
                drained = allDone(pending);
            }
        }

        synchronized (lifecycleLock) {
            closed = true;
            acceptingOfflineEdits = false;
        }
        releaseResources();
        return drained;
    }

    private void releaseResources() {
        for (CompletableFuture<OfflineEditResult> operation : pendingOfflineEdits) {
            operation.complete(OfflineEditResult.fail(
                    "YOV storage closed before the edit was confirmed; the outcome is unknown. "
                            + "Do not automatically retry a numeric mutation."));
        }
        pendingOfflineEdits.clear();
        for (CompletableFuture<Void> tail : offlineEditQueues.values()) tail.cancel(false);
        offlineEditQueues.clear();
        offlineEditReservationTail.cancel(false);
        offlineEditReservationTail = CompletableFuture.completedFuture(null);
        YovRedisDeltaBus currentBus = deltaBus;
        deltaBus = null;
        if (currentBus != null) currentBus.close();
        huskSync = null;
        api = null;
        identifier = null;
    }

    public boolean isReady() {
        BukkitHuskSync connectedHuskSync = huskSync;
        return !closed && connectedHuskSync != null && connectedHuskSync.isEnabled()
                && api != null && identifier != null && deltaBus != null;
    }

    public BukkitHuskSyncAPI api() {
        return api;
    }

    public Identifier identifier() {
        return identifier;
    }

    public void unlockPlayer(UUID playerId) {
        BukkitHuskSync currentHuskSync = huskSync;
        if (currentHuskSync != null && playerId != null) {
            currentHuskSync.unlockPlayer(playerId);
        }
    }

    public Map<String, String> readVariables(DataHolder holder) {
        return readVariables(holder, identifier);
    }

    public void writeVariables(DataHolder holder, Map<String, String> variables) {
        Identifier currentIdentifier = identifier;
        if (holder == null || currentIdentifier == null) return;
        holder.setData(currentIdentifier, new YovVariablesData(normalize(variables)));
    }

    public void pushOnline(Player player, Map<String, String> variables) {
        if (player == null) throw new IllegalArgumentException("Player must not be null.");
        pushOnline(player.getUniqueId(), variables);
    }

    public void pushOnline(UUID playerId, Map<String, String> variables) {
        if (!isReady()) throw new IllegalStateException("HuskSync storage is not ready.");
        if (playerId == null) throw new IllegalArgumentException("Player UUID must not be null.");

        BukkitHuskSyncAPI currentApi = api;
        Identifier currentIdentifier = identifier;
        var user = currentApi.getOnlineUser(playerId).orElseThrow(() ->
                new IllegalStateException("Player is no longer online in HuskSync."));
        user.getCustomDataStore().put(
                currentIdentifier, new YovVariablesData(normalize(variables)));
    }

    public Map<String, String> readOnline(Player player) {
        if (!isReady() || player == null) return Collections.emptyMap();
        return readOnline(player.getUniqueId());
    }

    public Map<String, String> readOnline(UUID playerId) {
        if (!isReady() || playerId == null) return Collections.emptyMap();
        BukkitHuskSyncAPI currentApi = api;
        Identifier currentIdentifier = identifier;
        return currentApi.getOnlineUser(playerId)
                .map(user -> readVariables(user, currentIdentifier))
                .orElseGet(Collections::emptyMap);
    }

    public CompletableFuture<Map<String, String>> pullCurrent(String playerName) {
        if (!isReady() || playerName == null || playerName.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("HuskSync is not ready."));
        }

        return pullCurrentStrict(playerName);
    }

    public CompletableFuture<Map<String, String>> pullCurrent(UUID playerId) {
        if (!isReady() || playerId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("HuskSync is not ready."));
        }
        return pullCurrentStrict(playerId);
    }

    public CompletableFuture<Map<String, String>> pullCurrentStrict(String playerName) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("HuskSync is not ready."));
        }
        if (playerName == null || playerName.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player name must not be blank."));
        }

        BukkitHuskSyncAPI currentApi = api;
        Identifier currentIdentifier = identifier;
        long lookupTimeoutMillis = userLookupTimeoutMillis();
        long dataReadTimeoutMillis = dataReadTimeoutMillis();
        return awaitOfflineEditReservations()
                .thenCompose(ignored -> currentApi.getUser(playerName)
                        .orTimeout(lookupTimeoutMillis, TimeUnit.MILLISECONDS))
                .thenCompose(optionalUser -> optionalUser
                        .map(user -> awaitQueue(offlineEditQueues, user.getUuid())
                                .thenCompose(ignored -> currentApi.getCurrentData(user)
                                        .orTimeout(dataReadTimeoutMillis, TimeUnit.MILLISECONDS))
                                .thenApply(snapshot -> readOptionalSnapshot(snapshot, currentIdentifier)))
                        .orElseGet(() -> CompletableFuture.failedFuture(
                                new IllegalArgumentException(
                                        "Player '" + playerName + "' was not found in HuskSync."))));
    }

    public CompletableFuture<Map<String, String>> pullCurrentStrict(UUID playerId) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("HuskSync is not ready."));
        }
        if (playerId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player UUID must not be null."));
        }

        BukkitHuskSyncAPI currentApi = api;
        Identifier currentIdentifier = identifier;
        long lookupTimeoutMillis = userLookupTimeoutMillis();
        long dataReadTimeoutMillis = dataReadTimeoutMillis();
        return awaitOfflineEditReservations()
                .thenCompose(ignored -> awaitQueue(offlineEditQueues, playerId))
                .thenCompose(ignored -> currentApi.getUser(playerId)
                        .orTimeout(lookupTimeoutMillis, TimeUnit.MILLISECONDS))
                .thenCompose(optionalUser -> optionalUser
                        .map(user -> currentApi.getCurrentData(user)
                                .orTimeout(dataReadTimeoutMillis, TimeUnit.MILLISECONDS)
                                .thenApply(snapshot -> readOptionalSnapshot(snapshot, currentIdentifier)))
                        .orElseGet(() -> CompletableFuture.failedFuture(
                                new IllegalArgumentException(
                                        "Player '" + playerId + "' was not found in HuskSync."))));
    }

    public CompletableFuture<Map<String, String>> pullCurrentStrict(UUID playerId, String playerName) {
        if (!isReady()) {
            return CompletableFuture.failedFuture(new IllegalStateException("HuskSync is not ready."));
        }
        if (playerId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player UUID must not be null."));
        }

        BukkitHuskSyncAPI currentApi = api;
        Identifier currentIdentifier = identifier;
        long lookupTimeoutMillis = userLookupTimeoutMillis();
        long dataReadTimeoutMillis = dataReadTimeoutMillis();
        return awaitOfflineEditReservations()
                .thenCompose(ignored -> awaitQueue(offlineEditQueues, playerId))
                .thenCompose(ignored -> pullCurrentStrict(
                        currentApi, currentIdentifier,
                        currentApi.getUser(playerId)
                                .orTimeout(lookupTimeoutMillis, TimeUnit.MILLISECONDS),
                        dataReadTimeoutMillis));
    }

    public CompletableFuture<OfflineEditResult> editOffline(String playerName,
                                                            YovDeltaOperation operation,
                                                            Function<Map<String, String>, OfflineEditResult> editor) {
        if (playerName == null || playerName.isBlank() || operation == null || editor == null) {
            return CompletableFuture.completedFuture(OfflineEditResult.fail("HuskSync is not ready."));
        }

        synchronized (lifecycleLock) {
            if (!isReady() || !acceptingOfflineEdits) {
                return CompletableFuture.completedFuture(OfflineEditResult.fail("HuskSync is not ready."));
            }
            OfflineEditPermit permit = reserveGlobalOfflinePermit();
            if (permit == null) return globalBackpressureFailure();
            String requestedName = playerName;
            BukkitHuskSyncAPI currentApi = api;
            BukkitHuskSync currentHuskSync = huskSync;
            Identifier currentIdentifier = identifier;
            long lookupTimeoutMillis = userLookupTimeoutMillis();
            long dataReadTimeoutMillis = dataReadTimeoutMillis();
            return trackOfflineEdit(reserveNameEdit(
                    () -> currentApi.getUser(playerName)
                            .orTimeout(lookupTimeoutMillis, TimeUnit.MILLISECONDS),
                    user -> {
                        if (!reservePlayerOfflinePermit(permit, user.getUuid())) {
                            return playerBackpressureFailure(user.getUuid());
                        }
                        return enqueueOfflineEdit(
                                offlineEditQueues,
                                user.getUuid(),
                                () -> editCurrentData(
                                        user, currentApi, currentHuskSync, currentIdentifier,
                                        dataReadTimeoutMillis, operation, editor));
                    },
                    "Player '" + requestedName + "' was not found in HuskSync.")
                    .exceptionally(error -> {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to edit HuskSync data for " + requestedName + ".", error);
                        return OfflineEditResult.fail("Failed to edit HuskSync data. See console.");
                    }), permit);
        }
    }

    public CompletableFuture<OfflineEditResult> editOffline(UUID playerId,
                                                            YovDeltaOperation operation,
                                                            Function<Map<String, String>, OfflineEditResult> editor) {
        if (playerId == null || operation == null || editor == null) {
            return CompletableFuture.completedFuture(OfflineEditResult.fail("HuskSync is not ready."));
        }

        synchronized (lifecycleLock) {
            if (!isReady() || !acceptingOfflineEdits) {
                return CompletableFuture.completedFuture(OfflineEditResult.fail("HuskSync is not ready."));
            }
            OfflineEditPermit permit = reserveGlobalOfflinePermit();
            if (permit == null) return globalBackpressureFailure();
            if (!reservePlayerOfflinePermit(permit, playerId)) {
                releaseOfflinePermit(permit);
                return playerBackpressureFailure(playerId);
            }
            BukkitHuskSyncAPI currentApi = api;
            BukkitHuskSync currentHuskSync = huskSync;
            Identifier currentIdentifier = identifier;
            long lookupTimeoutMillis = userLookupTimeoutMillis();
            long dataReadTimeoutMillis = dataReadTimeoutMillis();
            return trackOfflineEdit(reserveUuidEdit(playerId, () ->
                    currentApi.getUser(playerId)
                            .orTimeout(lookupTimeoutMillis, TimeUnit.MILLISECONDS)
                            .thenCompose(optionalUser -> optionalUser
                                    .map(user -> editCurrentData(
                                            user, currentApi, currentHuskSync, currentIdentifier,
                                            dataReadTimeoutMillis, operation, editor))
                                    .orElseGet(() -> CompletableFuture.completedFuture(OfflineEditResult.fail(
                                            "Player '" + playerId + "' was not found in HuskSync.")))))
                    .exceptionally(error -> {
                        plugin.getLogger().log(Level.WARNING,
                                "Failed to edit HuskSync data for " + playerId + ".", error);
                        return OfflineEditResult.fail("Failed to edit HuskSync data. See console.");
                    }), permit);
        }
    }

    private CompletableFuture<OfflineEditResult> trackOfflineEdit(
            CompletableFuture<OfflineEditResult> operation,
            OfflineEditPermit permit) {
        pendingOfflineEdits.add(operation);
        metrics.mutationAccepted(true);
        operation.whenComplete((result, error) -> {
            pendingOfflineEdits.remove(operation);
            releaseOfflinePermit(permit);
            metrics.mutationCompleted(true,
                    error == null && result != null && result.success());
        });
        return operation.thenApply(result -> result);
    }

    private OfflineEditPermit reserveGlobalOfflinePermit() {
        if (pendingOfflineCount >= limits.maxPendingOfflineGlobal()) return null;
        pendingOfflineCount++;
        return new OfflineEditPermit();
    }

    private boolean reservePlayerOfflinePermit(OfflineEditPermit permit, UUID playerId) {
        synchronized (lifecycleLock) {
            if (permit == null || permit.released || permit.playerId != null || playerId == null) {
                return false;
            }
            int pending = pendingOfflineByPlayer.getOrDefault(playerId, 0);
            if (pending >= limits.maxPendingOfflinePerPlayer()) return false;
            pendingOfflineByPlayer.put(playerId, pending + 1);
            permit.playerId = playerId;
            return true;
        }
    }

    private void releaseOfflinePermit(OfflineEditPermit permit) {
        if (permit == null) return;
        synchronized (lifecycleLock) {
            if (permit.released) return;
            permit.released = true;
            pendingOfflineCount = Math.max(0, pendingOfflineCount - 1);
            UUID playerId = permit.playerId;
            if (playerId == null) return;
            int remaining = pendingOfflineByPlayer.getOrDefault(playerId, 0) - 1;
            if (remaining <= 0) pendingOfflineByPlayer.remove(playerId);
            else pendingOfflineByPlayer.put(playerId, remaining);
        }
    }

    private CompletableFuture<OfflineEditResult> globalBackpressureFailure() {
        metrics.mutationRejected(YOVMetrics.RejectReason.BACKPRESSURE);
        return CompletableFuture.completedFuture(OfflineEditResult.fail(
                "Too many offline variable mutations are pending (limit "
                        + limits.maxPendingOfflineGlobal() + ")."));
    }

    private CompletableFuture<OfflineEditResult> playerBackpressureFailure(UUID playerId) {
        metrics.mutationRejected(YOVMetrics.RejectReason.BACKPRESSURE);
        return CompletableFuture.completedFuture(OfflineEditResult.fail(
                "Too many offline variable mutations are pending for player '"
                        + playerId + "' (limit " + limits.maxPendingOfflinePerPlayer() + ")."));
    }

    private CompletableFuture<OfflineEditResult> reserveUuidEdit(
            UUID playerId, Supplier<CompletableFuture<OfflineEditResult>> operationSupplier) {
        CompletableFuture<OfflineEditResult> exposed = new CompletableFuture<>();
        CompletableFuture<Void> previous = normalized(offlineEditReservationTail);
        CompletableFuture<Void> reservation = previous.thenRun(() -> {
            if (closed) {
                exposed.complete(OfflineEditResult.fail(
                        "YOV storage was closed before the edit started."));
                return;
            }
            pipe(enqueueOfflineEdit(offlineEditQueues, playerId, operationSupplier), exposed);
        });
        offlineEditReservationTail = reservation.handle((ignored, error) -> {
            if (error != null) exposed.completeExceptionally(error);
            return null;
        });
        return exposed;
    }

    private CompletableFuture<OfflineEditResult> reserveNameEdit(
            Supplier<CompletableFuture<Optional<User>>> userLookup,
            Function<User, CompletableFuture<OfflineEditResult>> operationFactory,
            String missingUserMessage) {
        CompletableFuture<OfflineEditResult> exposed = new CompletableFuture<>();
        final CompletableFuture<Optional<User>> lookup;
        try {
            lookup = userLookup.get();
        } catch (RuntimeException error) {
            exposed.completeExceptionally(error);
            return exposed;
        }
        CompletableFuture<Void> previous = normalized(offlineEditReservationTail);
        CompletableFuture<Void> reservation = previous.thenCompose(ignored -> {
            if (closed) {
                exposed.complete(OfflineEditResult.fail(
                        "YOV storage was closed before the edit started."));
                return CompletableFuture.completedFuture(null);
            }
            return lookup.handle((optionalUser, error) -> {
                if (error != null) {
                    exposed.completeExceptionally(error);
                } else if (closed) {
                    exposed.complete(OfflineEditResult.fail(
                            "YOV storage was closed before the edit started."));
                } else if (optionalUser == null || optionalUser.isEmpty()) {
                    exposed.complete(OfflineEditResult.fail(missingUserMessage));
                } else {
                    try {
                        pipe(operationFactory.apply(optionalUser.get()), exposed);
                    } catch (RuntimeException operationError) {
                        exposed.completeExceptionally(operationError);
                    }
                }
                return null;
            });
        });
        offlineEditReservationTail = reservation.handle((ignored, error) -> {
            if (error != null) exposed.completeExceptionally(error);
            return null;
        });
        return exposed;
    }

    private void pipe(CompletableFuture<OfflineEditResult> source,
                      CompletableFuture<OfflineEditResult> target) {
        source.whenComplete((result, error) -> {
            if (error != null) target.completeExceptionally(error);
            else target.complete(result);
        });
    }

    private CompletableFuture<Void> awaitOfflineEditReservations() {
        synchronized (lifecycleLock) {
            return normalized(offlineEditReservationTail);
        }
    }

    private CompletableFuture<Void> normalized(CompletableFuture<Void> future) {
        return future.handle((result, error) -> null);
    }

    private boolean allDone(CompletableFuture<?>[] futures) {
        for (CompletableFuture<?> future : futures) {
            if (!future.isDone()) return false;
        }
        return true;
    }

    private CompletableFuture<?>[] closeWaiters() {
        List<CompletableFuture<?>> waiters = new ArrayList<>(pendingOfflineEdits);
        waiters.add(offlineEditReservationTail);
        YovRedisDeltaBus currentBus = deltaBus;
        if (currentBus != null) Collections.addAll(waiters, currentBus.pendingOperations());
        return waiters.toArray(CompletableFuture[]::new);
    }

    private boolean hasPendingDeltaOperations() {
        YovRedisDeltaBus currentBus = deltaBus;
        if (currentBus == null) return false;
        for (CompletableFuture<?> operation : currentBus.pendingOperations()) {
            if (!operation.isDone()) return true;
        }
        return false;
    }

    private <K> CompletableFuture<Void> awaitQueue(Map<K, CompletableFuture<Void>> queues, K key) {
        CompletableFuture<Void> tail = queues.get(key);
        return tail == null
                ? CompletableFuture.completedFuture(null)
                : tail.handle((result, error) -> null);
    }

    private CompletableFuture<Map<String, String>> pullCurrentStrict(
            BukkitHuskSyncAPI currentApi, Identifier currentIdentifier,
            CompletableFuture<Optional<User>> userFuture,
            long dataReadTimeoutMillis) {
        return userFuture.thenCompose(optionalUser -> optionalUser
                .map(user -> currentApi.getCurrentData(user)
                        .orTimeout(dataReadTimeoutMillis, TimeUnit.MILLISECONDS)
                        .thenApply(snapshot -> readOptionalSnapshot(snapshot, currentIdentifier)))
                .orElseGet(() -> CompletableFuture.completedFuture(Collections.emptyMap())));
    }

    private <K> CompletableFuture<OfflineEditResult> enqueueOfflineEdit(
            Map<K, CompletableFuture<Void>> queues,
            K key,
            Supplier<CompletableFuture<OfflineEditResult>> operationSupplier) {
        AtomicReference<CompletableFuture<OfflineEditResult>> operation = new AtomicReference<>();
        CompletableFuture<Void> queueTail = queues.compute(key, (ignored, previousTail) -> {
            CompletableFuture<Void> ready = previousTail == null
                    ? CompletableFuture.completedFuture(null)
                    : previousTail.handle((result, error) -> null);
            CompletableFuture<OfflineEditResult> queued = ready.thenCompose(ignoredResult -> operationSupplier.get());
            operation.set(queued);
            return queued.handle((result, error) -> null);
        });
        queueTail.whenComplete((ignored, error) -> queues.remove(key, queueTail));
        return operation.get();
    }

    private CompletableFuture<OfflineEditResult> editCurrentData(
            User user, BukkitHuskSyncAPI currentApi, BukkitHuskSync currentHuskSync,
            Identifier currentIdentifier, long dataReadTimeoutMillis,
            YovDeltaOperation operation,
            Function<Map<String, String>, OfflineEditResult> editor) {
        if (closed) {
            return CompletableFuture.completedFuture(
                    OfflineEditResult.fail("YOV storage was closed before the edit started."));
        }
        YovRedisDeltaBus currentBus = deltaBus;
        if (currentBus == null) {
            return CompletableFuture.completedFuture(
                    OfflineEditResult.fail("YOV cross-server edit routing is unavailable."));
        }

        return currentBus.readNetworkState(user.getUuid()).thenCompose(state -> {
            if (state.ownerServer().isPresent()) {
                return currentBus.request(
                        state.ownerServer().get(), user.getUuid(), operation);
            }
            if (state.serverSwitchInProgress()) {
                return CompletableFuture.completedFuture(serverSwitchFailure());
            }
            return readLatestDatabaseSnapshot(currentHuskSync, currentApi, user, dataReadTimeoutMillis)
                    .thenCompose(optionalSnapshot -> editConfirmedOffline(
                            user, currentApi, currentHuskSync, currentIdentifier,
                            operation, editor, optionalSnapshot, 0));
        });
    }

    private CompletableFuture<Optional<DataSnapshot.Unpacked>> readLatestDatabaseSnapshot(
            BukkitHuskSync currentHuskSync, BukkitHuskSyncAPI currentApi,
            User user, long dataReadTimeoutMillis) {
        return currentHuskSync.supplyAsync(() -> currentHuskSync.getDatabase()
                        .getLatestSnapshot(user)
                        .map(currentApi::unpackSnapshot))
                .orTimeout(dataReadTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    private CompletableFuture<OfflineEditResult> editConfirmedOffline(
            User user, BukkitHuskSyncAPI currentApi, BukkitHuskSync currentHuskSync,
            Identifier currentIdentifier, YovDeltaOperation operation,
            Function<Map<String, String>, OfflineEditResult> editor,
            Optional<DataSnapshot.Unpacked> optionalSnapshot,
            int timestampWaits) {
        if (closed) {
            return CompletableFuture.completedFuture(
                    OfflineEditResult.fail("YOV storage was closed before the edit completed."));
        }
        YovRedisDeltaBus currentBus = deltaBus;
        if (currentBus == null) {
            return CompletableFuture.completedFuture(
                    OfflineEditResult.fail("YOV cross-server edit routing is unavailable."));
        }

        return currentBus.readNetworkState(user.getUuid()).thenCompose(state -> {
            if (state.ownerServer().isPresent()) {
                return currentBus.request(
                        state.ownerServer().get(), user.getUuid(), operation);
            }
            if (state.serverSwitchInProgress()) {
                return CompletableFuture.completedFuture(serverSwitchFailure());
            }
            if (optionalSnapshot.isEmpty()) {
                return CompletableFuture.completedFuture(OfflineEditResult.fail(
                        "Player '" + user.getName() + "' has no HuskSync data."));
            }

            DataSnapshot.Unpacked snapshot = optionalSnapshot.get();
            long waitMillis = (snapshot.getTimestamp().toEpochSecond() + 1L) * 1000L
                    - System.currentTimeMillis();
            if (waitMillis > 0L) {
                if (waitMillis > 5000L || timestampWaits >= 2) {
                    return CompletableFuture.completedFuture(OfflineEditResult.fail(
                            "The snapshot timestamp is ahead of the local clock or player "
                                    + "data keeps changing; the edit was not applied. Retry later."));
                }
                return CompletableFuture.runAsync(() -> { },
                                CompletableFuture.delayedExecutor(waitMillis, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> readLatestDatabaseSnapshot(
                                currentHuskSync, currentApi, user, dataReadTimeoutMillis))
                        .thenCompose(latest -> editConfirmedOffline(
                                user, currentApi, currentHuskSync, currentIdentifier,
                                operation, editor, latest, timestampWaits + 1));
            }
            Map<String, String> variables = new LinkedHashMap<>(
                    readVariables(snapshot, currentIdentifier));
            OfflineEditResult result = editor.apply(variables);
            if (!result.success()) return CompletableFuture.completedFuture(result);

            Map<Identifier, Data> copiedData = new HashMap<>(snapshot.getData());
            copiedData.put(currentIdentifier, new YovVariablesData(normalize(variables)));
            DataSnapshot.Packed updatedSnapshot = currentApi.snapshotBuilder()
                    .timestamp(OffsetDateTime.now())
                    .saveCause(DataSnapshot.SaveCause.API)
                    .data(copiedData)
                    .buildAndPack();

            return saveOfflineSnapshot(
                    currentHuskSync, user, snapshot.getId(), updatedSnapshot)
                    .thenCompose(outcome -> {
                        if (outcome.ownerServer() != null) {
                            return currentBus.request(
                                    outcome.ownerServer(), user.getUuid(), operation);
                        }
                        if (!outcome.saved()) {
                            return CompletableFuture.completedFuture(
                                    OfflineEditResult.fail(outcome.failureMessage()));
                        }
                        return CompletableFuture.completedFuture(new OfflineEditResult(
                                true, result.message(), variables));
                    });
        });
    }

    private long userLookupTimeoutMillis() {
        return userLookupTimeoutMillis;
    }

    private long dataReadTimeoutMillis() {
        return dataReadTimeoutMillis;
    }

    private CompletableFuture<OfflineSaveOutcome> saveOfflineSnapshot(
            BukkitHuskSync currentHuskSync, User user, UUID baseSnapshotId,
            DataSnapshot.Packed snapshot) {
        return currentHuskSync.supplyAsync(() -> {
            if (closed) return OfflineSaveOutcome.failure("YOV storage is closed.");
            YovRedisDeltaBus currentBus = deltaBus;
            if (currentBus == null) {
                return OfflineSaveOutcome.failure("YOV Redis checkout reader is unavailable.");
            }

            YovRedisDeltaBus.PlayerNetworkState stateBeforeSave =
                    currentBus.readNetworkStateNow(user.getUuid());
            if (stateBeforeSave.ownerServer().isPresent()) {
                return OfflineSaveOutcome.checkedOut(stateBeforeSave.ownerServer().get());
            }
            if (stateBeforeSave.serverSwitchInProgress()) {
                return OfflineSaveOutcome.failure(serverSwitchFailure().message());
            }

            Optional<DataSnapshot.Packed> latestSnapshot =
                    currentHuskSync.getDatabase().getLatestSnapshot(user);
            if (latestSnapshot.isEmpty()
                    || !latestSnapshot.get().getId().equals(baseSnapshotId)) {
                return OfflineSaveOutcome.failure(
                        "Player data changed while the offline edit was being prepared; "
                                + "the edit was not applied. Retry the mutation.");
            }

            try {
                currentHuskSync.getDatabase().addSnapshotWithoutRotation(user, snapshot);
                if (currentHuskSync.getDatabase().getSnapshot(user, snapshot.getId())
                        .filter(stored -> stored.getId().equals(snapshot.getId())).isEmpty()) {
                    throw new IllegalStateException(
                            "HuskSync did not confirm the inserted snapshot " + snapshot.getId());
                }
            } catch (RuntimeException databaseError) {
                plugin.getLogger().log(Level.WARNING,
                        "The offline YOV database snapshot write could not be confirmed for " + user.getUuid()
                                + "; attempting to remove the provisional snapshot.",
                        databaseError);
                if (!rollbackProvisionalSnapshot(currentHuskSync, user, snapshot)) {
                    return OfflineSaveOutcome.failure(
                            "The database snapshot write failed and YOV could not confirm that "
                                    + "the provisional snapshot was removed. The edit outcome is "
                                    + "unknown; do not automatically retry a numeric mutation.");
                }
                return OfflineSaveOutcome.failure(
                        "The database snapshot write failed and the provisional snapshot was "
                                + "rolled back. The edit outcome is unknown; do not automatically "
                                + "retry a numeric mutation.");
            }

            try {
                if (!currentBus.evictOfflineSnapshotCacheNow(user.getUuid())) {
                    if (!rollbackProvisionalSnapshot(currentHuskSync, user, snapshot)) {
                        return provisionalRollbackFailure();
                    }
                    return OfflineSaveOutcome.failure(
                            "Player connected or began switching servers during the edit; "
                                    + "the provisional snapshot was rolled back. The edit outcome "
                                    + "is unknown; do not automatically retry a numeric mutation.");
                }
            } catch (RuntimeException cacheError) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not confirm offline HuskSync cache eviction for " + user.getUuid(),
                        cacheError);
                if (!rollbackProvisionalSnapshot(currentHuskSync, user, snapshot)) {
                    return provisionalRollbackFailure();
                }
                return OfflineSaveOutcome.failure(
                        "Could not confirm offline cache eviction; the provisional snapshot "
                                + "was rolled back. The edit outcome is unknown; do not "
                                + "automatically retry a numeric mutation.");
            }

            final YovRedisDeltaBus.PlayerNetworkState stateAfterSave;
            try {
                stateAfterSave = currentBus.readNetworkStateNow(user.getUuid());
            } catch (RuntimeException redisError) {
                if (!rollbackProvisionalSnapshot(currentHuskSync, user, snapshot)) {
                    return provisionalRollbackFailure();
                }
                return OfflineSaveOutcome.failure(
                        "Could not verify player presence after saving; the provisional "
                                + "snapshot was rolled back. The edit outcome is unknown; "
                                + "do not automatically retry a numeric mutation.");
            }
            if (stateAfterSave.ownerServer().isPresent()) {
                if (!rollbackProvisionalSnapshot(currentHuskSync, user, snapshot)) {
                    return provisionalRollbackFailure();
                }
                return OfflineSaveOutcome.failure(
                        "Player connected while the snapshot was being saved; the provisional "
                                + "snapshot was rolled back. The edit outcome is unknown; "
                                + "do not automatically retry a numeric mutation.");
            }
            if (stateAfterSave.serverSwitchInProgress()) {
                if (!rollbackProvisionalSnapshot(currentHuskSync, user, snapshot)) {
                    return provisionalRollbackFailure();
                }
                return OfflineSaveOutcome.failure(
                        "Player began switching servers during the edit; the provisional "
                                + "snapshot was rolled back. The edit outcome is unknown; "
                                + "do not automatically retry a numeric mutation.");
            }

            final Optional<DataSnapshot.Packed> confirmedHead;
            try {
                confirmedHead = currentHuskSync.getDatabase().getLatestSnapshot(user);
            } catch (RuntimeException databaseError) {
                if (!rollbackProvisionalSnapshot(currentHuskSync, user, snapshot)) {
                    return provisionalRollbackFailure();
                }
                return OfflineSaveOutcome.failure(
                        "Could not confirm the database head after saving; the provisional "
                                + "snapshot was rolled back. The edit outcome is unknown; "
                                + "do not automatically retry a numeric mutation.");
            }
            if (confirmedHead.isEmpty()
                    || !confirmedHead.get().getId().equals(snapshot.getId())) {
                if (!rollbackProvisionalSnapshot(currentHuskSync, user, snapshot)) {
                    return provisionalRollbackFailure();
                }
                return OfflineSaveOutcome.failure(
                        "The database head changed or could not be confirmed after saving; "
                                + "the provisional snapshot was rolled back. The edit outcome "
                                + "is unknown; do not automatically retry a numeric mutation.");
            }

            pruneConfirmedOfflineSnapshots(currentHuskSync, user, confirmedHead.get());

            return OfflineSaveOutcome.success();
        });
    }

    private void pruneConfirmedOfflineSnapshots(
            BukkitHuskSync currentHuskSync, User user, DataSnapshot.Packed confirmedSnapshot) {
        try {
            int retained = Math.max(1, currentHuskSync.getSettings().getSynchronization()
                    .getMaxUserDataSnapshots());
            var database = currentHuskSync.getDatabase();
            if (database.getUnpinnedSnapshotCount(user) <= retained) return;

            List<DataSnapshot.Packed> unpinned = database.getAllSnapshots(user).stream()
                    .filter(snapshot -> !snapshot.isPinned())
                    .sorted(Comparator.comparing(DataSnapshot.Packed::getTimestamp).reversed())
                    .toList();
            int attempted = 0;
            for (int index = retained; index < unpinned.size() && attempted < 64; index++) {
                DataSnapshot.Packed candidate = unpinned.get(index);
                if (candidate.getId().equals(confirmedSnapshot.getId())
                        || !candidate.getTimestamp().isBefore(confirmedSnapshot.getTimestamp())) {
                    continue;
                }
                attempted++;
                Optional<DataSnapshot.Packed> current = database.getSnapshot(user, candidate.getId());
                if (current.isPresent() && !current.get().isPinned()) {
                    database.deleteSnapshot(user, candidate.getId());
                }
            }
        } catch (RuntimeException cleanupError) {
            plugin.getLogger().log(Level.WARNING,
                    "Offline YOV data was committed for " + user.getUuid()
                            + " but old HuskSync snapshots could not be pruned.", cleanupError);
        }
    }

    private boolean rollbackProvisionalSnapshot(
            BukkitHuskSync currentHuskSync, User user, DataSnapshot.Packed snapshot) {
        try {
            return currentHuskSync.getDatabase().deleteSnapshot(user, snapshot.getId());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private OfflineSaveOutcome provisionalRollbackFailure() {
        return OfflineSaveOutcome.failure(
                "Player presence changed or became unclear during the edit and the "
                        + "provisional snapshot could not be rolled back safely. The edit "
                        + "outcome is unknown; do not automatically retry a numeric mutation.");
    }

    private OfflineEditResult serverSwitchFailure() {
        return OfflineEditResult.fail(
                "Player is switching servers; retry after HuskSync synchronization completes.");
    }

    private record OfflineSaveOutcome(boolean saved, String ownerServer, String failureMessage) {
        private static OfflineSaveOutcome success() {
            return new OfflineSaveOutcome(true, null, null);
        }

        private static OfflineSaveOutcome checkedOut(String ownerServer) {
            return new OfflineSaveOutcome(false, ownerServer, null);
        }

        private static OfflineSaveOutcome failure(String message) {
            return new OfflineSaveOutcome(false, null, message);
        }
    }

    private static final class OfflineEditPermit {
        private UUID playerId;
        private boolean released;
    }

    private Map<String, String> readOptionalSnapshot(Optional<DataSnapshot.Unpacked> optionalSnapshot,
                                                     Identifier currentIdentifier) {
        return optionalSnapshot.map(snapshot -> readVariables(snapshot, currentIdentifier))
                .orElseGet(Collections::emptyMap);
    }

    private Map<String, String> readVariables(DataHolder holder, Identifier currentIdentifier) {
        if (holder == null || currentIdentifier == null) return Collections.emptyMap();

        Optional<? extends Data> data = holder.getData(currentIdentifier);
        if (data.isPresent() && data.get() instanceof YovVariablesData yovData) {
            return normalize(yovData.getVariables());
        }

        Data direct = holder.getData().get(currentIdentifier);
        if (direct instanceof YovVariablesData yovData) {
            return normalize(yovData.getVariables());
        }

        return Collections.emptyMap();
    }

    public Map<String, String> normalize(Map<String, String> variables) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (variables == null || variables.isEmpty()) return normalized;
        if (variables.size() > limits.maxVariablesPerPlayer()) {
            throw new IllegalArgumentException("HuskSync YOV snapshot has too many variables ("
                    + variables.size() + "/" + limits.maxVariablesPerPlayer() + ").");
        }

        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String rawKey = entry.getKey();
            String value = entry.getValue();
            if (rawKey == null || value == null) {
                throw new IllegalArgumentException(
                        "HuskSync YOV snapshot contains a null variable name or value.");
            }
            if (rawKey.length() > limits.maxVariableNameBytes()
                    || value.length() > limits.maxValueBytes()) {
                throw new IllegalArgumentException(
                        "HuskSync YOV snapshot contains an oversized variable.");
            }
            String key = normalizeVariable(rawKey);
            if (key == null) {
                throw new IllegalArgumentException(
                        "HuskSync YOV snapshot contains an empty variable name.");
            }
            Optional<String> invalidEntry = limits.validateEntry(key, value);
            if (invalidEntry.isPresent()) {
                throw new IllegalArgumentException(
                        "Invalid HuskSync YOV snapshot: " + invalidEntry.get());
            }
            normalized.put(key, value);
        }

        limits.validateSnapshot(normalized).ifPresent(violation -> {
            throw new IllegalArgumentException("Invalid HuskSync YOV snapshot: " + violation);
        });

        return normalized;
    }

    public String normalizeVariable(String variable) {
        if (variable == null) return null;
        String normalized = variable.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
