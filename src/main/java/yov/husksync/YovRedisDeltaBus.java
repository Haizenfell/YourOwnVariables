package yov.husksync;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import net.william278.husksync.BukkitHuskSync;
import net.william278.husksync.config.Settings;
import net.william278.husksync.redis.RedisKeyType;
import net.william278.husksync.sync.DataSyncer;
import yov.YOVPlugin;
import yov.metrics.YOVMetrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

final class YovRedisDeltaBus implements AutoCloseable {

    @FunctionalInterface
    interface OnlineEditHandler {
        CompletableFuture<HuskSyncVariableStore.OfflineEditResult> apply(
                UUID playerId, YovDeltaOperation operation);
    }

    private static final String REQUEST_MAGIC = "YOVR1";
    private static final String ACK_MAGIC = "YOVA1";
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

    private final YOVPlugin plugin;
    private final BukkitHuskSync huskSync;
    private final YOVMetrics metrics;
    private final Object lifecycleLock = new Object();
    private final Object receiptLock = new Object();
    private final Map<UUID, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicInteger pendingRequestCount = new AtomicInteger();
    private final Set<CompletableFuture<?>> pendingAcknowledgements =
            ConcurrentHashMap.newKeySet();
    private final LinkedHashMap<UUID, OwnerReceipt> ownerReceipts = new LinkedHashMap<>();

    private RedisClient client;
    private StatefulRedisConnection<String, String> commands;
    private StatefulRedisPubSubConnection<String, String> subscriber;
    private volatile OnlineEditHandler handler;
    private String clusterId;
    private String serverName;
    private String requestChannel;
    private String acknowledgementChannel;
    private String receiptKeyPrefix;
    private int maxPayloadBytes;
    private int maxPendingRequestCount;
    private int maxReceiptCount;
    private long receiptTtlMillis;
    private long requestTimeoutMillis;
    private long requestMaxAgeMillis;
    private long redisCommandTimeoutMillis;
    private volatile boolean initialized;
    private volatile boolean active;
    private volatile boolean acceptingInbound;
    private volatile boolean closed;

    YovRedisDeltaBus(YOVPlugin plugin, BukkitHuskSync huskSync, YOVMetrics metrics) {
        this.plugin = plugin;
        this.huskSync = huskSync;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    void initialize() {
        synchronized (lifecycleLock) {
            if (initialized || closed) {
                throw new IllegalStateException("The YOV Redis delta bus cannot be initialized.");
            }

            Settings settings = huskSync.getSettings();
            if (settings.getSynchronization().getMode() != DataSyncer.Mode.LOCKSTEP) {
                throw new IllegalStateException(
                        "YOV cross-server edits require HuskSync synchronization.mode=LOCKSTEP.");
            }
            Settings.RedisSettings.RedisCredentials credentials = settings.getRedis().getCredentials();
            clusterId = settings.getClusterId();
            serverName = huskSync.getServerName();
            if (clusterId == null || clusterId.isBlank() || serverName == null || serverName.isBlank()) {
                throw new IllegalStateException("HuskSync cluster/server names must not be blank.");
            }

            maxPayloadBytes = boundedInt(
                    plugin.getConfig().getInt("husksync.delta-rpc.max-payload-bytes", 524_288),
                    4096, 4 * 1024 * 1024);
            maxPendingRequestCount = boundedInt(
                    plugin.getConfig().getInt("husksync.delta-rpc.max-pending-requests", 2048),
                    16, 65_536);
            maxReceiptCount = boundedInt(
                    plugin.getConfig().getInt("husksync.delta-rpc.receipt-cache-size", 4096),
                    64, 65_536);
            requestTimeoutMillis = boundedLong(
                    plugin.getConfig().getLong("husksync.delta-rpc.timeout-millis", 5000L),
                    250L, 60_000L);
            requestMaxAgeMillis = Math.max(
                    requestTimeoutMillis,
                    boundedLong(plugin.getConfig().getLong(
                                    "husksync.delta-rpc.max-request-age-millis", 30_000L),
                            1000L, 300_000L));
            receiptTtlMillis = Math.max(requestMaxAgeMillis, boundedLong(
                    plugin.getConfig().getLong("husksync.delta-rpc.receipt-ttl-millis", 300_000L),
                    10_000L, 3_600_000L));
            redisCommandTimeoutMillis = boundedLong(
                    credentials.getSocketTimeout(), 250L, 60_000L);

            String channelPrefix = "yov:" + clusterId + ":variable_delta:v1:";
            requestChannel = channelPrefix + "request";
            acknowledgementChannel = channelPrefix + "ack";
            receiptKeyPrefix = channelPrefix + "receipt:" + encodeText(serverName) + ":";

            try {
                RedisURI redisUri = createRedisUri(settings.getRedis());
                client = RedisClient.create();
                client.setOptions(ClientOptions.builder()
                        .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(redisCommandTimeoutMillis)))
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(boundedLong(
                                        credentials.getConnectionTimeout(), 250L, 60_000L)))
                                .build())
                        .autoReconnect(true)
                        .build());
                commands = client.connect(StringCodec.UTF8, redisUri);
                subscriber = client.connectPubSub(StringCodec.UTF8, redisUri);
                subscriber.addListener(new RedisPubSubAdapter<>() {
                    @Override
                    public void message(String channel, String message) {
                        receive(channel, message);
                    }
                });
                String pong = commands.sync().ping();
                if (pong == null || !pong.equalsIgnoreCase("PONG")) {
                    throw new IllegalStateException("Redis did not answer the YOV health check.");
                }
                initialized = true;
            } catch (Throwable error) {
                closeConnections();
                throw new IllegalStateException("Could not initialize the YOV Redis delta channel.", error);
            }
        }
    }

    void activate(OnlineEditHandler onlineEditHandler) {
        Objects.requireNonNull(onlineEditHandler, "onlineEditHandler");
        synchronized (lifecycleLock) {
            if (!initialized || closed || active) {
                throw new IllegalStateException("The YOV Redis delta bus cannot be activated.");
            }
            handler = onlineEditHandler;
            acceptingInbound = false;
            try {
                subscriber.sync().subscribe(requestChannel, acknowledgementChannel);
                active = true;
                acceptingInbound = true;
            } catch (Throwable error) {
                handler = null;
                throw new IllegalStateException("Could not subscribe to the YOV Redis delta channel.", error);
            }
        }
    }

    CompletableFuture<?>[] beginDrain() {
        synchronized (receiptLock) {
            acceptingInbound = false;
            return pendingOperationsLocked();
        }
    }

    void abortDrain() {
        synchronized (receiptLock) {
            if (!closed && active && handler != null) acceptingInbound = true;
        }
    }

    boolean transferReceiptsTo(YovRedisDeltaBus target) {
        if (target == null || target == this) return target == this;
        synchronized (target.lifecycleLock) {
            if (!target.initialized || target.closed || target.active) return false;
            if (!Objects.equals(clusterId, target.clusterId)
                    || !Objects.equals(serverName, target.serverName)) {
                return true;
            }
        }

        long now = System.currentTimeMillis();
        List<ReceiptSnapshot> snapshots = new ArrayList<>();
        synchronized (receiptLock) {
            pruneReceiptsLocked(now);
            for (Map.Entry<UUID, OwnerReceipt> entry : ownerReceipts.entrySet()) {
                OwnerReceipt receipt = entry.getValue();
                if (!receipt.future.isDone() || !receipt.acknowledgement.isDone()) return false;
                HuskSyncVariableStore.OfflineEditResult result = receipt.future.getNow(null);
                if (result == null) return false;
                snapshots.add(new ReceiptSnapshot(
                        entry.getKey(), receipt.signature, result, receipt.completedAtMillis));
            }
        }

        synchronized (target.receiptLock) {
            target.pruneReceiptsLocked(now);
            int additions = 0;
            for (ReceiptSnapshot snapshot : snapshots) {
                OwnerReceipt existing = target.ownerReceipts.get(snapshot.requestId());
                if (existing == null) additions++;
                else if (!existing.signature.equals(snapshot.signature())) return false;
            }
            if (target.ownerReceipts.size() + additions > target.maxReceiptCount) return false;

            for (ReceiptSnapshot snapshot : snapshots) {
                if (target.ownerReceipts.containsKey(snapshot.requestId())) continue;
                OwnerReceipt copy = new OwnerReceipt(snapshot.signature());
                copy.completedAtMillis = snapshot.completedAtMillis();
                copy.future.complete(snapshot.result());
                copy.acknowledgement.complete(null);
                target.ownerReceipts.put(snapshot.requestId(), copy);
            }
            target.pruneReceiptsLocked(now);
        }
        return true;
    }

    CompletableFuture<?>[] pendingOperations() {
        synchronized (receiptLock) {
            return pendingOperationsLocked();
        }
    }

    private CompletableFuture<?>[] pendingOperationsLocked() {
        List<CompletableFuture<?>> pending = new ArrayList<>();
        for (PendingRequest request : pendingRequests.values()) {
            if (!request.future.isDone()) pending.add(request.future);
        }
        for (OwnerReceipt receipt : ownerReceipts.values()) {
            if (!receipt.acknowledgement.isDone()) pending.add(receipt.acknowledgement);
        }
        for (CompletableFuture<?> acknowledgement : pendingAcknowledgements) {
            if (!acknowledgement.isDone()) pending.add(acknowledgement);
        }
        return pending.toArray(CompletableFuture[]::new);
    }

    CompletableFuture<Optional<String>> readCheckout(UUID playerId) {
        return readNetworkState(playerId).thenApply(PlayerNetworkState::ownerServer);
    }

    CompletableFuture<PlayerNetworkState> readNetworkState(UUID playerId) {
        if (playerId == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Player UUID must not be null."));
        }
        StatefulRedisConnection<String, String> current = commands;
        if (!initialized || closed || current == null || !current.isOpen()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("YOV Redis checkout reader is unavailable."));
        }
        try {
            return current.async().mget(checkoutKey(playerId), serverSwitchKey(playerId))
                    .toCompletableFuture()
                    .orTimeout(redisCommandTimeoutMillis, TimeUnit.MILLISECONDS)
                    .thenApply(this::decodeNetworkState);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    PlayerNetworkState readNetworkStateNow(UUID playerId) {
        StatefulRedisConnection<String, String> current = commands;
        if (!initialized || closed || current == null || !current.isOpen()) {
            throw new IllegalStateException("YOV Redis checkout reader is unavailable.");
        }
        return decodeNetworkState(current.sync().mget(
                checkoutKey(playerId), serverSwitchKey(playerId)));
    }

    boolean evictOfflineSnapshotCacheNow(UUID playerId) {
        StatefulRedisConnection<String, String> current = commands;
        if (!initialized || closed || current == null || !current.isOpen()) {
            throw new IllegalStateException("YOV Redis cache invalidation is unavailable.");
        }
        Long evicted = current.sync().eval(
                "if redis.call('EXISTS', KEYS[1], KEYS[2]) > 0 then return 0 end "
                        + "redis.call('DEL', KEYS[3]); return 1",
                ScriptOutputType.INTEGER,
                new String[]{checkoutKey(playerId), serverSwitchKey(playerId),
                        RedisKeyType.LATEST_SNAPSHOT.getKeyPrefix(clusterId) + ":" + playerId});
        if (evicted == null) {
            throw new IllegalStateException("Redis did not confirm YOV cache invalidation.");
        }
        return evicted == 1L;
    }

    CompletableFuture<HuskSyncVariableStore.OfflineEditResult> request(
            String ownerServer, UUID playerId, YovDeltaOperation operation) {
        if (ownerServer == null || ownerServer.isBlank() || playerId == null || operation == null) {
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "Invalid remote variable edit request."));
        }
        synchronized (lifecycleLock) {
            if (!active || closed) {
                return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                        "YOV cross-server edits are unavailable."));
            }
        }

        DeltaRequest request = new DeltaRequest(
                UUID.randomUUID(), playerId, System.currentTimeMillis(),
                serverName, ownerServer, operation);
        final String payload;
        try {
            payload = encodeRequest(request);
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "Variable edit request exceeds the configured Redis payload limit."));
        }

        PendingRequest pending = new PendingRequest(request.signature());
        if (pendingRequestCount.incrementAndGet() > maxPendingRequestCount) {
            pendingRequestCount.decrementAndGet();
            metrics.mutationRejected(YOVMetrics.RejectReason.BACKPRESSURE);
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "Too many cross-server variable edits are already pending."));
        }
        PendingRequest collision = pendingRequests.putIfAbsent(request.requestId, pending);
        if (collision != null) {
            pendingRequestCount.decrementAndGet();
            return CompletableFuture.completedFuture(HuskSyncVariableStore.OfflineEditResult.fail(
                    "Could not allocate a cross-server edit request."));
        }
        pending.future.whenComplete((result, error) -> {
            if (pendingRequests.remove(request.requestId, pending)) {
                pendingRequestCount.decrementAndGet();
            }
        });

        publish(requestChannel, payload).whenComplete((listeners, error) -> {
            if (error != null) {
                pending.future.complete(HuskSyncVariableStore.OfflineEditResult.fail(
                        "Could not confirm publication of the cross-server variable edit; "
                                + "the outcome is unknown. Do not automatically retry a numeric mutation."));
            } else if (listeners == null || listeners < 1L) {
                pending.future.complete(HuskSyncVariableStore.OfflineEditResult.fail(
                        "No YOV backend is listening for cross-server edits."));
            }
        });

        long retryDelay = Math.max(100L, requestTimeoutMillis / 2L);
        CompletableFuture.delayedExecutor(retryDelay, TimeUnit.MILLISECONDS).execute(() -> {
            if (!pending.future.isDone() && !closed) publish(requestChannel, payload);
        });
        CompletableFuture.delayedExecutor(requestTimeoutMillis, TimeUnit.MILLISECONDS).execute(() ->
                pending.future.complete(HuskSyncVariableStore.OfflineEditResult.fail(
                        "Timed out waiting for the owning server to acknowledge the variable edit; "
                                + "the outcome is unknown.")));
        return pending.future;
    }

    private void receive(String channel, String payload) {
        if (payload == null || payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) return;
        try {
            if (requestChannel.equals(channel)) {
                receiveRequest(decodeRequest(payload));
            } else if (acknowledgementChannel.equals(channel)) {
                receiveAcknowledgement(decodeAcknowledgement(payload));
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void receiveRequest(DeltaRequest request) {
        if (!serverName.equals(request.targetServer)) return;
        long now = System.currentTimeMillis();
        if (request.createdAtMillis > now + requestTimeoutMillis
                || now - request.createdAtMillis > requestMaxAgeMillis) {
            publishAcknowledgement(request, HuskSyncVariableStore.OfflineEditResult.fail(
                    "The cross-server variable edit expired before delivery."));
            return;
        }

        OwnerReceipt receipt;
        boolean start = false;
        boolean collision = false;
        boolean backpressured = false;
        boolean draining = false;
        synchronized (receiptLock) {
            pruneReceiptsLocked(now);
            receipt = ownerReceipts.get(request.requestId);
            if (receipt != null) {
                if (!receipt.signature.equals(request.signature())) collision = true;
            } else if (!acceptingInbound || handler == null || closed) {
                draining = true;
            } else {
                if (ownerReceipts.size() >= maxReceiptCount) {
                    backpressured = true;
                } else {
                    receipt = new OwnerReceipt(request.signature());
                    ownerReceipts.put(request.requestId, receipt);
                    start = true;
                }
            }
        }

        if (collision) {
            publishAcknowledgement(request, HuskSyncVariableStore.OfflineEditResult.fail(
                    "A conflicting cross-server request ID was rejected."));
            return;
        }
        if (draining) {
            return;
        }
        if (backpressured) {
            metrics.mutationRejected(YOVMetrics.RejectReason.BACKPRESSURE);
            publishAcknowledgement(request, HuskSyncVariableStore.OfflineEditResult.fail(
                    "The owning server's variable edit receipt cache is temporarily full."));
            return;
        }
        if (receipt == null) {
            publishAcknowledgement(request, HuskSyncVariableStore.OfflineEditResult.fail(
                    "The owning YOV runtime is reloading or unavailable."));
            return;
        }

        OwnerReceipt selected = receipt;
        boolean primaryDelivery = start;
        selected.future.whenComplete((result, error) -> {
            CompletableFuture<Long> acknowledgement = publishAcknowledgement(
                    request,
                    error == null && result != null
                            ? result
                            : HuskSyncVariableStore.OfflineEditResult.fail(
                                    "The owning server failed to apply the variable edit."));
            if (primaryDelivery) {
                acknowledgement.whenComplete((ignored, publishError) -> {
                    compactReceipt(selected);
                    selected.acknowledgement.complete(null);
                });
            }
        });

        if (!start) return;
        OnlineEditHandler selectedHandler = handler;
        claimRequest(request).thenCompose(claim -> switch (claim) {
                    case ACQUIRED -> readCheckout(request.playerId)
                            .thenCompose(owner -> {
                                if (owner.isEmpty() || !serverName.equals(owner.get())) {
                                    return CompletableFuture.completedFuture(
                                            HuskSyncVariableStore.OfflineEditResult.fail(
                                                    "The target server no longer owns this player."));
                                }
                                if (selectedHandler == null) {
                                    return CompletableFuture.completedFuture(
                                            HuskSyncVariableStore.OfflineEditResult.fail(
                                                    "The owning YOV runtime is unavailable."));
                                }
                                return selectedHandler.apply(request.playerId, request.operation);
                            });
                    case DUPLICATE -> CompletableFuture.completedFuture(
                            HuskSyncVariableStore.OfflineEditResult.fail(
                                    "This request was already accepted by an earlier YOV runtime; "
                                            + "the outcome is unknown and the operation was not repeated."));
                    case COLLISION -> CompletableFuture.completedFuture(
                            HuskSyncVariableStore.OfflineEditResult.fail(
                                    "A conflicting cross-server request ID was rejected."));
                    case UNKNOWN -> CompletableFuture.completedFuture(
                            HuskSyncVariableStore.OfflineEditResult.fail(
                                    "Could not establish cross-server idempotency; the operation was not run."));
                })
                .exceptionally(error -> HuskSyncVariableStore.OfflineEditResult.fail(
                        "The owning server could not reserve, verify, or apply the variable edit."))
                .whenComplete((result, error) -> {
                    HuskSyncVariableStore.OfflineEditResult completed = error == null && result != null
                            ? result
                            : HuskSyncVariableStore.OfflineEditResult.fail(
                                    "The owning server failed to apply the variable edit.");
                    synchronized (receiptLock) {
                        selected.completedAtMillis = System.currentTimeMillis();
                    }
                    selected.future.complete(completed);
                    synchronized (receiptLock) {
                        pruneReceiptsLocked(selected.completedAtMillis);
                    }
                });
    }

    private CompletableFuture<DurableClaim> claimRequest(DeltaRequest request) {
        StatefulRedisConnection<String, String> current = commands;
        if (!initialized || closed || current == null || !current.isOpen()) {
            return CompletableFuture.completedFuture(DurableClaim.UNKNOWN);
        }
        String key = receiptKeyPrefix + request.requestId;
        String fingerprint = requestFingerprint(request);
        try {
            SetArgs arguments = new SetArgs().nx().px(receiptTtlMillis);
            return current.async().set(key, fingerprint, arguments).toCompletableFuture()
                    .orTimeout(redisCommandTimeoutMillis, TimeUnit.MILLISECONDS)
                    .thenCompose(response -> {
                        if (response != null) {
                            return CompletableFuture.completedFuture(DurableClaim.ACQUIRED);
                        }
                        return current.async().get(key).toCompletableFuture()
                                .orTimeout(redisCommandTimeoutMillis, TimeUnit.MILLISECONDS)
                                .thenApply(existing -> existing == null
                                        ? DurableClaim.UNKNOWN
                                        : existing.equals(fingerprint)
                                        ? DurableClaim.DUPLICATE
                                        : DurableClaim.COLLISION);
                    })
                    .exceptionally(error -> DurableClaim.UNKNOWN);
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(DurableClaim.UNKNOWN);
        }
    }

    private String requestFingerprint(DeltaRequest request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(encodeRequest(request).getBytes(StandardCharsets.UTF_8));
            return BASE64_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable.", impossible);
        }
    }

    private void receiveAcknowledgement(DeltaAcknowledgement acknowledgement) {
        if (!serverName.equals(acknowledgement.sourceServer)) return;
        PendingRequest pending = pendingRequests.get(acknowledgement.requestId);
        if (pending == null
                || !pending.signature.playerId.equals(acknowledgement.playerId)
                || !pending.signature.targetServer.equals(acknowledgement.ownerServer)) {
            return;
        }
        pending.future.complete(acknowledgement.result);
    }

    private CompletableFuture<Long> publishAcknowledgement(
            DeltaRequest request, HuskSyncVariableStore.OfflineEditResult result) {
        DeltaAcknowledgement acknowledgement = new DeltaAcknowledgement(
                request.requestId, request.playerId, request.sourceServer, serverName, result);
        String payload;
        try {
            payload = encodeAcknowledgement(acknowledgement);
        } catch (RuntimeException tooLarge) {
            HuskSyncVariableStore.OfflineEditResult compact = result.success()
                    ? new HuskSyncVariableStore.OfflineEditResult(true, result.message(), Map.of())
                    : HuskSyncVariableStore.OfflineEditResult.fail(result.message());
            try {
                payload = encodeAcknowledgement(new DeltaAcknowledgement(
                        request.requestId, request.playerId, request.sourceServer, serverName, compact));
            } catch (RuntimeException stillTooLarge) {
                payload = encodeAcknowledgement(new DeltaAcknowledgement(
                        request.requestId, request.playerId, request.sourceServer, serverName,
                        HuskSyncVariableStore.OfflineEditResult.fail(
                                "The edit result exceeded the Redis payload limit; outcome is unknown.")));
            }
        }
        CompletableFuture<Long> publication = publish(acknowledgementChannel, payload);
        pendingAcknowledgements.add(publication);
        publication.whenComplete((ignored, error) -> pendingAcknowledgements.remove(publication));
        return publication;
    }

    private CompletableFuture<Long> publish(String channel, String payload) {
        StatefulRedisConnection<String, String> current = commands;
        if (!initialized || closed || current == null || !current.isOpen()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("YOV Redis publisher is unavailable."));
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("YOV Redis payload is too large."));
        }
        try {
            return current.async().publish(channel, payload).toCompletableFuture()
                    .orTimeout(redisCommandTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private void pruneReceiptsLocked(long now) {
        Iterator<Map.Entry<UUID, OwnerReceipt>> iterator = ownerReceipts.entrySet().iterator();
        while (iterator.hasNext()) {
            OwnerReceipt receipt = iterator.next().getValue();
            if (receipt.acknowledgement.isDone() && receipt.completedAtMillis > 0L
                    && now - receipt.completedAtMillis >= receiptTtlMillis) {
                iterator.remove();
            }
        }
    }

    private void compactReceipt(OwnerReceipt receipt) {
        synchronized (receiptLock) {
            CompletableFuture<HuskSyncVariableStore.OfflineEditResult> current = receipt.future;
            if (!current.isDone()) return;
            HuskSyncVariableStore.OfflineEditResult result = current.getNow(null);
            if (result == null || result.variables().isEmpty()) return;
            receipt.future = CompletableFuture.completedFuture(
                    new HuskSyncVariableStore.OfflineEditResult(
                            result.success(), result.message(), Map.of()));
        }
    }

    private String checkoutKey(UUID playerId) {
        return RedisKeyType.DATA_CHECKOUT.getKeyPrefix(clusterId) + ":" + playerId;
    }

    private String serverSwitchKey(UUID playerId) {
        return RedisKeyType.SERVER_SWITCH.getKeyPrefix(clusterId) + ":" + playerId;
    }

    private PlayerNetworkState decodeNetworkState(List<KeyValue<String, String>> values) {
        if (values == null || values.size() != 2) {
            throw new IllegalStateException("Redis returned an invalid HuskSync marker response.");
        }
        KeyValue<String, String> checkout = values.get(0);
        KeyValue<String, String> serverSwitch = values.get(1);
        if (checkout == null || serverSwitch == null) {
            throw new IllegalStateException("Redis returned an invalid HuskSync marker response.");
        }

        Optional<String> owner = checkout.hasValue()
                ? Optional.ofNullable(checkout.getValue())
                : Optional.empty();
        if (owner.isPresent() && owner.get().isBlank()) {
            throw new IllegalStateException("HuskSync checkout owner is blank.");
        }
        return new PlayerNetworkState(owner, serverSwitch.hasValue());
    }

    private RedisURI createRedisUri(Settings.RedisSettings redisSettings) {
        Settings.RedisSettings.RedisCredentials credentials = redisSettings.getCredentials();
        Settings.RedisSettings.RedisSentinel sentinel = redisSettings.getSentinel();
        String sentinelMaster = sentinel.getMaster();
        List<String> sentinelNodes = sentinel.getNodes();
        boolean usesSentinel = (sentinelMaster != null && !sentinelMaster.isBlank())
                || (sentinelNodes != null && !sentinelNodes.isEmpty());

        RedisURI.Builder builder;
        if (usesSentinel) {
            if (sentinelMaster == null || sentinelMaster.isBlank()
                    || sentinelNodes == null || sentinelNodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "HuskSync Redis Sentinel requires a master and at least one node.");
            }
            builder = RedisURI.builder().withSentinelMasterId(sentinelMaster);
            for (String node : sentinelNodes) {
                SentinelAddress address = parseSentinelAddress(node);
                if (sentinel.getPassword() == null || sentinel.getPassword().isEmpty()) {
                    builder.withSentinel(address.host(), address.port());
                } else {
                    builder.withSentinel(address.host(), address.port(), sentinel.getPassword());
                }
            }
        } else {
            builder = RedisURI.Builder.redis(credentials.getHost(), credentials.getPort());
        }

        builder.withDatabase(credentials.getDatabase())
                .withSsl(credentials.isUseSsl());
        String password = credentials.getPassword();
        if (password != null && !password.isEmpty()) {
            String user = credentials.getUser();
            if (user == null || user.isBlank()) builder.withPassword(password);
            else builder.withAuthentication(user, password);
        }
        return builder.build();
    }

    private SentinelAddress parseSentinelAddress(String node) {
        if (node == null || node.isBlank()) {
            throw new IllegalArgumentException("Invalid HuskSync Redis Sentinel node.");
        }
        int separator = node.lastIndexOf(':');
        if (separator <= 0 || separator + 1 >= node.length()) {
            throw new IllegalArgumentException("Invalid HuskSync Redis Sentinel node.");
        }
        String host = node.substring(0, separator);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isBlank()) {
            throw new IllegalArgumentException("Invalid HuskSync Redis Sentinel node.");
        }
        int port = Integer.parseInt(node.substring(separator + 1));
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid HuskSync Redis Sentinel port.");
        }
        return new SentinelAddress(host, port);
    }

    private String encodeRequest(DeltaRequest request) {
        String payload = String.join("\t",
                REQUEST_MAGIC,
                request.requestId.toString(),
                request.playerId.toString(),
                Long.toString(request.createdAtMillis),
                encodeText(request.sourceServer),
                encodeText(request.targetServer),
                request.operation.type().name(),
                request.operation.variable() == null ? "0" : "1",
                encodeText(request.operation.variable() == null ? "" : request.operation.variable()),
                request.operation.operand() == null ? "0" : "1",
                encodeText(request.operation.operand() == null ? "" : request.operation.operand()));
        ensurePayloadSize(payload);
        return payload;
    }

    private DeltaRequest decodeRequest(String payload) {
        String[] fields = payload.split("\t", -1);
        if (fields.length != 11 || !REQUEST_MAGIC.equals(fields[0])) {
            throw new IllegalArgumentException("Invalid request frame.");
        }
        UUID requestId = UUID.fromString(fields[1]);
        UUID playerId = UUID.fromString(fields[2]);
        long createdAt = Long.parseLong(fields[3]);
        String source = boundedText(decodeText(fields[4]), 128);
        String target = boundedText(decodeText(fields[5]), 128);
        YovDeltaOperation.Type type = YovDeltaOperation.Type.valueOf(fields[6]);
        String variable = parseOptionalText(fields[7], fields[8], 256);
        String operand = parseOptionalText(fields[9], fields[10], maxPayloadBytes);
        return new DeltaRequest(requestId, playerId, createdAt, source, target,
                new YovDeltaOperation(type, variable, operand));
    }

    private String encodeAcknowledgement(DeltaAcknowledgement acknowledgement) {
        HuskSyncVariableStore.OfflineEditResult result = acknowledgement.result;
        List<String> fields = new ArrayList<>(10 + result.variables().size() * 2);
        Collections.addAll(fields,
                ACK_MAGIC,
                acknowledgement.requestId.toString(),
                acknowledgement.playerId.toString(),
                encodeText(acknowledgement.sourceServer),
                encodeText(acknowledgement.ownerServer),
                result.success() ? "1" : "0",
                result.message() == null ? "0" : "1",
                encodeText(result.message() == null ? "" : result.message()),
                Integer.toString(result.variables().size()));
        for (Map.Entry<String, String> entry : result.variables().entrySet()) {
            fields.add(encodeText(entry.getKey()));
            fields.add(encodeText(entry.getValue()));
        }
        String payload = String.join("\t", fields);
        ensurePayloadSize(payload);
        return payload;
    }

    private DeltaAcknowledgement decodeAcknowledgement(String payload) {
        String[] fields = payload.split("\t", -1);
        if (fields.length < 9 || !ACK_MAGIC.equals(fields[0])) {
            throw new IllegalArgumentException("Invalid acknowledgement frame.");
        }
        UUID requestId = UUID.fromString(fields[1]);
        UUID playerId = UUID.fromString(fields[2]);
        String source = boundedText(decodeText(fields[3]), 128);
        String owner = boundedText(decodeText(fields[4]), 128);
        boolean success = parseFlag(fields[5]);
        String detail = parseOptionalText(fields[6], fields[7], maxPayloadBytes);
        int count = Integer.parseInt(fields[8]);
        if (count < 0 || count > 4096 || fields.length != 9 + count * 2) {
            throw new IllegalArgumentException("Invalid acknowledgement map size.");
        }
        Map<String, String> variables = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String key = boundedText(decodeText(fields[9 + index * 2]), 256);
            String value = decodeText(fields[10 + index * 2]);
            variables.put(key, value);
        }
        HuskSyncVariableStore.OfflineEditResult result = success
                ? new HuskSyncVariableStore.OfflineEditResult(true, detail, variables)
                : HuskSyncVariableStore.OfflineEditResult.fail(
                        detail == null ? "The owning server rejected the variable edit." : detail);
        return new DeltaAcknowledgement(requestId, playerId, source, owner, result);
    }

    private String parseOptionalText(String presentField, String valueField, int maxCharacters) {
        boolean present = parseFlag(presentField);
        String value = decodeText(valueField);
        if (!present) {
            if (!value.isEmpty()) throw new IllegalArgumentException("Unexpected optional value.");
            return null;
        }
        return boundedText(value, maxCharacters);
    }

    private boolean parseFlag(String field) {
        return switch (field) {
            case "1" -> true;
            case "0" -> false;
            default -> throw new IllegalArgumentException("Invalid flag.");
        };
    }

    private String encodeText(String value) {
        return BASE64_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeText(String value) {
        return new String(BASE64_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    private String boundedText(String value, int maxCharacters) {
        if (value == null
                || (value.isBlank() && maxCharacters <= 256)
                || value.length() > maxCharacters) {
            throw new IllegalArgumentException("Invalid bounded text field.");
        }
        return value;
    }

    private void ensurePayloadSize(String payload) {
        if (payload.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
            throw new IllegalArgumentException("YOV Redis payload exceeds its configured limit.");
        }
    }

    private int boundedInt(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private long boundedLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            acceptingInbound = false;
            active = false;
        }

        HuskSyncVariableStore.OfflineEditResult failure =
                HuskSyncVariableStore.OfflineEditResult.fail(
                        "YOV storage closed before the cross-server edit was confirmed; "
                                + "the outcome is unknown. Do not automatically retry a numeric mutation.");
        for (PendingRequest pending : pendingRequests.values()) pending.future.complete(failure);
        pendingRequests.clear();
        pendingAcknowledgements.clear();
        synchronized (receiptLock) {
            for (OwnerReceipt receipt : ownerReceipts.values()) receipt.future.complete(failure);
            ownerReceipts.clear();
        }
        handler = null;
        closeConnections();
    }

    private void closeConnections() {
        StatefulRedisPubSubConnection<String, String> currentSubscriber = subscriber;
        subscriber = null;
        if (currentSubscriber != null) {
            try {
                if (currentSubscriber.isOpen()) {
                    currentSubscriber.sync().unsubscribe(requestChannel, acknowledgementChannel);
                }
            } catch (Throwable ignored) {
            }
            try {
                currentSubscriber.close();
            } catch (Throwable ignored) {
            }
        }

        StatefulRedisConnection<String, String> currentCommands = commands;
        commands = null;
        if (currentCommands != null) {
            try {
                currentCommands.close();
            } catch (Throwable ignored) {
            }
        }

        RedisClient currentClient = client;
        client = null;
        if (currentClient != null) {
            try {
                currentClient.shutdown();
            } catch (Throwable error) {
                plugin.getLogger().log(Level.FINE,
                        "Failed to completely close the YOV Redis delta client.", error);
            }
        }
        initialized = false;
    }

    private record RequestSignature(UUID playerId, String sourceServer, String targetServer,
                                    YovDeltaOperation operation) {
    }

    private record ReceiptSnapshot(UUID requestId, RequestSignature signature,
                                   HuskSyncVariableStore.OfflineEditResult result,
                                   long completedAtMillis) {
    }

    private enum DurableClaim {
        ACQUIRED,
        DUPLICATE,
        COLLISION,
        UNKNOWN
    }

    record PlayerNetworkState(Optional<String> ownerServer, boolean serverSwitchInProgress) {
        PlayerNetworkState {
            ownerServer = Objects.requireNonNull(ownerServer, "ownerServer");
        }
    }

    private record SentinelAddress(String host, int port) {
    }

    private record DeltaRequest(UUID requestId, UUID playerId, long createdAtMillis,
                                String sourceServer, String targetServer,
                                YovDeltaOperation operation) {
        private DeltaRequest {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(playerId, "playerId");
            if (sourceServer == null || sourceServer.isBlank()
                    || targetServer == null || targetServer.isBlank()) {
                throw new IllegalArgumentException("Delta server names must not be blank.");
            }
            Objects.requireNonNull(operation, "operation");
        }

        RequestSignature signature() {
            return new RequestSignature(playerId, sourceServer, targetServer, operation);
        }
    }

    private record DeltaAcknowledgement(UUID requestId, UUID playerId,
                                        String sourceServer, String ownerServer,
                                        HuskSyncVariableStore.OfflineEditResult result) {
    }

    private static final class PendingRequest {
        private final RequestSignature signature;
        private final CompletableFuture<HuskSyncVariableStore.OfflineEditResult> future =
                new CompletableFuture<>();

        private PendingRequest(RequestSignature signature) {
            this.signature = signature;
        }
    }

    private static final class OwnerReceipt {
        private final RequestSignature signature;
        private volatile CompletableFuture<HuskSyncVariableStore.OfflineEditResult> future =
                new CompletableFuture<>();
        private final CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        private long completedAtMillis;

        private OwnerReceipt(RequestSignature signature) {
            this.signature = signature;
        }
    }
}
