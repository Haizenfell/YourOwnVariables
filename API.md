# YourOwnVariables Java API

This reference describes the public API of YourOwnVariables 6.0. For installation, server configuration, commands, and PlaceholderAPI syntax, see the project README.

YOV stores string variables for players. Commands, placeholders, and the Java API use the same UUID-based player data, persisted as HuskSync custom data. There are no world or global variables.

## Dependency and API access

Compile against the YOV JAR as a `provided` dependency. Do not shade or relocate YOV classes into your plugin.

Declare a hard dependency in your plugin's `src/main/resources/plugin.yml` if YOV is required:

```yaml
depend: [YourOwnVariables]
```

Use `softdepend` only if your plugin also supports running without YOV:

```yaml
softdepend: [YourOwnVariables]
```

Obtain the API from Bukkit's services registry after YOV has enabled:

```java
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import yov.api.YOVApi;

RegisteredServiceProvider<YOVApi> registration =
        Bukkit.getServicesManager().getRegistration(YOVApi.class);

if (registration == null) {
    throw new IllegalStateException("YourOwnVariables API is unavailable");
}

YOVApi yov = registration.getProvider();
```

With a hard dependency, the plugin instance also exposes `getApi()`:

```java
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import yov.YOVPlugin;
import yov.api.YOVApi;

Plugin candidate = Bukkit.getPluginManager().getPlugin("YourOwnVariables");
if (candidate instanceof YOVPlugin yovPlugin && yovPlugin.isEnabled()) {
    YOVApi yov = yovPlugin.getApi();
}
```

The API provider remains stable across a successful `/yov reload`; it delegates to the current service. A full plugin disable or replacement invalidates the provider. Reacquire it when handling such a lifecycle change.

## Player identity and variable names

Use UUID overloads whenever the UUID is known. Player names are lookup conveniences, not storage keys, and name-based overloads depend on the names known to the local server or HuskSync. YOV does not create an arbitrary new HuskSync player record for an unknown name or UUID.

Variable names are trimmed and converted to lowercase using `Locale.ROOT`. An empty normalized name is invalid. `normalizeVariable(String)` returns the normalized name, or `null` for a null or empty input. Size limits are checked separately when data is loaded or changed.

Values are strings. `setVariable` preserves the supplied value; the Java API is not restricted to the command's single-token value syntax. Use simple names such as `coins`, `vote_total`, or `rank_level` for convenient use in commands and placeholders.

Returned maps are immutable snapshots. They do not update when a later operation changes the player's data. Read again if you need a newer snapshot.

## Threading and futures

Fast local reads access published data in memory and do not perform Redis or database requests. They can be used from asynchronous code.

Asynchronous reads, readiness waits, and mutations can also be called from any thread. YOV schedules work that needs Bukkit onto the main server thread. Their `CompletableFuture` callbacks may execute on the calling thread, the main thread, or an asynchronous completion thread; there is no general callback-thread guarantee.

Schedule Bukkit interactions back onto the main thread:

```java
UUID playerId = player.getUniqueId();

yov.getCurrentVariable(playerId, "coins").whenComplete((value, error) -> {
    Bukkit.getScheduler().runTask(plugin, () -> {
        if (error != null) {
            plugin.getLogger().warning("Could not read coins: " + error.getMessage());
            return;
        }
        if (Bukkit.getPlayer(playerId) != player) {
            return;
        }
        player.sendMessage("Coins: " + value.orElse("0"));
    });
});
```

Do not call `join()` or `get()` on an unfinished YOV future from the main server thread. The pending work may need that thread, and storage requests can take time.

Cancelling or manually completing a returned future does not cancel or roll back work that has already started. In particular, cancelling an in-progress mutation does not prove that the value was left unchanged. YOV keeps accounting for the internal operation until it finishes.

## Fast local reads

Use these methods for frequently accessed player data, menus, and statistics when the player is connected to the current backend:

```java
UUID playerId = player.getUniqueId();

String coins = yov.getVariable(playerId, "coins");
String displayedCoins = yov.getVariableOrDefault(playerId, "coins", "0");
Map<String, String> variables = yov.getVariables(playerId);
```

| Method | Result |
| --- | --- |
| `getVariable(UUID playerId, String variable)` | Local value, or `null` if the variable is absent or the player is not locally ready. |
| `getVariable(String playerName, String variable)` | The same local lookup using a player name. |
| `getVariableOrDefault(UUID playerId, String variable, String defaultValue)` | Local value, or the supplied fallback when `getVariable` returns `null`. |
| `getVariableOrDefault(String playerName, String variable, String defaultValue)` | The same fallback lookup using a player name. |
| `getVariables(UUID playerId)` | Immutable local snapshot, or an empty map before readiness or when no local session exists. |
| `getVariables(String playerName)` | The same snapshot lookup using a player name. |

These methods never load offline or remote data. A fallback value does not mean that synchronization has finished: it also applies while the player is loading. If initialization depends on loaded data, wait for readiness first.

`getVariable` performs a lookup against an immutable published snapshot without taking the player mutation lock. `getVariables` creates a defensive snapshot and should not replace single-value reads in a hot loop.

## Waiting for player readiness

The `READY` state means that HuskSync has applied the player's snapshot, YOV has merged pending changes and mandatory defaults, and HuskSync's player lock has been released. YOV only publishes local variables after those steps.

`PlayerJoinEvent` is too early for data-dependent initialization because HuskSync begins asynchronous synchronization around this stage. Listen for YOV's synchronous Bukkit event instead:

```java
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import yov.api.event.YOVPlayerReadyEvent;

public final class PlayerDataListener implements Listener {

    @EventHandler
    public void onYovReady(YOVPlayerReadyEvent event) {
        String coins = event.getVariables().getOrDefault("coins", "0");
        event.getPlayer().sendMessage("Coins: " + coins);
    }
}
```

Register the listener through Bukkit's plugin manager. The event exposes `getPlayer()` and an immutable `getVariables()` snapshot. It is fired after a successful synchronization; it is not an event for every mutation. Players already online when YOV starts or reloads can be bootstrapped without a new readiness event. Plugins enabling late should also handle existing players through the API.

For a current local session, use `whenPlayerReady(UUID)`:

```java
UUID playerId = player.getUniqueId();

yov.whenPlayerReady(playerId).whenComplete((variables, error) -> {
    Bukkit.getScheduler().runTask(plugin, () -> {
        if (error != null) {
            plugin.getLogger().warning("YOV readiness failed: " + error.getMessage());
            return;
        }
        if (Bukkit.getPlayer(playerId) != player || !yov.isPlayerReady(playerId)) {
            return;
        }
        player.sendMessage("Coins: " + variables.getOrDefault("coins", "0"));
    });
});
```

The future completes immediately if the current session is already ready. It fails if there is no active local session, synchronization fails, or the session ends before readiness. It does not wait for a future login by an offline player and does not track later reconnects. Each caller receives a separate dependent future.

`isPlayerReady(UUID)` checks the current state without waiting. It cannot make a later action atomic with the readiness check, so keep session identity in mind when scheduling delayed work.

HuskSync restricts player actions while applying data. YOV cannot postpone another plugin's `PlayerJoinEvent` handler; dependent plugins must cooperate through the event or future. A backend plugin also cannot guarantee that the client stays on the actual loading screen. That requires a proxy-side waiting or limbo module.

## Current-data reads

Use current-data reads when the player may be offline or connected to a different backend:

```java
yov.getCurrentVariable(playerId, "coins").thenAccept(value -> {
    plugin.getLogger().info("Coins: " + value.orElse("0"));
});

yov.getCurrentVariables(playerId).thenAccept(variables -> {
    plugin.getLogger().info("Variables: " + variables.size());
});
```

| Method | Result |
| --- | --- |
| `getCurrentVariable(UUID playerId, String variable)` | `CompletableFuture<Optional<String>>` for one value. |
| `getCurrentVariable(String playerName, String variable)` | The same read using HuskSync's player-name lookup. |
| `getCurrentVariables(UUID playerId)` | `CompletableFuture<Map<String, String>>` containing an immutable snapshot. |
| `getCurrentVariables(String playerName)` | The same snapshot read using a player name. |

For a local online player, these methods await the current session's readiness and read the resulting snapshot. Otherwise, they wait for earlier accepted YOV offline writes in this server process and use HuskSync's current-data API. Missing configured defaults are added to the returned data. A read does not by itself persist that default backfill.

An absent key produces `Optional.empty()`. A missing HuskSync player record, storage failure, or timeout can complete the future exceptionally. These conditions must not be treated as a confirmed zero balance or an absent variable.

The user-lookup and data-read timeouts are configured under `husksync.offline-user-lookup-timeout-millis` and `husksync.offline-data-read-timeout-millis`. A read queued after an accepted write still waits for that write's completion to preserve local ordering.

For a player active on another backend, HuskSync determines the current snapshot available to its API. This is not a transactional RPC read of that backend's latest in-memory YOV value, and it does not provide globally linearizable reads. Prefer local ready reads on the backend owning the session for live menus and gameplay.

## Mutations

All mutation methods return `CompletableFuture<YOVOperationResult>`. Each has a UUID overload and a player-name overload; UUID is preferred.

| Method | Behavior |
| --- | --- |
| `setVariable(UUID/String player, String variable, String value)` | Stores the supplied string. |
| `addVariable(UUID/String player, String variable, BigDecimal amount)` | Adds a decimal amount. A missing variable starts at `0`. |
| `addVariable(UUID/String player, String variable, String amount)` | Parses a trimmed decimal string and adds it. |
| `remVariable(UUID/String player, String variable, BigDecimal amount)` | Subtracts a decimal amount. A missing variable starts at `0`. |
| `remVariable(UUID/String player, String variable, String amount)` | Parses a trimmed decimal string and subtracts it. |
| `deleteVariable(UUID/String player, String variable)` | Removes one key. |
| `clearVariables(UUID/String player)` | Removes all variables for one player. |

`UUID/String` in the table denotes two overloads, not a Java parameter type.

```java
import java.math.BigDecimal;

yov.addVariable(playerId, "coins", new BigDecimal("10.50"))
        .whenComplete((result, error) -> {
            if (error != null) {
                plugin.getLogger().warning("Coin update failed: " + error.getMessage());
                return;
            }
            if (result.failed()) {
                plugin.getLogger().warning(result.message());
                return;
            }
            plugin.getLogger().info("New coins value: " + result.value());
        });
```

Numeric operations use `BigDecimal`. They fail if an existing value is not numeric or the operand/result exceeds configured size protections. YOV does not impose economy rules such as nonnegative balances or positive amounts; apply those rules in your own integration. A separate read followed by a write is not an atomic conditional update.

### Local online players

For a ready local player, YOV updates HuskSync's active custom-data store before publishing the new local snapshot. Success confirms this active session update; it does not mean that a new durable SQL snapshot has already been written. HuskSync controls online persistence and server-transfer saves.

If a local player is still synchronizing, accepted changes are queued, merged into the incoming data, and completed when their application is resolved. Normally their futures complete when the final snapshot is published. In a partial failure, changes already applied by HuskSync can still report success while the session itself fails readiness. Do not use mutation completion as a substitute for `whenPlayerReady`.

### Players on another backend

YOV reads HuskSync's ownership and server-switch markers. When another server owns the player, YOV sends a typed `SET`, `ADD`, `REMOVE`, `DELETE`, or `CLEAR` request over its Redis channel. The receiving server checks the UUID session and readiness again before applying the operation.

This path does not apply an old full player snapshot to an online player. Unrelated data such as inventory, location, and experience is not replaced by the variable edit. The owning backend must run a compatible YOV instance, and all participating backends must share the required HuskSync cluster, Redis, database, and unique server identifiers.

### Confirmed offline players

Within one server process, offline mutations are queued per UUID. YOV checks ownership and server-switch markers, reads the latest database snapshot, changes YOV's custom data, and inserts a new HuskSync snapshot. It verifies the inserted snapshot ID and database head, and invalidates the offline login cache only while Redis atomically confirms that no server owns or is transferring the player.

Standard HuskSync SQL timestamps can have one-second precision. Consecutive offline edits may wait asynchronously for the next timestamp interval rather than create ambiguous ordering or future-dated data.

These checks reduce races but are not a distributed transaction. HuskSync 4.1 does not expose a compare-and-swap operation covering its SQL and Redis state. Concurrent offline edits of the same UUID from different YOV servers can still overwrite one another. If a balance requires strict cross-server accounting, route its offline writes through one responsible service or use a transactional store designed for that purpose.

### Ambiguous results and retries

YOV reserves cross-server request IDs in Redis and keeps receipts for a bounded time to prevent duplicate delivery from applying the same numeric request twice. This is internal protocol protection; each new public API call is a new operation. It is not a permanent exactly-once guarantee across Redis data loss, expired receipts, or arbitrary application retries.

A network timeout, lost acknowledgement, cancellation, failed snapshot confirmation, or concurrent login can leave the outcome unknown. YOV reports this where it can detect it and attempts to remove provisional offline snapshots where appropriate. A rollback cannot prove that no server already consumed the provisional data.

Do not automatically retry `addVariable` or `remVariable` after an ambiguous outcome: the first operation may have applied. Read and reconcile the player's data or use an application-level transaction record before deciding what to do next. Repeating `setVariable` can also overwrite an intervening update, even though setting the same value is otherwise idempotent.

During `/yov reload`, new mutations are briefly rejected while accepted work drains. If draining cannot complete safely, the reload is aborted. In-flight API work may also fail during plugin disable, so handle both failed operation results and exceptional completions.

## Operation results

`YOVOperationResult` is a record with the following accessors:

| Accessor | Meaning |
| --- | --- |
| `success()` | Whether YOV reports the operation as applied. See the online durability and ambiguous-outcome notes above. |
| `failed()` | Convenience inverse of `success()`. |
| `message()` | Human-readable success or failure text. It is not a stable machine-readable error code. |
| `value()` | For set/add/rem, the resulting string; for delete, the previous value if present; for clear, the removed-key count as a string. May be `null`. |
| `variables()` | Immutable resulting snapshot when included. Empty on failure; it can also be empty after a successful clear or when a cross-server acknowledgement omits the snapshot to fit payload limits. |

Do not infer success from a nonempty snapshot, and do not parse message text as a long-term protocol. Inspect `success()` and handle the future's exceptional completion separately. The API currently has no structured status distinguishing every rejection from an unknown outcome.

For multiple queued mutations, `value()` describes the individual operation's result, while `variables()` can contain the final merged snapshot after other accepted operations.

## Defaults and safety limits

`src/main/resources/default_variables.yml` defines mandatory initial/backfill values under `player_variables`. Missing keys are restored whenever player data loads and are included in current-data reads and mutation preparation. Existing values are preserved.

Defaults are not undeletable keys. `deleteVariable` or `clearVariables` can remove them from the current snapshot, but they can return during a later load or backfill. Do not use deletion of a configured default as a permanent opt-out. Use an explicit stored value for that state.

The same limits apply to commands, API mutations, configured defaults, and incoming HuskSync data:

| Configuration key | Default |
| --- | --- |
| `limits.max-variables-per-player` | `256` |
| `limits.max-variable-name-bytes` | `128` UTF-8 bytes |
| `limits.max-value-bytes` | `16384` UTF-8 bytes |
| `limits.max-data-bytes-per-player` | `262144` estimated serialized bytes |
| `limits.max-pending-online-mutations-per-player` | `256` |
| `limits.max-pending-offline-mutations-per-player` | `64` |
| `limits.max-pending-offline-mutations-global` | `2048` |
| `limits.max-pending-api-calls-global` | `8192` |

Nonpositive settings fall back to the built-in defaults; they do not disable the limit. Numeric calculations also check the potential plain-text output size and scale gap before allocating large values.

An oversized mutation is rejected without publishing a partially changed local snapshot. Invalid defaults abort startup or reload. Oversized incoming player data fails synchronization instead of being silently truncated. Allow enough space for the player's stored variables plus every missing configured default.

Queue admission failures can be returned as unsuccessful operation results or, for asynchronous API-call admission, as exceptional futures such as `RejectedExecutionException`. Current-data reads and thread-bridge tasks are also bounded. Apply backpressure in your integration instead of repeatedly submitting more work while the limit is reached.

## State and discovery methods

| Method | Meaning |
| --- | --- |
| `isPlayerReady(UUID playerId)` | Whether the current local session has a ready published snapshot. |
| `whenPlayerReady(UUID playerId)` | Future for the current local session's immutable ready snapshot. |
| `hasPlayerSnapshot(UUID playerId)` | Ready local snapshot check; currently equivalent to `isPlayerReady`. |
| `hasPlayerSnapshot(String playerName)` | Name-based ready local snapshot check. |
| `isPlayerLoaded(String playerName)` | Deprecated compatibility method for local readiness; prefer UUID-based `isPlayerReady`. |
| `getKnownVariableNames()` | Sorted names from ready local players and configured defaults, not every offline player in the network. |
| `normalizeVariable(String variable)` | Trimmed lowercase name, or `null` when empty/null. |

## Placeholder-only integrations

Prefer the Java API when you control the dependent plugin. It avoids placeholder parsing and offers explicit readiness and error handling.

If an integration can only read PlaceholderAPI, `%yov_player_key:<variable>%` and the `%rounded_player_key...%` forms return the configured `placeholders.loading-value` while the local player is not ready or no player context is provided. After readiness, an absent variable returns the literal string `null`; rounded placeholders also return it for invalid numeric input or unsupported output size.

There is no `%yov_player_loaded%` placeholder. A placeholder consumer must treat the loading marker as unavailable data and avoid triggering actions from it. If an action must reliably wait for readiness, use `YOVPlayerReadyEvent` or `whenPlayerReady(UUID)`.

The optional login-preload check is disabled by default. It checks storage availability before login and does not complete HuskSync synchronization or make local API data ready.
