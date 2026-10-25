package yov.service;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import yov.YOVPlugin;
import yov.api.YOVOperationResult;
import yov.api.event.YOVPlayerReadyEvent;
import yov.cache.VariableCache;
import yov.config.VariableLimits;
import yov.husksync.HuskSyncVariableStore;
import yov.husksync.YovDeltaOperation;
import yov.metrics.YOVMetrics;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Logger;

public final class VariableService {

    private static final char CACHE_KEY_SEPARATOR = (char) 31;

    private final YOVPlugin plugin;
    private final HuskSyncVariableStore store;
    private final Logger logger;
    private final String prefix;
    private final VariableLimits limits;
    private final YOVMetrics metrics;
    private final long syncTimeoutTicks;
    private final boolean disconnectOnSyncFailure;
    private final String syncFailureMessage;

    private final ConcurrentHashMap<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PreloadAttempt> preloadAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> uuidByName = new ConcurrentHashMap<>();
    private final Set<TrackedMainCall<?>> pendingMainThreadCalls = ConcurrentHashMap.newKeySet();
    private final AtomicInteger pendingMainThreadCallCount = new AtomicInteger();
    private final AtomicInteger pendingAsyncReadCount = new AtomicInteger();
    private final AtomicLong generations = new AtomicLong();
    private volatile Map<String, String> defaultVariables = Map.of();
    private volatile boolean acceptingMutations = true;
    private volatile boolean retired;

    public VariableService(YOVPlugin plugin,
                           VariableCache cache,
                           HuskSyncVariableStore store,
                           Logger logger,
                           String prefix) {
        this(plugin, store, logger, prefix);
    }

    public VariableService(YOVPlugin plugin,
                           HuskSyncVariableStore store,
                           Logger logger,
                           String prefix) {
        this(plugin, store, logger, prefix,
                VariableLimits.from(plugin.getConfig()), new YOVMetrics());
    }

    public VariableService(YOVPlugin plugin,
                           HuskSyncVariableStore store,
                           Logger logger,
                           String prefix,
                           VariableLimits limits,
                           YOVMetrics metrics) {
        this.plugin = plugin;
        this.store = store;
        this.logger = logger;
        this.prefix = prefix;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.syncTimeoutTicks = plugin.getConfig()
                .getLong("husksync.sync-timeout-ticks", 600L);
        this.disconnectOnSyncFailure = plugin.getConfig()
                .getBoolean("husksync.disconnect-on-sync-failure", true);
        String configuredFailureMessage = plugin.getConfig().getString(
                "husksync.sync-failure-message",
                "Your data could not be synchronized. Please reconnect.");
        this.syncFailureMessage = configuredFailureMessage != null
                ? configuredFailureMessage
                : "Your data could not be synchronized. Please reconnect.";
    }

    public VariableLimits limits() {
        return limits;
    }

    public YOVMetrics metrics() {
        return metrics;
    }

    public YOVMetrics.Snapshot metricsSnapshot() {
        int ready = 0;
        int syncing = 0;
        for (PlayerState state : states.values()) {
            Phase phase = state.phase;
            if (phase == Phase.READY) ready++;
            else if (phase == Phase.SYNCING) syncing++;
        }
        return metrics.snapshot(states.size(), ready, syncing);
    }

    public boolean isPlayerLoaded(String playerName) {
        UUID playerId = findPlayerId(playerName);
        return playerId != null && isPlayerReady(playerId);
    }

    public boolean isPlayerReady(UUID playerId) {
        if (playerId == null) return false;
        PlayerState state = states.get(playerId);
        return state != null && state.published.ready();
    }

    public boolean hasPlayerSnapshot(String playerName) {
        return isPlayerLoaded(playerName);
    }

    public boolean hasPlayerSnapshot(UUID playerId) {
        return isPlayerReady(playerId);
    }

    public void setDefaultVariablesSupplier(Supplier<Map<String, String>> supplier) {
        try {
            Map<String, String> supplied = supplier != null ? supplier.get() : Map.of();
            defaultVariables = immutableSnapshot(normalizeIngestSnapshot(
                    supplied != null ? supplied : Map.of(), "configured defaults"));
        } catch (RuntimeException error) {
            throw new IllegalStateException("Configured default variables are invalid.", error);
        }
    }

    public PreloadAttempt preloadCurrentVariables(UUID playerId, String playerName) {
        if (playerId == null || playerName == null || playerName.isBlank()) {
            CompletableFuture<Void> failed = CompletableFuture.failedFuture(
                    new IllegalArgumentException("Invalid player identity."));
            return new PreloadAttempt(playerId, -1L, failed);
        }

        long generation = generations.incrementAndGet();
        CompletableFuture<Void> completion = trackAsyncRead(
                () -> store.pullCurrentStrict(playerId, playerName), true).thenAccept(ignored -> {
        });
        PreloadAttempt attempt = new PreloadAttempt(playerId, generation, completion);
        PreloadAttempt previous = preloadAttempts.put(playerId, attempt);
        if (previous != null && previous != attempt) previous.completion().cancel(true);
        return attempt;
    }

    public void cancelPreload(PreloadAttempt attempt, String reason) {
        if (attempt == null || attempt.playerId() == null) return;
        if (preloadAttempts.remove(attempt.playerId(), attempt)) {
            attempt.completion().cancel(true);
        }
    }

    public void cleanupUnusedPreload(PreloadAttempt attempt) {
        if (attempt == null || attempt.playerId() == null) return;
        if (preloadAttempts.remove(attempt.playerId(), attempt)) {
            attempt.completion().cancel(true);
        }
    }

    public void startPlayerSession(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        preloadAttempts.remove(playerId);

        PlayerState existing = states.get(playerId);
        if (existing != null && existing.sessionPlayer == player) {
            return;
        }

        PlayerState replacement = new PlayerState(
                playerId, player.getName(), Phase.SYNCING, player);
        openSyncMetric(replacement);
        PlayerState previous = states.put(playerId, replacement);
        if (previous != null && previous != replacement) {
            retireState(previous, "Player session was replaced by a new login.");
            removeNameIndex(previous);
        }
        indexName(replacement);
        scheduleSynchronizationTimeout(replacement, replacement.syncToken);
    }

    public Map<String, String> prepareIncomingSnapshot(Player player,
                                                       UUID transactionId,
                                                       Map<String, String> incoming) {
        if (player == null) return normalizeIngestSnapshot(incoming, "incoming HuskSync snapshot");
        PlayerState state = stateForSession(player);
        long timeoutToken;
        synchronized (state) {
            if (state.ready.isDone()) state.ready = new CompletableFuture<>();
            boolean repeatedTransaction = Objects.equals(state.activeTransactionId, transactionId);
            Phase previousPhase = state.phase;
            if (!state.syncMetricOpen) openSyncMetric(state);
            if (!repeatedTransaction) {
                if (previousPhase == Phase.READY) {
                    state.rollbackVariables = new LinkedHashMap<>(state.published.variables());
                } else if (previousPhase != Phase.SYNCING) {
                    state.rollbackVariables = null;
                }
            }
            Map<String, String> merged = repeatedTransaction
                    ? new LinkedHashMap<>(state.staged)
                    : normalizeIngestSnapshot(incoming, "incoming HuskSync snapshot");
            applyDefaultValues(merged, getDefaultVariables());

            if (!repeatedTransaction) {
                for (PendingOperation operation : state.inFlight) applyOperation(merged, operation);
            }
            if (!state.queued.isEmpty()) {
                for (PendingOperation operation : state.queued) applyOperation(merged, operation);
                state.inFlight.addAll(state.queued);
                state.queued.clear();
            }

            applyDefaultValues(merged, getDefaultVariables());
            requireValidSnapshot(merged, "prepared HuskSync snapshot");
            state.staged = new LinkedHashMap<>(merged);
            state.phase = Phase.SYNCING;
            state.published = PublishedSnapshot.NOT_READY;
            state.failureMessage = null;
            state.activeTransactionId = transactionId;
            timeoutToken = ++state.syncToken;
            Map<String, String> prepared = new LinkedHashMap<>(merged);
            scheduleSynchronizationTimeout(state, timeoutToken);
            return prepared;
        }
    }

    public void completeSynchronization(Player player,
                                        Map<String, String> appliedVariables) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        PlayerState state = states.get(playerId);
        if (state == null || state.sessionPlayer != player) return;
        List<PendingOperation> appliedByHuskSync = new ArrayList<>();
        List<PendingOperation> lateOperations = new ArrayList<>();
        Map<String, String> appliedSnapshot;
        Map<String, String> published = null;
        CompletableFuture<Map<String, String>> readyFuture;
        RuntimeException pushFailure = null;

        synchronized (state) {
            if (states.get(playerId) != state || state.sessionPlayer != player
                    || state.phase != Phase.SYNCING) return;
            Map<String, String> merged = normalizeIngestSnapshot(
                    appliedVariables, "applied HuskSync snapshot");
            appliedSnapshot = immutableSnapshot(merged);
            boolean pushOnline = applyDefaultValues(merged, getDefaultVariables());

            if (!state.queued.isEmpty()) {
                for (PendingOperation operation : state.queued) applyOperation(merged, operation);
                lateOperations.addAll(state.queued);
                pushOnline = true;
            }

            pushOnline |= applyDefaultValues(merged, getDefaultVariables());
            requireValidSnapshot(merged, "completed HuskSync snapshot");
            appliedByHuskSync.addAll(state.inFlight);
            readyFuture = state.ready;

            if (pushOnline) {
                try {
                    store.pushOnline(playerId, merged);
                } catch (RuntimeException error) {
                    pushFailure = error;
                }
            }

            state.inFlight.clear();
            state.queued.clear();
            state.activeTransactionId = null;
            state.rollbackVariables = null;
            state.syncToken++;

            if (pushFailure == null) {
                state.staged = new LinkedHashMap<>(merged);
                state.phase = Phase.READY;
                state.failureMessage = null;
                published = publishReady(state, merged);
            } else {
                state.phase = Phase.FAILED;
                state.failureMessage = "Failed to publish synchronized YOV variables.";
                publishUnavailable(state);
                state.staged = new LinkedHashMap<>();
            }
        }

        if (pushFailure != null) {
            String message = "Failed to publish synchronized YOV variables.";
            for (PendingOperation operation : appliedByHuskSync) {
                operation.complete(appliedSnapshot);
            }
            String lateMessage = "Failed to publish a late player variable mutation; "
                    + "its outcome is unknown. Do not automatically retry a numeric mutation.";
            for (PendingOperation operation : lateOperations) {
                operation.fail(lateMessage);
            }
            readyFuture.completeExceptionally(new IllegalStateException(message, pushFailure));
            recordSyncFailed(state);
            disconnectFailedSession(state, player);
            throw pushFailure;
        }

        for (PendingOperation operation : lateOperations) operation.complete(published);
        for (PendingOperation operation : appliedByHuskSync) operation.complete(published);
        readyFuture.complete(published);

        if (player.isOnline() && Bukkit.getPlayer(playerId) == player) {
            Bukkit.getPluginManager().callEvent(new YOVPlayerReadyEvent(player, published));
        }
        recordSyncSucceeded(state);
    }

    public void bootstrapReadyPlayer(Player player, Map<String, String> variables) {
        bootstrapReadyPlayer(player, variables, true);
    }

    public void bootstrapReadyPlayer(Player player,
                                     Map<String, String> variables,
                                     boolean persistDefaults) {
        if (player == null) return;
        PlayerState current = states.get(player.getUniqueId());
        if (current != null && current.sessionPlayer == player) {
            if (current.phase == Phase.SYNCING) {
                completeSynchronization(player, variables);
            }
            return;
        }
        PlayerState state = new PlayerState(
                player.getUniqueId(), player.getName(), Phase.READY, player);
        Map<String, String> loaded = normalizeIngestSnapshot(
                variables, "online HuskSync snapshot");
        boolean defaultsChanged = applyDefaultValues(loaded, getDefaultVariables());
        requireValidSnapshot(loaded, "online snapshot with mandatory defaults");
        if (defaultsChanged && persistDefaults) {
            store.pushOnline(player.getUniqueId(), loaded);
        }
        state.staged = new LinkedHashMap<>(loaded);
        Map<String, String> published = publishReady(state, loaded);

        PlayerState previous = states.put(player.getUniqueId(), state);
        if (previous != null) {
            retireState(previous, "YOV was reloaded.");
            removeNameIndex(previous);
        }
        indexName(state);
        state.ready.complete(published);
        if (defaultsChanged && !persistDefaults) state.defaultBackfillPending = true;
    }

    public void flushDefaultBackfills() {
        for (PlayerState state : states.values()) {
            Map<String, String> snapshot;
            synchronized (state) {
                if (!state.defaultBackfillPending || state.phase != Phase.READY
                        || !state.published.ready()) continue;
                snapshot = state.published.variables();
                state.defaultBackfillPending = false;
            }
            try {
                store.pushOnline(state.playerId, snapshot);
            } catch (RuntimeException error) {
                synchronized (state) {
                    if (state.phase == Phase.READY) state.defaultBackfillPending = true;
                }
                logger.warning("Failed to persist default YOV variables for "
                        + state.playerId + ": " + rootMessage(error));
            }
        }
    }

    public void unloadPlayer(UUID playerId) {
        if (playerId == null) return;
        PlayerState state = states.remove(playerId);
        if (state == null) return;
        retireState(state, "Player disconnected before the operation completed.");
        removeNameIndex(state);
    }

    public void disconnectPlayer(Player player) {
        if (player == null) return;
        PlayerState state = states.get(player.getUniqueId());
        if (state == null || state.sessionPlayer != player) return;
        retireState(state, "Player disconnected before the operation completed.");
        removeNameIndex(state);
    }

    public void unloadPlayer(Player expectedSession) {
        if (expectedSession == null) return;
        UUID playerId = expectedSession.getUniqueId();
        PlayerState state = states.get(playerId);
        if (state == null || state.sessionPlayer != expectedSession
                || !states.remove(playerId, state)) return;
        retireState(state, "Player session unloaded.");
        removeNameIndex(state);
    }

    public void shutdown() {
        retired = true;
        acceptingMutations = false;
        IllegalStateException failure = new IllegalStateException("YOV runtime was retired.");
        for (TrackedMainCall<?> pending : pendingMainThreadCalls) {
            pending.exposed.completeExceptionally(failure);
            pending.release();
        }
        pendingMainThreadCalls.clear();
        for (PreloadAttempt attempt : preloadAttempts.values()) attempt.completion().cancel(true);
        preloadAttempts.clear();
        for (UUID playerId : new ArrayList<>(states.keySet())) unloadPlayer(playerId);
        uuidByName.clear();
    }

    public CompletableFuture<Boolean> prepareCloseForReload(long waitMillis) {
        acceptingMutations = false;
        CompletableFuture<?>[] pending = pendingSynchronizationMutations();
        if (pending.length == 0 || allDone(pending)) {
            return CompletableFuture.completedFuture(true);
        }

        CompletableFuture<Boolean> drained = CompletableFuture.allOf(pending)
                .handle((ignored, error) -> allDone(pending));
        CompletableFuture<Boolean> timeout = new CompletableFuture<>();
        CompletableFuture.delayedExecutor(Math.max(0L, waitMillis), TimeUnit.MILLISECONDS)
                .execute(() -> timeout.complete(false));
        return drained.applyToEither(timeout, result -> result).thenApply(success -> {
            if (!success && !retired) acceptingMutations = true;
            return success;
        });
    }

    public boolean finishCloseForReload() {
        return !acceptingMutations && allDone(pendingSynchronizationMutations());
    }

    public void abortCloseForReload() {
        if (!retired) acceptingMutations = true;
    }

    public void shutdownForReload() {
        retired = true;
        acceptingMutations = false;
        IllegalStateException failure = new IllegalStateException("YOV runtime was retired.");
        for (TrackedMainCall<?> pending : pendingMainThreadCalls) {
            pending.exposed.completeExceptionally(failure);
            pending.release();
        }
        pendingMainThreadCalls.clear();
        for (PreloadAttempt attempt : preloadAttempts.values()) attempt.completion().cancel(true);
        preloadAttempts.clear();
        for (PlayerState state : states.values()) {
            synchronized (state) {
                if (state.phase == Phase.READY && state.published.ready()) continue;
            }
            retireState(state, "YOV was reloaded.");
            removeNameIndex(state);
        }
    }

    private CompletableFuture<?>[] pendingSynchronizationMutations() {
        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (PlayerState state : states.values()) {
            synchronized (state) {
                for (PendingOperation operation : state.queued) {
                    if (!operation.future.isDone()) pending.add(operation.future);
                }
                for (PendingOperation operation : state.inFlight) {
                    if (!operation.future.isDone()) pending.add(operation.future);
                }
            }
        }
        return pending.toArray(CompletableFuture[]::new);
    }

    private boolean allDone(CompletableFuture<?>[] futures) {
        for (CompletableFuture<?> future : futures) {
            if (!future.isDone()) return false;
        }
        return true;
    }

    public Map<String, String> snapshotPlayerVars(String playerName) {
        return snapshotPlayerVars(findPlayerId(playerName));
    }

    public Map<String, String> snapshotPlayerVars(UUID playerId) {
        if (playerId == null) return Collections.emptyMap();
        PlayerState state = states.get(playerId);
        if (state == null) return Collections.emptyMap();
        PublishedSnapshot published = state.published;
        return published.ready()
                ? new LinkedHashMap<>(published.variables())
                : Collections.emptyMap();
    }

    public CompletableFuture<Map<String, String>> whenPlayerReady(UUID playerId) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> whenPlayerReady(playerId));
        }
        if (playerId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player UUID cannot be null."));
        }
        PlayerState state = states.get(playerId);
        if (state == null) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) state = beginWaitingForSync(player);
        }
        if (state == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Player has no active YOV session."));
        }

        PublishedSnapshot published = state.published;
        if (published.ready()) {
            return CompletableFuture.completedFuture(published.variables());
        }
        synchronized (state) {
            published = state.published;
            if (published.ready()) {
                return CompletableFuture.completedFuture(published.variables());
            }
            if (state.phase == Phase.FAILED && !state.ready.isDone()) {
                state.ready.completeExceptionally(new IllegalStateException(
                        state.failureMessage != null ? state.failureMessage : "Player synchronization failed."));
            }
            return state.ready.thenApply(variables -> variables);
        }
    }

    public String getSynchronizedValue(String fullKey) {
        if (fullKey == null) return null;
        int split = fullKey.indexOf(CACHE_KEY_SEPARATOR);
        if (split <= 0 || split + 1 >= fullKey.length()) return null;
        return getPlayerValue(fullKey.substring(0, split), fullKey.substring(split + 1));
    }

    public String getPlayerValue(String playerName, String variable) {
        return getPlayerValue(findPlayerId(playerName), variable);
    }

    public String getPlayerValue(UUID playerId, String variable) {
        return readLocalValue(playerId, variable).value();
    }

    public LocalValue readLocalValue(UUID playerId, String variable) {
        String var = normalizeVariable(variable);
        boolean ready = false;
        String result = null;
        if (playerId != null) {
            PlayerState state = states.get(playerId);
            if (state != null) {
                PublishedSnapshot published = state.published;
                ready = published.ready();
                if (ready && var != null) result = published.variables().get(var);
            }
        }
        metrics.fastRead(result != null);
        return new LocalValue(ready, result);
    }

    public List<String> knownVariableNames() {
        Set<String> names = new TreeSet<>();
        for (PlayerState state : states.values()) {
            PublishedSnapshot published = state.published;
            if (published.ready()) names.addAll(published.variables().keySet());
        }
        for (String variable : getDefaultVariables().keySet()) {
            String normalized = normalizeVariable(variable);
            if (normalized != null) names.add(normalized);
        }
        return new ArrayList<>(names);
    }

    public CompletableFuture<Optional<String>> getCurrentValue(String playerName, String variable) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> getCurrentValue(playerName, variable));
        }
        String requestedName = cleanPlayerName(playerName);
        String var = normalizeVariable(variable);
        if (requestedName == null || var == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Player online = findOnlinePlayer(requestedName);
        if (online != null) {
            return trackAsyncRead(
                    () -> whenPlayerReady(online.getUniqueId())
                            .thenApply(values -> Optional.ofNullable(values.get(var))), false);
        }
        return trackAsyncRead(() -> store.pullCurrent(requestedName), true)
                .thenApply(this::withDefaultValues)
                .thenApply(values -> Optional.ofNullable(values.get(var)));
    }

    public CompletableFuture<Optional<String>> getCurrentValue(UUID playerId, String variable) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> getCurrentValue(playerId, variable));
        }
        String var = normalizeVariable(variable);
        if (playerId == null || var == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return trackAsyncRead(
                    () -> whenPlayerReady(playerId)
                            .thenApply(values -> Optional.ofNullable(values.get(var))), false);
        }
        return trackAsyncRead(() -> store.pullCurrent(playerId), true)
                .thenApply(this::withDefaultValues)
                .thenApply(values -> Optional.ofNullable(values.get(var)));
    }

    public CompletableFuture<Map<String, String>> getCurrentVariables(String playerName) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> getCurrentVariables(playerName));
        }
        String requestedName = cleanPlayerName(playerName);
        if (requestedName == null) return CompletableFuture.completedFuture(Collections.emptyMap());
        Player online = findOnlinePlayer(requestedName);
        if (online != null) {
            return trackAsyncRead(
                    () -> whenPlayerReady(online.getUniqueId()).thenApply(LinkedHashMap::new), false);
        }
        return trackAsyncRead(() -> store.pullCurrent(requestedName), true)
                .thenApply(this::withDefaultValues);
    }

    public CompletableFuture<Map<String, String>> getCurrentVariables(UUID playerId) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> getCurrentVariables(playerId));
        }
        if (playerId == null) return CompletableFuture.completedFuture(Collections.emptyMap());
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return trackAsyncRead(
                    () -> whenPlayerReady(playerId).thenApply(LinkedHashMap::new), false);
        }
        return trackAsyncRead(() -> store.pullCurrent(playerId), true)
                .thenApply(this::withDefaultValues);
    }

    public CompletableFuture<YOVOperationResult> setVariable(String playerName, String variable, String value) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> setVariable(playerName, variable, value));
        }
        if (!acceptingMutations || retired) return mutationAdmissionRejected();
        String requestedName = cleanPlayerName(playerName);
        String var = normalizeVariable(variable);
        if (requestedName == null || var == null) return failedOperation("Invalid player or variable.");
        if (value == null) return failedOperation("Value cannot be null.");
        Optional<String> invalidEntry = limits.validateEntry(var, value);
        if (invalidEntry.isPresent()) return limitRejectedOperation(invalidEntry.get());
        Player player = findOnlinePlayer(requestedName);
        if (player != null) return mutateOnline(player, PendingOperation.set(var, value));

        return store.editOffline(requestedName, YovDeltaOperation.set(var, value), variables -> {
            applyDefaultValues(variables, getDefaultVariables());
            variables.put(var, value);
            return validateOfflineMutation(variables, value);
        }).thenApply(result -> operationResult(result, "Variable set."));
    }

    public CompletableFuture<YOVOperationResult> setVariable(UUID playerId, String variable, String value) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> setVariable(playerId, variable, value));
        }
        if (!acceptingMutations || retired) return mutationAdmissionRejected();
        String var = normalizeVariable(variable);
        if (playerId == null || var == null) return failedOperation("Invalid player UUID or variable.");
        if (value == null) return failedOperation("Value cannot be null.");
        Optional<String> invalidEntry = limits.validateEntry(var, value);
        if (invalidEntry.isPresent()) return limitRejectedOperation(invalidEntry.get());
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) return mutateOnline(player, PendingOperation.set(var, value));

        return store.editOffline(playerId, YovDeltaOperation.set(var, value), variables -> {
            applyDefaultValues(variables, getDefaultVariables());
            variables.put(var, value);
            return validateOfflineMutation(variables, value);
        }).thenApply(result -> operationResult(result, "Variable set."));
    }

    public CompletableFuture<YOVOperationResult> deleteVariable(String playerName, String variable) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> deleteVariable(playerName, variable));
        }
        if (!acceptingMutations || retired) return mutationAdmissionRejected();
        String requestedName = cleanPlayerName(playerName);
        String var = normalizeVariable(variable);
        if (requestedName == null || var == null) return failedOperation("Invalid player or variable.");
        Player player = findOnlinePlayer(requestedName);
        if (player != null) return mutateOnline(player, PendingOperation.delete(var));

        return store.editOffline(requestedName, YovDeltaOperation.delete(var), variables -> {
            applyDefaultValues(variables, getDefaultVariables());
            String previous = variables.remove(var);
            return new HuskSyncVariableStore.OfflineEditResult(true, previous, variables);
        }).thenApply(result -> operationResult(result, "Variable deleted."));
    }

    public CompletableFuture<YOVOperationResult> deleteVariable(UUID playerId, String variable) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> deleteVariable(playerId, variable));
        }
        if (!acceptingMutations || retired) return mutationAdmissionRejected();
        String var = normalizeVariable(variable);
        if (playerId == null || var == null) return failedOperation("Invalid player UUID or variable.");
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) return mutateOnline(player, PendingOperation.delete(var));

        return store.editOffline(playerId, YovDeltaOperation.delete(var), variables -> {
            applyDefaultValues(variables, getDefaultVariables());
            String previous = variables.remove(var);
            return new HuskSyncVariableStore.OfflineEditResult(true, previous, variables);
        }).thenApply(result -> operationResult(result, "Variable deleted."));
    }

    public CompletableFuture<YOVOperationResult> addVariable(String playerName, String variable, BigDecimal amount) {
        return modifyNumber(playerName, variable, amount, true);
    }

    public CompletableFuture<YOVOperationResult> addVariable(UUID playerId, String variable, BigDecimal amount) {
        return modifyNumber(playerId, variable, amount, true);
    }

    public CompletableFuture<YOVOperationResult> remVariable(String playerName, String variable, BigDecimal amount) {
        return modifyNumber(playerName, variable, amount, false);
    }

    public CompletableFuture<YOVOperationResult> remVariable(UUID playerId, String variable, BigDecimal amount) {
        return modifyNumber(playerId, variable, amount, false);
    }

    public CompletableFuture<YOVOperationResult> clearPlayerVariables(String playerName) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> clearPlayerVariables(playerName));
        }
        if (!acceptingMutations || retired) return mutationAdmissionRejected();
        String requestedName = cleanPlayerName(playerName);
        if (requestedName == null) return failedOperation("Invalid player.");
        Player player = findOnlinePlayer(requestedName);
        if (player != null) return mutateOnline(player, PendingOperation.clear());

        return store.editOffline(requestedName, YovDeltaOperation.clear(), variables -> {
            applyDefaultValues(variables, getDefaultVariables());
            int removed = variables.size();
            variables.clear();
            return new HuskSyncVariableStore.OfflineEditResult(true, String.valueOf(removed), variables);
        }).thenApply(result -> operationResult(result, "Variables cleared."));
    }

    public CompletableFuture<YOVOperationResult> clearPlayerVariables(UUID playerId) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> clearPlayerVariables(playerId));
        }
        if (!acceptingMutations || retired) return mutationAdmissionRejected();
        if (playerId == null) return failedOperation("Invalid player UUID.");
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) return mutateOnline(player, PendingOperation.clear());

        return store.editOffline(playerId, YovDeltaOperation.clear(), variables -> {
            applyDefaultValues(variables, getDefaultVariables());
            int removed = variables.size();
            variables.clear();
            return new HuskSyncVariableStore.OfflineEditResult(true, String.valueOf(removed), variables);
        }).thenApply(result -> operationResult(result, "Variables cleared."));
    }

    public void checkVariable(String playerName, String variable, CommandSender sender) {
        String requestedName = cleanPlayerName(playerName);
        String var = normalizeVariable(variable);
        if (requestedName == null || var == null) {
            sender.sendMessage(prefix + "§cInvalid player or variable.");
            return;
        }
        getCurrentValue(requestedName, var).whenComplete((value, error) -> runSync(() -> {
            if (error != null) {
                sender.sendMessage(prefix + "§cCould not read HuskSync data: " + rootMessage(error));
                return;
            }
            sender.sendMessage(prefix + "§e" + requestedName + "§7/§e" + var + "§7: §f"
                    + value.orElse("null"));
        }));
    }

    public void setVariable(String playerName, String variable, String value, CommandSender sender, boolean silent) {
        deliver(setVariable(playerName, variable, value), sender, silent,
                result -> "§aVariable set. New value: §f" + result.value() + "§a.");
    }

    public void deleteVariable(String playerName, String variable, CommandSender sender, boolean silent) {
        deliver(deleteVariable(playerName, variable), sender, silent, ignored -> "§aVariable deleted.");
    }

    public void addVariable(String playerName, String variable, String amount, CommandSender sender, boolean silent) {
        modifyNumberCommand(playerName, variable, amount, sender, silent, true);
    }

    public void remVariable(String playerName, String variable, String amount, CommandSender sender, boolean silent) {
        modifyNumberCommand(playerName, variable, amount, sender, silent, false);
    }

    public void clearPlayerVariables(String playerName, CommandSender sender) {
        deliver(clearPlayerVariables(playerName), sender, false,
                result -> "§aRemoved §e" + result.value() + "§a variables.");
    }

    public void applyDefaults(Player player, Map<String, String> defaults) {
        if (player == null || defaults == null || defaults.isEmpty()) return;
        PlayerState state = states.get(player.getUniqueId());
        if (state == null) return;
        synchronized (state) {
            if (state.phase != Phase.READY) return;
            Map<String, String> updated = new LinkedHashMap<>(state.published.variables());
            if (!applyDefaultValues(updated, defaults)) return;
            try {
                requireValidSnapshot(updated, "snapshot after applying defaults");
                store.pushOnline(player.getUniqueId(), updated);
                state.staged = new LinkedHashMap<>(updated);
                publishReady(state, updated);
            } catch (RuntimeException error) {
                logger.warning("Failed to apply default YOV variables for "
                        + player.getUniqueId() + ": " + rootMessage(error));
            }
        }
    }

    private CompletableFuture<YOVOperationResult> modifyNumber(String playerName,
                                                               String variable,
                                                               BigDecimal amount,
                                                               boolean add) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> modifyNumber(playerName, variable, amount, add));
        }
        if (!acceptingMutations || retired) return mutationAdmissionRejected();
        String requestedName = cleanPlayerName(playerName);
        String var = normalizeVariable(variable);
        if (requestedName == null || var == null) return failedOperation("Invalid player or variable.");
        if (amount == null) return failedOperation("Amount cannot be null.");
        Optional<String> invalidAmount = validateNumericSize(amount);
        if (invalidAmount.isPresent()) return limitRejectedOperation(invalidAmount.get());
        Player player = findOnlinePlayer(requestedName);
        if (player != null) {
            return mutateOnline(player, add
                    ? PendingOperation.add(var, amount)
                    : PendingOperation.remove(var, amount));
        }

        YovDeltaOperation remoteOperation = add
                ? YovDeltaOperation.add(var, amount)
                : YovDeltaOperation.remove(var, amount);
        return store.editOffline(requestedName, remoteOperation, variables -> {
            applyDefaultValues(variables, getDefaultVariables());
            String current = variables.get(var);
            BigDecimal currentNumber;
            try {
                currentNumber = current == null ? BigDecimal.ZERO : parseDecimal(current);
            } catch (NumberFormatException ex) {
                return HuskSyncVariableStore.OfflineEditResult.fail("Variable '" + var + "' is not numeric.");
            }
            NumericCalculation calculation = calculateNumeric(currentNumber, amount, add);
            if (calculation.failed()) {
                metrics.mutationRejected(YOVMetrics.RejectReason.LIMIT);
                return HuskSyncVariableStore.OfflineEditResult.fail(calculation.error());
            }
            String result = calculation.value();
            variables.put(var, result);
            return validateOfflineMutation(variables, result);
        }).thenApply(result -> operationResult(result, "Variable updated."));
    }

    private CompletableFuture<YOVOperationResult> modifyNumber(UUID playerId,
                                                               String variable,
                                                               BigDecimal amount,
                                                               boolean add) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> modifyNumber(playerId, variable, amount, add));
        }
        if (!acceptingMutations || retired) return mutationAdmissionRejected();
        String var = normalizeVariable(variable);
        if (playerId == null || var == null) return failedOperation("Invalid player UUID or variable.");
        if (amount == null) return failedOperation("Amount cannot be null.");
        Optional<String> invalidAmount = validateNumericSize(amount);
        if (invalidAmount.isPresent()) return limitRejectedOperation(invalidAmount.get());
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            return mutateOnline(player, add
                    ? PendingOperation.add(var, amount)
                    : PendingOperation.remove(var, amount));
        }

        YovDeltaOperation remoteOperation = add
                ? YovDeltaOperation.add(var, amount)
                : YovDeltaOperation.remove(var, amount);
        return store.editOffline(playerId, remoteOperation, variables -> {
            applyDefaultValues(variables, getDefaultVariables());
            String current = variables.get(var);
            BigDecimal currentNumber;
            try {
                currentNumber = current == null ? BigDecimal.ZERO : parseDecimal(current);
            } catch (NumberFormatException ex) {
                return HuskSyncVariableStore.OfflineEditResult.fail("Variable '" + var + "' is not numeric.");
            }
            NumericCalculation calculation = calculateNumeric(currentNumber, amount, add);
            if (calculation.failed()) {
                metrics.mutationRejected(YOVMetrics.RejectReason.LIMIT);
                return HuskSyncVariableStore.OfflineEditResult.fail(calculation.error());
            }
            String result = calculation.value();
            variables.put(var, result);
            return validateOfflineMutation(variables, result);
        }).thenApply(result -> operationResult(result, "Variable updated."));
    }

    private void modifyNumberCommand(String playerName,
                                     String variable,
                                     String amount,
                                     CommandSender sender,
                                     boolean silent,
                                     boolean add) {
        BigDecimal parsed;
        try {
            parsed = parseDecimal(amount);
        } catch (NumberFormatException ex) {
            send(sender, silent, "§cInvalid number: " + amount);
            return;
        }
        CompletableFuture<YOVOperationResult> operation = add
                ? addVariable(playerName, variable, parsed)
                : remVariable(playerName, variable, parsed);
        deliver(operation, sender, silent,
                result -> "§aVariable updated. New value: §f" + result.value() + "§a.");
    }

    public CompletableFuture<HuskSyncVariableStore.OfflineEditResult> applyNetworkEdit(
            UUID playerId, YovDeltaOperation remoteOperation) {
        if (!Bukkit.isPrimaryThread()) {
            return callOnMainThread(() -> applyNetworkEdit(playerId, remoteOperation));
        }
        if (retired || !acceptingMutations || playerId == null || remoteOperation == null) {
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "The owning YOV runtime is unavailable."));
        }

        Player player = Bukkit.getPlayer(playerId);
        PlayerState state = states.get(playerId);
        if (player == null || !player.isOnline() || Bukkit.getPlayer(playerId) != player
                || state == null || state.sessionPlayer != player
                || state.phase != Phase.READY || !state.published.ready()) {
            metrics.mutationRejected(YOVMetrics.RejectReason.REMOTE_ONLINE);
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "The player is not READY on the owning server."));
        }

        String variable = remoteOperation.type() == YovDeltaOperation.Type.CLEAR
                ? null : normalizeVariable(remoteOperation.variable());
        if (remoteOperation.type() != YovDeltaOperation.Type.CLEAR && variable == null) {
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "The remote variable name is invalid."));
        }

        final PendingOperation operation;
        try {
            operation = switch (remoteOperation.type()) {
                case SET -> {
                    String value = remoteOperation.operand();
                    Optional<String> invalid = limits.validateEntry(variable, value);
                    if (invalid.isPresent()) {
                        yield null;
                    }
                    yield PendingOperation.set(variable, value);
                }
                case DELETE -> PendingOperation.delete(variable);
                case ADD, REMOVE -> {
                    BigDecimal amount = new BigDecimal(remoteOperation.operand());
                    if (validateNumericSize(amount).isPresent()) yield null;
                    yield remoteOperation.type() == YovDeltaOperation.Type.ADD
                            ? PendingOperation.add(variable, amount)
                            : PendingOperation.remove(variable, amount);
                }
                case CLEAR -> PendingOperation.clear();
            };
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "The remote variable edit is invalid."));
        }
        if (operation == null) {
            metrics.mutationRejected(YOVMetrics.RejectReason.LIMIT);
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "The remote variable edit exceeds configured limits."));
        }

        return mutateOnline(player, operation, true).thenApply(result -> result.success()
                ? new HuskSyncVariableStore.OfflineEditResult(
                        true, result.value(), result.variables())
                : HuskSyncVariableStore.OfflineEditResult.fail(result.message()));
    }

    private CompletableFuture<YOVOperationResult> mutateOnline(Player player, PendingOperation operation) {
        return mutateOnline(player, operation, false);
    }

    private CompletableFuture<YOVOperationResult> mutateOnline(Player player,
                                                                PendingOperation operation,
                                                                boolean requireReadySession) {
        if (retired || !acceptingMutations) return mutationAdmissionRejected();
        PlayerState state = states.get(player.getUniqueId());
        if (requireReadySession && (state == null || state.sessionPlayer != player)) {
            return CompletableFuture.completedFuture(YOVOperationResult.failure(
                    "The player is not READY on the owning server."));
        }
        if (state == null || state.sessionPlayer != player) state = stateForSession(player);
        CompletableFuture<YOVOperationResult> exposed = operation.future.thenApply(result -> result);

        Map<String, String> snapshot = null;
        synchronized (state) {
            if (!Objects.equals(state.name, player.getName())) {
                removeNameIndex(state);
                state.name = player.getName();
                indexName(state);
            }
            if (state.phase == Phase.FAILED) {
                metrics.mutationRejected(YOVMetrics.RejectReason.OTHER);
                operation.future.complete(YOVOperationResult.failure(
                        state.failureMessage != null
                                ? state.failureMessage
                                : "Player data synchronization failed."));
                return exposed;
            }
            if (state.phase != Phase.READY) {
                if (requireReadySession) {
                    metrics.mutationRejected(YOVMetrics.RejectReason.REMOTE_ONLINE);
                    operation.future.complete(YOVOperationResult.failure(
                            "The player is not READY on the owning server."));
                    return exposed;
                }
                int pending = state.queued.size() + state.inFlight.size();
                if (pending >= limits.maxPendingOnlinePerPlayer()) {
                    metrics.mutationRejected(YOVMetrics.RejectReason.BACKPRESSURE);
                    operation.future.complete(YOVOperationResult.failure(
                            "Too many pending player mutations (" + pending + "/"
                                    + limits.maxPendingOnlinePerPlayer()
                                    + "). Wait for synchronization to finish."));
                    return exposed;
                }
                trackAcceptedOnlineMutation(operation);
                state.queued.add(operation);
                return exposed;
            }
            trackAcceptedOnlineMutation(operation);
            Map<String, String> updated = new LinkedHashMap<>(state.published.variables());
            applyOperation(updated, operation);
            if (operation.errorMessage == null) {
                try {
                    store.pushOnline(player.getUniqueId(), updated);
                    state.staged = new LinkedHashMap<>(updated);
                    snapshot = publishReady(state, updated);
                } catch (RuntimeException error) {
                    logger.warning("Failed to publish an online YOV mutation for "
                            + player.getUniqueId() + ": " + rootMessage(error));
                    operation.fail("Failed to publish the player variable mutation; it was not applied.");
                    return exposed;
                }
            }
        }

        if (snapshot != null) {
            operation.complete(snapshot);
        } else {
            operation.complete(snapshotPlayerVars(player.getUniqueId()));
        }
        return exposed;
    }

    private void trackAcceptedOnlineMutation(PendingOperation operation) {
        metrics.mutationAccepted(false);
        operation.future.whenComplete((result, error) -> {
            if (operation.limitRejected) {
                metrics.mutationRejected(YOVMetrics.RejectReason.LIMIT);
            }
            metrics.mutationCompleted(false,
                    error == null && result != null && result.success());
        });
    }

    private PlayerState stateForSession(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerState state = states.get(playerId);
        if (state != null && state.sessionPlayer == player) return state;
        startPlayerSession(player);
        return states.get(playerId);
    }

    public void waitForSynchronization(Player player) {
        if (player != null) beginWaitingForSync(player);
    }

    private PlayerState beginWaitingForSync(Player player) {
        return stateForSession(player);
    }

    public void failSynchronization(Player player, String reason) {
        failSynchronization(player, -1L, reason);
    }

    public void cancelSynchronization(Player player, long expectedToken, String reason) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        PlayerState state = states.get(playerId);
        if (state == null || state.sessionPlayer != player) return;

        List<PendingOperation> completed = new ArrayList<>();
        CompletableFuture<Map<String, String>> readyFuture;
        Map<String, String> restored;
        Map<String, String> storedSnapshot = null;
        boolean unlockReadySession = false;
        boolean dataStored = false;
        RuntimeException restoreFailure = null;
        synchronized (state) {
            if (states.get(playerId) != state || state.sessionPlayer != player) return;
            if (expectedToken >= 0L && state.syncToken != expectedToken) return;
            if (state.phase == Phase.READY) {
                restored = null;
                readyFuture = null;
                unlockReadySession = true;
            } else if (state.phase != Phase.SYNCING) {
                return;
            } else if (state.rollbackVariables == null) {
                restored = null;
                readyFuture = null;
            } else {
                restored = new LinkedHashMap<>(state.rollbackVariables);
                for (PendingOperation operation : state.inFlight) applyOperation(restored, operation);
                for (PendingOperation operation : state.queued) applyOperation(restored, operation);
                completed.addAll(state.inFlight);
                completed.addAll(state.queued);
                applyDefaultValues(restored, getDefaultVariables());
                requireValidSnapshot(restored, "restored HuskSync snapshot");
                storedSnapshot = immutableSnapshot(restored);
                readyFuture = state.ready;
                try {
                    store.pushOnline(playerId, restored);
                    dataStored = true;
                    store.unlockPlayer(playerId);
                } catch (RuntimeException error) {
                    restoreFailure = error;
                }

                state.inFlight.clear();
                state.queued.clear();
                state.activeTransactionId = null;
                state.rollbackVariables = null;
                state.syncToken++;
                if (restoreFailure == null) {
                    state.staged = new LinkedHashMap<>(restored);
                    state.phase = Phase.READY;
                    state.failureMessage = null;
                    restored = publishReady(state, restored);
                } else {
                    state.phase = Phase.FAILED;
                    state.failureMessage = "Failed to restore YOV variables after a cancelled synchronization.";
                    publishUnavailable(state);
                    state.staged = new LinkedHashMap<>();
                }
            }
        }

        if (unlockReadySession) {
            try {
                store.unlockPlayer(playerId);
            } catch (RuntimeException error) {
                failSynchronization(player, expectedToken,
                        "Failed to unlock the player after cancelled synchronization.");
                throw error;
            }
            return;
        }

        if (restored == null) {
            failSynchronization(player, expectedToken, reason);
            return;
        }

        recordSyncFailed(state);
        if (restoreFailure != null) {
            String failure = "Failed to restore YOV variables after a cancelled synchronization.";
            if (dataStored) {
                for (PendingOperation operation : completed) {
                    operation.complete(storedSnapshot);
                }
            } else {
                for (PendingOperation operation : completed) operation.fail(failure);
            }
            readyFuture.completeExceptionally(new IllegalStateException(failure, restoreFailure));
            disconnectFailedSession(state, player);
            throw restoreFailure;
        }

        for (PendingOperation operation : completed) operation.complete(restored);
        readyFuture.complete(restored);
    }

    public long synchronizationToken(Player player) {
        if (player == null) return -1L;
        PlayerState state = states.get(player.getUniqueId());
        if (state == null || state.sessionPlayer != player) return -1L;
        synchronized (state) {
            return state.syncToken;
        }
    }

    public long acknowledgeSynchronizationComplete(Player player) {
        if (player == null) return -1L;
        UUID playerId = player.getUniqueId();
        PlayerState state = states.get(playerId);
        if (state == null || state.sessionPlayer != player) return -1L;

        long token;
        synchronized (state) {
            if (states.get(playerId) != state || state.sessionPlayer != player
                    || state.phase != Phase.SYNCING) return -1L;
            token = ++state.syncToken;
        }
        scheduleSynchronizationTimeout(state, token);
        return token;
    }

    public void failSynchronization(Player player, long expectedToken, String reason) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        PlayerState state = states.get(playerId);
        if (state == null || state.sessionPlayer != player) return;

        String failure = reason != null && !reason.isBlank()
                ? reason
                : "Player data synchronization failed.";
        synchronized (state) {
            if (states.get(playerId) != state || state.sessionPlayer != player) return;
            if (expectedToken >= 0L && state.syncToken != expectedToken) return;
            if (state.phase == Phase.FAILED) return;
            if (state.ready.isDone()) state.ready = new CompletableFuture<>();
            state.phase = Phase.FAILED;
            state.failureMessage = failure;
            state.activeTransactionId = null;
            state.rollbackVariables = null;
            state.syncToken++;
            publishUnavailable(state);
            state.staged = new LinkedHashMap<>();
        }
        recordSyncFailed(state);
        failPending(state, failure);
        disconnectFailedSession(state, player);
    }

    private void disconnectFailedSession(PlayerState state, Player player) {
        if (!plugin.isEnabled() || !disconnectOnSyncFailure) {
            store.unlockPlayer(state.playerId);
            return;
        }

        Runnable disconnect = () -> {
            if (states.get(state.playerId) != state || state.phase != Phase.FAILED
                    || player == null || !player.isOnline()
                    || Bukkit.getPlayer(state.playerId) != player) return;
            player.kick(Component.text(syncFailureMessage));
        };
        if (Bukkit.isPrimaryThread()) {
            disconnect.run();
        } else {
            store.unlockPlayer(state.playerId);
            Bukkit.getScheduler().runTask(plugin, disconnect);
        }
    }

    private void scheduleSynchronizationTimeout(PlayerState state, long token) {
        if (syncTimeoutTicks <= 0L || !plugin.isEnabled()) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (retired) return;
            if (states.get(state.playerId) != state) return;
            synchronized (state) {
                if (state.phase != Phase.SYNCING || state.syncToken != token) return;
            }

            Player player = state.sessionPlayer;
            failSynchronization(
                    player,
                    token,
                    "HuskSync did not complete player synchronization in time.");
        }, syncTimeoutTicks);
    }

    private void deliver(CompletableFuture<YOVOperationResult> operation,
                         CommandSender sender,
                         boolean silent,
                         java.util.function.Function<YOVOperationResult, String> successMessage) {
        operation.whenComplete((result, error) -> runSync(() -> {
            if (error != null) {
                send(sender, silent, "§cOperation failed: " + rootMessage(error));
                return;
            }
            if (result == null || result.failed()) {
                send(sender, silent, "§c" + (result != null ? result.message() : "Operation failed."));
                return;
            }
            send(sender, silent, successMessage.apply(result));
        }));
    }

    private CompletableFuture<YOVOperationResult> failedOperation(String message) {
        return CompletableFuture.completedFuture(YOVOperationResult.failure(message));
    }

    private CompletableFuture<YOVOperationResult> mutationAdmissionRejected() {
        metrics.mutationRejected(YOVMetrics.RejectReason.OTHER);
        return failedOperation("YOV is reloading; retry the variable edit after reload completes.");
    }

    private CompletableFuture<YOVOperationResult> limitRejectedOperation(String violation) {
        metrics.mutationRejected(YOVMetrics.RejectReason.LIMIT);
        return failedOperation(limitFailureMessage(violation));
    }

    private HuskSyncVariableStore.OfflineEditResult validateOfflineMutation(
            Map<String, String> variables, String resultValue) {
        Optional<String> invalid = limits.validateSnapshot(variables);
        if (invalid.isPresent()) {
            metrics.mutationRejected(YOVMetrics.RejectReason.LIMIT);
            return HuskSyncVariableStore.OfflineEditResult.fail(limitFailureMessage(invalid.get()));
        }
        return new HuskSyncVariableStore.OfflineEditResult(true, resultValue, variables);
    }

    private void applyOperation(Map<String, String> variables, PendingOperation operation) {
        Map<String, String> previous = operation.requiresLimitValidation()
                ? new LinkedHashMap<>(variables)
                : null;
        operation.applyTo(variables, this);
        if (operation.errorMessage != null || previous == null) return;

        Optional<String> invalid = limits.validateSnapshot(variables);
        if (invalid.isEmpty()) return;
        variables.clear();
        variables.putAll(previous);
        operation.errorMessage = limitFailureMessage(invalid.get());
        operation.limitRejected = true;
    }

    private String limitFailureMessage(String violation) {
        return "Mutation rejected by YOV safety limits: " + violation;
    }

    private YOVOperationResult operationResult(HuskSyncVariableStore.OfflineEditResult result,
                                               String successMessage) {
        if (!result.success()) return YOVOperationResult.failure(result.message());
        return YOVOperationResult.success(successMessage, result.message(), result.variables());
    }

    private Map<String, String> getDefaultVariables() {
        return defaultVariables;
    }

    private boolean applyDefaultValues(Map<String, String> variables, Map<String, String> defaults) {
        if (variables == null || defaults == null || defaults.isEmpty()) return false;
        boolean changed = false;
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            String key = normalizeVariable(entry.getKey());
            String value = entry.getValue();
            if (key == null || value == null || variables.containsKey(key)) continue;
            variables.put(key, value);
            changed = true;
        }
        return changed;
    }

    private Map<String, String> normalizeIngestSnapshot(Map<String, String> variables,
                                                         String context) {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (variables == null) return normalized;
        if (variables.size() > limits.maxVariablesPerPlayer()) {
            throw invalidSnapshot(context, "Player has too many raw variables ("
                    + variables.size() + "/" + limits.maxVariablesPerPlayer() + ").");
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String rawKey = entry.getKey();
            String value = entry.getValue();
            if (rawKey == null || value == null) {
                throw invalidSnapshot(context, "Variable names and values cannot be null.");
            }
            if (rawKey.length() > limits.maxVariableNameBytes()) {
                throw invalidSnapshot(context, "Variable name is too large.");
            }
            if (value.length() > limits.maxValueBytes()) {
                throw invalidSnapshot(context, "Variable value is too large.");
            }
            String key = normalizeVariable(rawKey);
            if (key == null) {
                throw invalidSnapshot(context, "Variable name cannot be empty.");
            }
            Optional<String> invalidEntry = limits.validateEntry(key, value);
            if (invalidEntry.isPresent()) throw invalidSnapshot(context, invalidEntry.get());
            normalized.put(key, value);
        }
        requireValidSnapshot(normalized, context);
        return normalized;
    }

    private Map<String, String> withDefaultValues(Map<String, String> variables) {
        Map<String, String> result = normalizeIngestSnapshot(
                variables, "HuskSync data read");
        applyDefaultValues(result, getDefaultVariables());
        requireValidSnapshot(result, "HuskSync data with mandatory defaults");
        return result;
    }

    private Map<String, String> publishReady(PlayerState state,
                                             Map<String, String> variables) {
        requireValidSnapshot(variables, "published player snapshot");
        Map<String, String> immutable = immutableSnapshot(variables);
        state.published = new PublishedSnapshot(true, immutable);
        return immutable;
    }

    private static Map<String, String> immutableSnapshot(Map<String, String> variables) {
        return variables == null || variables.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    private void requireValidSnapshot(Map<String, String> variables, String context) {
        limits.validateSnapshot(variables).ifPresent(violation -> {
            throw invalidSnapshot(context, violation);
        });
    }

    private IllegalArgumentException invalidSnapshot(String context, String violation) {
        return new IllegalArgumentException("Invalid " + context + ": " + violation);
    }

    private static void publishUnavailable(PlayerState state) {
        state.published = PublishedSnapshot.NOT_READY;
    }

    private void indexName(PlayerState state) {
        if (state.name != null && !state.name.isBlank()) {
            uuidByName.put(normalizePlayer(state.name), state.playerId);
        }
    }

    private void removeNameIndex(PlayerState state) {
        if (state.name != null && !state.name.isBlank()) {
            uuidByName.remove(normalizePlayer(state.name), state.playerId);
        }
    }

    private UUID findPlayerId(String playerName) {
        String requestedName = cleanPlayerName(playerName);
        if (requestedName == null) return null;
        return uuidByName.get(normalizePlayer(requestedName));
    }

    private Player findOnlinePlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        Player exact = Bukkit.getPlayerExact(playerName);
        if (exact != null) return exact;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(playerName)) return player;
        }
        return null;
    }

    public String normalizePlayer(String playerName) {
        return playerName == null ? "" : playerName.trim().toLowerCase(Locale.ROOT);
    }

    public String normalizeVariable(String variable) {
        return store.normalizeVariable(variable);
    }

    public String toFullKey(String playerName, String variable) {
        String playerKey = normalizePlayer(playerName);
        String var = normalizeVariable(variable);
        if (playerKey.isEmpty() || var == null) return null;
        return playerKey + CACHE_KEY_SEPARATOR + var;
    }

    private String cleanPlayerName(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        return playerName.trim();
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null) throw new NumberFormatException("null");
        return new BigDecimal(raw.trim());
    }

    private Optional<String> validateNumericSize(BigDecimal value) {
        if (value == null) return Optional.of("Numeric value cannot be null.");
        final BigDecimal normalized;
        try {
            normalized = normalizeNumeric(value);
        } catch (ArithmeticException error) {
            return Optional.of("Numeric scale is too large.");
        }
        long bytes = estimatedPlainLength(normalized);
        if (bytes > limits.maxValueBytes()) {
            return Optional.of("Numeric value would require " + bytes
                    + " plain-text bytes (max " + limits.maxValueBytes() + ").");
        }
        return Optional.empty();
    }

    private NumericCalculation calculateNumeric(BigDecimal current,
                                                BigDecimal amount,
                                                boolean add) {
        final BigDecimal normalizedCurrent;
        final BigDecimal normalizedAmount;
        try {
            normalizedCurrent = normalizeNumeric(current);
            normalizedAmount = normalizeNumeric(amount);
        } catch (ArithmeticException error) {
            return NumericCalculation.failure(limitFailureMessage("Numeric scale is too large."));
        }

        Optional<String> invalidCurrent = validateNumericSize(normalizedCurrent);
        if (invalidCurrent.isPresent()) {
            return NumericCalculation.failure(limitFailureMessage(invalidCurrent.get()));
        }
        Optional<String> invalidAmount = validateNumericSize(normalizedAmount);
        if (invalidAmount.isPresent()) {
            return NumericCalculation.failure(limitFailureMessage(invalidAmount.get()));
        }

        long scaleGap = Math.abs((long) normalizedCurrent.scale() - normalizedAmount.scale());
        if (scaleGap > limits.maxValueBytes()) {
            return NumericCalculation.failure(limitFailureMessage(
                    "Numeric scale gap is too large (" + scaleGap + "/"
                            + limits.maxValueBytes() + ")."));
        }

        final BigDecimal result;
        try {
            result = normalizeNumeric(add
                    ? normalizedCurrent.add(normalizedAmount)
                    : normalizedCurrent.subtract(normalizedAmount));
        } catch (ArithmeticException error) {
            return NumericCalculation.failure(limitFailureMessage("Numeric result is too large."));
        }
        Optional<String> invalidResult = validateNumericSize(result);
        if (invalidResult.isPresent()) {
            return NumericCalculation.failure(limitFailureMessage(invalidResult.get()));
        }
        return NumericCalculation.success(result.toPlainString());
    }

    private BigDecimal normalizeNumeric(BigDecimal value) {
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }

    private static long estimatedPlainLength(BigDecimal value) {
        if (value.signum() == 0) return 1L;
        long precision = value.precision();
        long scale = value.scale();
        long length;
        if (scale <= 0L) {
            length = precision - scale;
        } else if (scale >= precision) {
            length = scale + 2L;
        } else {
            length = precision + 1L;
        }
        return value.signum() < 0 ? length + 1L : length;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private void runSync(Runnable task) {
        if (!plugin.isEnabled()) return;
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    private <T> CompletableFuture<T> trackAsyncRead(
            Supplier<CompletableFuture<T>> operationFactory,
            boolean offline) {
        int pending = pendingAsyncReadCount.incrementAndGet();
        if (pending > limits.maxPendingApiCallsGlobal()) {
            pendingAsyncReadCount.decrementAndGet();
            metrics.apiBackpressureRejected();
            return CompletableFuture.failedFuture(new RejectedExecutionException(
                    "Too many YOV asynchronous reads are pending (limit "
                            + limits.maxPendingApiCallsGlobal() + ")."));
        }

        final CompletableFuture<T> operation;
        try {
            operation = operationFactory.get();
        } catch (RuntimeException error) {
            pendingAsyncReadCount.decrementAndGet();
            if (offline) metrics.offlineRead(true);
            return CompletableFuture.failedFuture(error);
        }
        operation.whenComplete((result, error) -> {
            pendingAsyncReadCount.decrementAndGet();
            if (offline) metrics.offlineRead(error != null);
        });
        return operation.thenApply(result -> result);
    }

    public record LocalValue(boolean ready, String value) {
    }

    private <T> CompletableFuture<T> callOnMainThread(Supplier<CompletableFuture<T>> operation) {
        if (retired || !plugin.isEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException("YourOwnVariables is disabled."));
        }
        if (Bukkit.isPrimaryThread()) {
            try {
                return operation.get();
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }

        TrackedMainCall<T> call = new TrackedMainCall<>();
        int pendingCount = pendingMainThreadCallCount.incrementAndGet();
        if (pendingCount > limits.maxPendingApiCallsGlobal()) {
            pendingMainThreadCallCount.decrementAndGet();
            metrics.apiBackpressureRejected();
            call.released.set(true);
            call.exposed.completeExceptionally(new RejectedExecutionException(
                    "Too many YOV main-thread calls are pending (limit "
                            + limits.maxPendingApiCallsGlobal() + ")."));
            return call.exposed;
        }
        pendingMainThreadCalls.add(call);
        if (retired || !plugin.isEnabled()) {
            call.exposed.completeExceptionally(new IllegalStateException("YOV runtime was retired."));
            call.release();
            return call.exposed;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (retired || call.exposed.isDone()) {
                    if (retired) {
                        call.exposed.completeExceptionally(
                                new IllegalStateException("YOV runtime was retired."));
                    }
                    call.release();
                    return;
                }
                try {
                    operation.get().whenComplete((value, error) -> {
                        if (error != null) call.exposed.completeExceptionally(error);
                        else call.exposed.complete(value);
                        call.release();
                    });
                } catch (RuntimeException error) {
                    call.exposed.completeExceptionally(error);
                    call.release();
                }
            });
        } catch (RuntimeException error) {
            call.exposed.completeExceptionally(error);
            call.release();
        }
        return call.exposed;
    }

    private void send(CommandSender sender, boolean silent, String message) {
        if (!silent && sender != null) sender.sendMessage(prefix + message);
    }

    private void failPending(PlayerState state, String message) {
        List<PendingOperation> pending = new ArrayList<>();
        synchronized (state) {
            pending.addAll(state.queued);
            pending.addAll(state.inFlight);
            state.queued.clear();
            state.inFlight.clear();
            if (!state.ready.isDone()) {
                state.ready.completeExceptionally(new IllegalStateException(message));
            }
        }
        for (PendingOperation operation : pending) {
            operation.future.complete(YOVOperationResult.failure(message));
        }
    }

    private void retireState(PlayerState state, String message) {
        synchronized (state) {
            if (state.ready.isDone()) state.ready = new CompletableFuture<>();
            state.phase = Phase.FAILED;
            state.failureMessage = message;
            state.activeTransactionId = null;
            state.rollbackVariables = null;
            state.syncToken++;
            publishUnavailable(state);
            state.staged = new LinkedHashMap<>();
        }
        recordSyncFailed(state);
        failPending(state, message);
    }

    private void openSyncMetric(PlayerState state) {
        if (state.syncMetricOpen) return;
        state.syncMetricOpen = true;
        state.syncStartedNanos = System.nanoTime();
        metrics.syncStarted();
    }

    private void recordSyncSucceeded(PlayerState state) {
        long duration = closeSyncMetric(state);
        if (duration >= 0L) metrics.syncSucceeded(duration);
    }

    private void recordSyncFailed(PlayerState state) {
        long duration = closeSyncMetric(state);
        if (duration >= 0L) metrics.syncFailed(duration);
    }

    private long closeSyncMetric(PlayerState state) {
        synchronized (state) {
            if (!state.syncMetricOpen) return -1L;
            state.syncMetricOpen = false;
            return Math.max(0L, System.nanoTime() - state.syncStartedNanos);
        }
    }

    public record PreloadAttempt(UUID playerId, long generation, CompletableFuture<Void> completion) {
    }

    private enum Phase { SYNCING, READY, FAILED }

    private enum OperationType { SET, DELETE, ADD, REMOVE, CLEAR }

    private record NumericCalculation(String value, String error) {
        private static NumericCalculation success(String value) {
            return new NumericCalculation(value, null);
        }

        private static NumericCalculation failure(String error) {
            return new NumericCalculation(null, error);
        }

        private boolean failed() {
            return error != null;
        }
    }

    private record PublishedSnapshot(boolean ready, Map<String, String> variables) {
        private static final PublishedSnapshot NOT_READY =
                new PublishedSnapshot(false, Map.of());
    }

    private final class TrackedMainCall<T> {
        private final CompletableFuture<T> exposed = new CompletableFuture<>();
        private final AtomicBoolean released = new AtomicBoolean();

        private void release() {
            if (!released.compareAndSet(false, true)) return;
            pendingMainThreadCalls.remove(this);
            pendingMainThreadCallCount.decrementAndGet();
        }
    }

    private static final class PlayerState {
        private final UUID playerId;
        private final Player sessionPlayer;
        private CompletableFuture<Map<String, String>> ready = new CompletableFuture<>();
        private final List<PendingOperation> queued = new ArrayList<>();
        private final List<PendingOperation> inFlight = new ArrayList<>();
        private String name;
        private volatile Phase phase;
        private volatile PublishedSnapshot published = PublishedSnapshot.NOT_READY;
        private String failureMessage;
        private UUID activeTransactionId;
        private Map<String, String> rollbackVariables;
        private long syncToken;
        private long syncStartedNanos;
        private boolean syncMetricOpen;
        private boolean defaultBackfillPending;
        private Map<String, String> staged = new LinkedHashMap<>();

        private PlayerState(UUID playerId, String name, Phase phase, Player sessionPlayer) {
            this.playerId = playerId;
            this.name = name;
            this.phase = phase;
            this.sessionPlayer = sessionPlayer;
        }
    }

    private static final class PendingOperation {
        private final OperationType type;
        private final String variable;
        private final String value;
        private final BigDecimal amount;
        private final CompletableFuture<YOVOperationResult> future = new CompletableFuture<>();
        private String resultValue;
        private String errorMessage;
        private boolean limitRejected;

        private PendingOperation(OperationType type, String variable, String value, BigDecimal amount) {
            this.type = type;
            this.variable = variable;
            this.value = value;
            this.amount = amount;
        }

        private static PendingOperation set(String variable, String value) {
            return new PendingOperation(OperationType.SET, variable, value, null);
        }

        private static PendingOperation delete(String variable) {
            return new PendingOperation(OperationType.DELETE, variable, null, null);
        }

        private static PendingOperation add(String variable, BigDecimal amount) {
            return new PendingOperation(OperationType.ADD, variable, null, amount);
        }

        private static PendingOperation remove(String variable, BigDecimal amount) {
            return new PendingOperation(OperationType.REMOVE, variable, null, amount);
        }

        private static PendingOperation clear() {
            return new PendingOperation(OperationType.CLEAR, null, null, null);
        }

        private void applyTo(Map<String, String> variables, VariableService service) {
            errorMessage = null;
            limitRejected = false;
            switch (type) {
                case SET -> {
                    variables.put(variable, value);
                    resultValue = value;
                }
                case DELETE -> resultValue = variables.remove(variable);
                case CLEAR -> {
                    resultValue = String.valueOf(variables.size());
                    variables.clear();
                }
                case ADD, REMOVE -> {
                    BigDecimal current;
                    try {
                        String raw = variables.get(variable);
                        current = raw == null ? BigDecimal.ZERO : new BigDecimal(raw.trim());
                    } catch (NumberFormatException error) {
                        errorMessage = "Variable '" + variable + "' is not numeric.";
                        return;
                    }
                    NumericCalculation calculation = service.calculateNumeric(
                            current, amount, type == OperationType.ADD);
                    if (calculation.failed()) {
                        errorMessage = calculation.error();
                        limitRejected = true;
                        return;
                    }
                    resultValue = calculation.value();
                    variables.put(variable, resultValue);
                }
            }
        }

        private boolean requiresLimitValidation() {
            return type == OperationType.SET
                    || type == OperationType.ADD
                    || type == OperationType.REMOVE;
        }

        private void complete(Map<String, String> snapshot) {
            if (future.isDone()) return;
            if (errorMessage != null) {
                future.complete(YOVOperationResult.failure(errorMessage));
                return;
            }
            String message = switch (type) {
                case SET -> "Variable set.";
                case DELETE -> "Variable deleted.";
                case ADD, REMOVE -> "Variable updated.";
                case CLEAR -> "Variables cleared.";
            };
            future.complete(YOVOperationResult.success(message, resultValue, snapshot));
        }

        private void fail(String message) {
            future.complete(YOVOperationResult.failure(message));
        }
    }
}
