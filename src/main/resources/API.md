# YourOwnVariables API

YourOwnVariables exposes a Java API for plugins that need direct access to YOV player variables without resolving PlaceholderAPI placeholders.

The API uses the same HuskSync-backed storage as `/yov` commands and YOV placeholders.

## Dependency

Add `YourOwnVariables` as a hard dependency when your plugin cannot work without YOV:

```yml
depend: [YourOwnVariables]
```

Use `softdepend` only when your plugin has a fallback path:

```yml
softdepend: [YourOwnVariables]
```

Compile against the YOV jar as a `provided` dependency. Do not shade YOV into your plugin.

## Getting The API

Preferred access is through Bukkit `ServicesManager`:

```java
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import yov.api.YOVApi;

RegisteredServiceProvider<YOVApi> registration =
        Bukkit.getServicesManager().getRegistration(YOVApi.class);

if (registration == null) {
    // YOV is not enabled or its API is not registered.
    return;
}

YOVApi yov = registration.getProvider();
```

If you have a hard dependency, you can also get the API from the plugin instance:

```java
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import yov.YOVPlugin;
import yov.api.YOVApi;

Plugin plugin = Bukkit.getPluginManager().getPlugin("YourOwnVariables");
if (plugin instanceof YOVPlugin yovPlugin) {
    YOVApi yov = yovPlugin.getApi();
}
```

## Threading

- Fast read methods do not query HuskSync and return immediately from local memory.
- Mutation methods can be called from any thread. YOV schedules Bukkit-sensitive work on the primary server thread when needed.
- Async methods return `CompletableFuture`.
- A future for offline/HuskSync work may complete off the primary server thread. Schedule back to the server thread before calling Bukkit APIs in your completion callback.

Example:

```java
yov.getCurrentVariable("Steve", "coins").thenAccept(value -> {
    Bukkit.getScheduler().runTask(plugin, () -> {
        player.sendMessage("Coins: " + value.orElse("0"));
    });
});
```

## Normalization

- Player names are trimmed and lower-cased internally.
- Variable names are trimmed and lower-cased internally.
- Empty variable names are invalid.
- Values are stored as strings.
- Missing variables return `null` from fast sync reads and `Optional.empty()` from async current reads.

## Fast Local Reads

These methods never pull data from HuskSync. Use them when you are handling an online player whose data has already loaded.

```java
String value = yov.getVariable("Steve", "coins");
String safeValue = yov.getVariableOrDefault("Steve", "coins", "0");
Map<String, String> variables = yov.getVariables("Steve");
```

Methods:

| Method | Description |
| --- | --- |
| `String getVariable(String playerName, String variable)` | Returns a cached value or `null`. |
| `String getVariableOrDefault(String playerName, String variable, String defaultValue)` | Returns a cached value or your default. |
| `Map<String, String> getVariables(String playerName)` | Returns an immutable local snapshot. Empty when no local snapshot exists. |

## Current HuskSync Reads

These methods use local memory when available. If the player is not loaded locally, they pull current HuskSync data asynchronously.

```java
yov.getCurrentVariable("Steve", "coins").thenAccept(value -> {
    String coins = value.orElse("0");
});

yov.getCurrentVariables("Steve").thenAccept(variables -> {
    String coins = variables.getOrDefault("coins", "0");
});
```

Methods:

| Method | Description |
| --- | --- |
| `CompletableFuture<Optional<String>> getCurrentVariable(String playerName, String variable)` | Returns the current value if present. |
| `CompletableFuture<Map<String, String>> getCurrentVariables(String playerName)` | Returns an immutable current variable snapshot. |

## Mutations

Mutation methods update online players immediately and push data into HuskSync. For offline or cross-server players, YOV edits HuskSync current data asynchronously.

All mutation methods return `CompletableFuture<YOVOperationResult>`.

```java
yov.setVariable("Steve", "coins", "100").thenAccept(result -> {
    if (!result.success()) {
        plugin.getLogger().warning(result.message());
        return;
    }

    plugin.getLogger().info("New value: " + result.value());
});
```

Methods:

| Method | Description |
| --- | --- |
| `setVariable(String playerName, String variable, String value)` | Stores a string value. |
| `addVariable(String playerName, String variable, BigDecimal amount)` | Adds a decimal amount. Missing values are treated as `0`. |
| `addVariable(String playerName, String variable, String amount)` | String overload for `addVariable`. |
| `remVariable(String playerName, String variable, BigDecimal amount)` | Subtracts a decimal amount. Missing values are treated as `0`. |
| `remVariable(String playerName, String variable, String amount)` | String overload for `remVariable`. |
| `deleteVariable(String playerName, String variable)` | Deletes one variable. |
| `clearVariables(String playerName)` | Deletes all variables for one player. |

Numeric operations fail when the existing variable value is non-numeric.

## Operation Result

`YOVOperationResult` fields:

| Field | Description |
| --- | --- |
| `success()` | `true` when the operation was applied. |
| `failed()` | Convenience inverse of `success()`. |
| `message()` | Human-readable status or error. |
| `value()` | Main returned value. For `set/add/rem`, this is the stored value. For `delete`, this is the previous value when known. For `clearVariables`, this is the removed count. May be `null`. |
| `variables()` | Immutable variable snapshot after the operation when available. Empty on failure. |

## State And Discovery

```java
boolean loaded = yov.isPlayerLoaded("Steve");
boolean hasSnapshot = yov.hasPlayerSnapshot("Steve");
List<String> variableNames = yov.getKnownVariableNames();
String normalized = yov.normalizeVariable("Coins");
```

Methods:

| Method | Description |
| --- | --- |
| `isPlayerLoaded(String playerName)` | Returns whether YOV has marked the player's HuskSync data as loaded. |
| `hasPlayerSnapshot(String playerName)` | Returns whether YOV has a local variable snapshot for the player. |
| `getKnownVariableNames()` | Returns variable names known from loaded players, cache, and defaults. |
| `normalizeVariable(String variable)` | Returns YOV's normalized variable name or `null` when invalid. |

## Recommended Patterns

- Use `getVariable` for hot paths involving currently online players.
- Use `getCurrentVariable` for offline players or cross-server reads.
- Use API mutations instead of dispatching `/yov` commands from another plugin.
- Do not parse YOV PlaceholderAPI placeholders when you can depend on this API.
- Do not modify the maps returned by the API; they are immutable snapshots.

## Placeholder-Only Integrations

When another plugin cannot depend on the Java API and can only read PlaceholderAPI values:

- `%yov_player_loaded%` returns `true` after YOV has loaded that player's HuskSync data locally.
- `%yov_player_key:<variable>%` returns the configured `placeholders.loading-value` while player data is still loading or no player context is available.
- After `%yov_player_loaded%` is `true`, a missing variable returns `null`.

Keep `placeholders.loading-value` different from `null` when another plugin triggers actions from `== null` conditions.
