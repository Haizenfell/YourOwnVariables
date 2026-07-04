# YourOwnVariables (YOV)

YOV stores player-bound variables in HuskSync custom data and exposes them through PlaceholderAPI.
The plugin is intended to be installed on every server in the network so all dependent plugins read the same player data.

## Requirements

- Paper `26.1.2.build.72-stable`
- PlaceholderAPI
- HuskSync v3 API
- Java 25+ runtime; build tested with JDK 26

`PlaceholderAPI` and `HuskSync` are hard dependencies.

## Storage Model

YOV variables are stored per player inside HuskSync under the custom data identifier:

```text
yov:variables
```

There is no YAML, SQLite, MariaDB, global storage, or migration layer anymore. Player data is synchronized by HuskSync snapshots/current data.

## Commands

All commands require `yov.admin`.

```text
/yov reload
/yov set <variable> <value> [player] [-s]
/yov add <variable> <amount> [player] [-s]
/yov rem <variable> <amount> [player] [-s]
/yov delete <variable> [player] [-s]
/yov check <variable> [player]
/yov userclear <player>
```

Behavior:

- If `[player]` is omitted, the sender must be a player and the command targets the sender.
- Console must always specify `[player]`.
- `-s` suppresses command feedback.
- `set` stores values as strings.
- `add` and `rem` require numeric values and use decimal-safe arithmetic.
- Offline or cross-server players are updated through HuskSync current data.

Examples:

```text
/yov set coins 100 Steve
/yov add coins 25 Steve -s
/yov rem coins 10 Steve
/yov delete coins Steve
/yov check coins Steve
/yov userclear Steve
```

## Placeholders

```text
%yov_player_key:coins%
%rounded_player_key:coins%
%rounded_player_key_2:coins%
```

Global/local placeholders such as `%yov_coins%` are intentionally not supported.
Rounded placeholders use `HALF_UP` rounding. Missing or non-numeric values return `null`.

## Default Variables

Defaults are configured in `default_variables.yml` and are applied once after the player's HuskSync data has loaded.
Existing values are not overwritten.

```yml
player_variables:
  coins: "0"
  claim_points: "200"
```

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `yov.admin` | `op` | Full access to `/yov` commands |

## Config

```yml
husksync:
  unload-delay-ticks: 40

defaults:
  apply-timeout-ticks: 100
```

`unload-delay-ticks` keeps player variables cached briefly after quit so HuskSync save events can complete.
