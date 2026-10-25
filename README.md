# YourOwnVariables (YOV)

YourOwnVariables is a Paper plugin for player-specific string variables, backed by HuskSync. It gives menus, statistics, progression systems, and other plugins a shared way to read and update player data through PlaceholderAPI, commands, and a Java API.

YOV manages player variables only. It does not provide world, global, or server variables, and it does not require a separate player database.

## Features

- UUID-based sessions, with player-name lookup for commands and compatibility methods.
- Fast reads from immutable local snapshots without Redis or database queries.
- A readiness event and future that expose data after HuskSync synchronization and player unlock.
- Queued mutations while a local player is synchronizing.
- Variable-only operations routed to the backend owning a remote player.
- Missing default variables restored on every load.
- Decimal arithmetic with BigDecimal and optional rounded placeholders.
- Configurable data and queue limits, plus runtime counters.
- An API provider that remains valid across YOV's own reload.

## Requirements

The project targets these versions:

| Component | Version |
| --- | --- |
| Java | 26 or newer |
| Paper API | `26.3.build.205-stable` |
| HuskSync | `4.1.0+26.3` |
| PlaceholderAPI | `2.12.3` |

HuskSync and PlaceholderAPI are required dependencies. HuskSync must use `synchronization.mode: LOCKSTEP`. YOV refuses to initialize in `DELAY` mode because it cannot safely identify the backend owning a player in that mode.

Paper downloads and caches the Lettuce library declared in `plugin.yml`. It is not a separate server plugin.

## Installation and network setup

1. Install compatible HuskSync and PlaceholderAPI versions on each participating Paper backend.
2. Configure HuskSync's database, Redis connection, and `LOCKSTEP` synchronization mode.
3. Assign a unique HuskSync server name to every backend.
4. Put the YOV JAR in each backend's `plugins` directory.
5. Start the servers, then edit `plugins/YourOwnVariables/config.yml` and `default_variables.yml`.
6. Apply YOV configuration changes with `/yov reload`.

Install YOV on every Paper backend that reads or changes variables, including spawn servers. Do not install it on Velocity.

Backends sharing variables must use the same logical HuskSync cluster, Redis, and database. Backend names must be unique across the entire shared cluster. For example, `spawn-eu`, `survival-eu`, `spawn-us`, and `survival-us` can share one data set while two Velocity proxies route players to them. Independent HuskSync clusters have independent variables.

The Redis account must allow YOV's request/ack channels, expiring receipt keys, the relevant HuskSync keys, and `EVAL` for guarded offline cache invalidation. Cross-region operations depend on the latency and availability of the shared infrastructure.

## Storage and variable names

Variables are stored in HuskSync custom data under:

```text
yov:variables
```

Each player has a map of variable names to string values.

- UUID identifies the session; names are used for lookup and display.
- Variable names are trimmed and lower-cased.
- Empty names and null values are invalid.
- Values remain strings, including values used as numbers.
- Command names and values occupy one argument each; Java API values may contain spaces.
- API maps are immutable snapshots.

An online mutation updates the local snapshot and HuskSync's live custom data store. It follows HuskSync's normal save lifecycle: online success does not mean that a separate SQL commit occurred immediately for that variable change.

## Commands

All commands require `yov.admin`, which defaults to operators.

| Command | Purpose |
| --- | --- |
| `/yov help` | Show command help. |
| `/yov reload` | Reload configuration, defaults, and runtime hooks. |
| `/yov status` | Show sessions, reads, synchronization, mutation counters, and queues. |
| `/yov set <variable> <value> [player] [-s]` | Set one string value. |
| `/yov add <variable> <amount> [player] [-s]` | Add a decimal amount. |
| `/yov rem <variable> <amount> [player] [-s]` | Subtract a decimal amount. |
| `/yov delete <variable> [player] [-s]` | Delete one variable. |
| `/yov check <variable> [player]` | Read a current value. |
| `/yov userclear <player>` | Clear a player's variables. |

`remove` is an alias for `rem`; `del` is an alias for `delete`.

If a target is omitted, the sender must be a player and the command targets that player. Console commands must specify the target as the final positional argument, before an optional `-s`. `userclear` always requires a target.

The `set` value is exactly one argument without spaces. For example, `/yov set greeting hello Steve` stores `hello` for Steve; it does not store `hello Steve` for the sender.

The trailing `-s` flag suppresses feedback for `set`, `add`, `rem`, and `delete`.

```text
/yov set coins 100
/yov set coins 100 Steve
/yov set greeting hello Steve
/yov add coins 25 Steve -s
/yov rem coins 10 Steve
/yov delete greeting Steve
/yov check coins Steve
/yov userclear Steve
```

Numeric operations treat a missing value as zero and reject an existing non-numeric value. Arithmetic uses BigDecimal and remains subject to the configured limits.

A nonlocal target must be known to HuskSync and have stored data for a confirmed-offline snapshot edit.

## Placeholders

| Placeholder | Result |
| --- | --- |
| `%yov_player_key:coins%` | The ready string value of `coins`. |
| `%rounded_player_key:coins%` | The numeric value rounded to zero decimal places. |
| `%rounded_player_key_2:coins%` | The numeric value rounded to two decimal places. |

Rounded placeholders use `HALF_UP`; explicit decimal places range from 0 to 10. Missing, non-numeric, or excessively large rounded results return the literal string `null`.

Before synchronization completes, or without a player context, placeholders return `placeholders.loading-value`, which defaults to `loading`. After readiness, a missing variable returns the literal string `null`.

There is no separate readiness placeholder, and forms such as `%yov_coins%` are unsupported. A display placeholder is not a reliable trigger for initializing another plugin; use the Java readiness API when an action must wait for data.

## Player loading and readiness

HuskSync synchronizes data after `PlayerJoinEvent`. YOV stages incoming variables, applies missing defaults and queued changes, then publishes the final map after synchronization and player unlock.

Use `YOVPlayerReadyEvent` or `YOVApi.whenPlayerReady(UUID)` for variable-dependent initialization. `YOVApi.isPlayerReady(UUID)` provides a non-blocking state check.

Fast reads return `null`, an empty map, or a supplied fallback while data is unavailable. These results do not prove that the player has no stored data.

The synchronous ready event can occur again after another successful synchronization. It is not emitted for ordinary bootstrap of already-online players. Plugins enabled after a session became ready should also initialize existing players through the readiness API.

HuskSync blocks player actions during synchronization, but YOV cannot stop unrelated plugins from running their join handlers. Those plugins must wait for readiness when they depend on variables.

A Paper plugin alone cannot hold the client on a true pre-join loading screen during HuskSync's post-join synchronization. That presentation requires a proxy-side loading or limbo module.

## Default variables

Configure defaults in `default_variables.yml`:

```yaml
player_variables:
  coins: "0"
  claim_points: "200"
  greeting: "hello"
```

Missing defaults are restored on every HuskSync load without overwriting existing values. They also appear in current-data API read results and form the starting point for offline edits. Adding a default to a read result does not itself persist that result.

Explicitly deleting a default or clearing the map can remove it from a ready session. It is restored on the next load; defaults remain mandatory during loading.

Defaults and incoming maps must satisfy the data limits. Invalid defaults abort startup or reload. If player data plus missing defaults exceeds a limit, synchronization fails without truncating the data. Allow enough capacity for both normal variables and required defaults.

## Configuration

The full [config.yml](src/main/resources/config.yml) includes English comments. Settings ending in `-millis` use milliseconds; `-ticks` settings use server ticks.

### Synchronization and storage

| Setting | Default | Purpose |
| --- | --- | --- |
| `husksync.unload-delay-ticks` | `40` | Retain a retired session object for late-callback safety; it is no longer READY. |
| `husksync.shutdown-wait-millis` | `5000` | Grace period for accepted work during reload or storage shutdown. |
| `husksync.offline-user-lookup-timeout-millis` | `5000` | Bound user lookup time. |
| `husksync.offline-data-read-timeout-millis` | `5000` | Bound current-data read time. |
| `husksync.sync-timeout-ticks` | `600` | Synchronization watchdog; `0` disables it. |
| `husksync.disconnect-on-sync-failure` | `true` | Disconnect players whose synchronization fails. |
| `husksync.sync-failure-message` | Bundled reconnect message | Message shown on that disconnection. |

An accepted database write is awaited without an artificial write timeout, preventing the next queued write from overtaking it. Lookup and read timeouts do not change this rule.

### Cross-server request channel

These settings are under `husksync.delta-rpc`.

| Setting | Default | Purpose |
| --- | --- | --- |
| `timeout-millis` | `5000` | Wait for the remote operation result. |
| `max-request-age-millis` | `30000` | Maximum accepted request age. |
| `max-payload-bytes` | `524288` | Maximum protocol payload size. |
| `max-pending-requests` | `2048` | Bound outgoing pending requests. |
| `receipt-cache-size` | `4096` | Bound the owner's in-memory request receipt cache. |
| `receipt-ttl-millis` | `300000` | Retain receipts and expiring Redis request claims. |

The receipt TTL is raised when necessary to cover the maximum request age. New work can be rejected when request or receipt capacity is exhausted.

### Optional login preload

These settings are under `husksync.login-preload`.

| Setting | Default | Purpose |
| --- | --- | --- |
| `enabled` | `false` | Perform a preliminary availability check before login. |
| `timeout-millis` | `5000` | Bound preliminary check time. |
| `disconnect-on-failure` | `true` | Reject login when this check fails. |
| `timeout-message` | Bundled loading message | Message shown when the check times out. |
| `failure-message` | Bundled failure message | Message shown when the check fails. |
| `unused-cleanup-delay-ticks` | `200` | Cleanup delay for unused preload attempts. |

This check does not perform full HuskSync synchronization and cannot replace the authoritative post-join snapshot. Leave it disabled when the extra read on every backend switch is unnecessary.

### Placeholder loading value

`placeholders.loading-value` defaults to `loading`. Keep it distinct from `null` if another plugin treats missing values as an action trigger.

### Safety limits

These settings are under `limits`.

| Setting | Default |
| --- | --- |
| `max-variables-per-player` | `256` |
| `max-variable-name-bytes` | `128` |
| `max-value-bytes` | `16384` |
| `max-data-bytes-per-player` | `262144` |
| `max-pending-online-mutations-per-player` | `256` |
| `max-pending-offline-mutations-per-player` | `64` |
| `max-pending-offline-mutations-global` | `2048` |
| `max-pending-api-calls-global` | `8192` |

Name and value limits count UTF-8 bytes. The aggregate limit estimates serialized JSON size. Limits apply to commands, API writes, defaults, and incoming snapshots. Rejected mutations do not publish a partially edited map.

## Java API

The complete method reference and integration guide are in [API.md](API.md).

### Dependency and access

Declare a dependency in the consuming plugin:

```yaml
depend: [YourOwnVariables]
```

Compile against YOV with `provided` scope; do not shade its classes. Build with `mvn clean install` to install the artifact locally, then reference it:

```xml
<dependency>
    <groupId>yov</groupId>
    <artifactId>YourOwnVariables</artifactId>
    <version>6.0</version>
    <scope>provided</scope>
</dependency>
```

Get the registered provider through Bukkit:

```java
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import yov.api.YOVApi;

RegisteredServiceProvider<YOVApi> registration =
        Bukkit.getServicesManager().getRegistration(YOVApi.class);
YOVApi yov = registration == null ? null : registration.getProvider();
```

Do not use the API if no provider is registered. The provider remains valid across YOV's own reload.

### Reading and waiting

For frequent local reads after readiness:

```java
String coins = yov.getVariable(player.getUniqueId(), "coins");
Map<String, String> variables = yov.getVariables(player.getUniqueId());
```

Initialize dependent state from the ready event:

```java
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import yov.api.event.YOVPlayerReadyEvent;

public final class PlayerDataListener implements Listener {
    @EventHandler
    public void onReady(YOVPlayerReadyEvent event) {
        String coins = event.getVariables().getOrDefault("coins", "0");
        event.getPlayer().sendMessage("Coins: " + coins);
    }
}
```

Alternatively, call `whenPlayerReady(UUID)`. For nonlocal players, use `getCurrentVariable` or `getCurrentVariables`; remote results depend on HuskSync's current-data lookup and are not a transactional owner-side read.

| Method family | Behavior |
| --- | --- |
| `getVariable(UUID/String, variable)` | Immediate ready local value, or `null`. |
| `getVariableOrDefault(UUID/String, variable, fallback)` | Immediate local value, or the supplied fallback. |
| `getVariables(UUID/String)` | Immutable ready local map, or an empty map. |
| `whenPlayerReady(UUID)` | Future for the current local session's ready map. |
| `getCurrentVariable(UUID/String, variable)` | Future containing the current value as an Optional. |
| `getCurrentVariables(UUID/String)` | Future containing an immutable current map. |
| `isPlayerReady(UUID)` / `hasPlayerSnapshot(UUID/String)` | Non-blocking local state checks. |
| `getKnownVariableNames()` | Names from ready local sessions and defaults. |
| `normalizeVariable(name)` | Normalized name, or `null` if invalid. |

`UUID/String` denotes separate UUID and player-name overloads. Prefer UUID when available. The deprecated `isPlayerLoaded(String)` remains a compatibility alias.

### Mutations and threading

Use `setVariable`, `addVariable`, `remVariable`, `deleteVariable`, or `clearVariables`. Each supports UUID and name targets and returns `CompletableFuture<YOVOperationResult>`. Numeric methods accept BigDecimal or string amounts.

```java
import java.math.BigDecimal;

yov.addVariable(player.getUniqueId(), "coins", new BigDecimal("25.5"))
        .thenAccept(result -> {
            if (result.success()) {
                plugin.getLogger().info("New balance: " + result.value());
            } else {
                plugin.getLogger().warning(result.message());
            }
        })
        .exceptionally(error -> {
            plugin.getLogger().warning(error.getMessage());
            return null;
        });
```

The result exposes `success()`, `failed()`, `message()`, `value()`, and `variables()`. For set/add/rem, the value is the result; for delete, the previous value when known; for clear, the removed count. The map is immutable and can be empty when unavailable, even for a successful remote operation.

Fast reads do not query storage. Mutations and asynchronous reads may be called from any thread; YOV schedules Bukkit-sensitive work as needed. Completion callbacks are not generally guaranteed to use the server thread. Schedule Bukkit work explicitly and never block the server thread with `join()` or `get()`.

## Cross-server guarantees and limitations

A local ready mutation updates active memory and HuskSync's live data. A mutation during local synchronization is queued and completes after it is applied or rejected.

For a player on another backend, YOV checks HuskSync's owner markers and sends a typed `SET`, `ADD`, `REMOVE`, `DELETE`, or `CLEAR` operation. The owner rechecks its local UUID session and readiness. A variable edit does not push a full player snapshot to a live player.

For a confirmed-offline player, YOV reads the latest database snapshot, checks presence and the database head around saving, confirms the inserted snapshot by ID, and invalidates the old login cache only while the player remains offline. Consecutive edits can wait asynchronously for the next timestamp second because HuskSync's default SQL schema cannot reliably order equal timestamps.

Ordering is local to one YOV process. Simultaneous offline read-modify-write operations for the same UUID from different servers do not have a distributed compare-and-set transaction: a later snapshot can overwrite an earlier change.

Remote request IDs are claimed in Redis and retained for the configured TTL. Repeated delivery of the same request is deduplicated, including during reload and short owner restarts while the claim remains valid. A lost result produces an unknown outcome rather than automatic re-execution.

A timeout or a database/login race can leave the outcome unknown. Do not automatically submit another `add` or `rem`: the original change may already have been applied. Read and reconcile the state first. YOV is not a general transactional economy ledger.

## Reloading and troubleshooting

During `/yov reload`, new mutations are briefly rejected while accepted work drains. If it does not finish within `shutdown-wait-millis`, reload is aborted and the existing runtime resumes. Invalid configuration or defaults also prevent a successful replacement.

`/yov status` reports session states, read hits and failures, synchronization counts and duration, accepted/completed/rejected mutations, pending queues, and queue peaks. Counters reset on reload.

| Symptom | Check |
| --- | --- |
| A placeholder returns `loading` | HuskSync synchronization, player unlock, logs, and watchdog errors. |
| A ready placeholder returns `null` | Variable name, player context, existence, and numeric format for rounded placeholders. |
| Another plugin sees a fallback too early | Initialize through the ready event/future instead of a join handler. |
| Remote edits fail | Shared cluster/Redis/database, unique server names, `LOCKSTEP`, YOV on the owner, and Redis permissions. |
| A load exceeds a limit | Raise the relevant limit or repair data; YOV does not truncate snapshots. |
| Reload is aborted | Pending work, invalid files, and the configured grace period. |

Local placeholder and single-value API reads avoid storage I/O. Whole-map reads and mutations copy snapshots. Actual capacity depends on the dependent plugins and infrastructure; the project does not claim a verified 1,000-player benchmark.

## Building

Use JDK 26 or newer and Maven:

```sh
mvn clean package
```

The artifact is written to `target/YourOwnVariables-<version>.jar`.

The version is defined in `pom.xml`. The source descriptor uses `version: '${project.version}'`; Maven substitutes it only in `plugin.yml`. Other resources are copied without filtering.

See [LICENSE](LICENSE) for licensing terms.
