# 🌌 YourOwnVariables (YOV)

A flexible variable management system for Minecraft servers.\
Allows you to create, modify, delete, migrate, and display **global**
and **player-based variables**.

> **Requires:** [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)\
> Works on all Spigot/Paper versions (1.16.5-1.21.11)

------------------------------------------------------------------------

# ✨ Features

### Global Variables

-   `%yov_<variable>%`

### Player Variables

-   Stored as `<player>_<variable>`
-   Accessed with `%yov_player_key:<variable>%`

### Migration System

    /yov migrate <from> <to>

### Commands

-   `/yov help`
-   `/yov reload`
-   `/yov set <variable> <value> [player] [-s]`
-   `/yov add <variable> <amount> [player] [-s]`
-   `/yov rem <variable> <amount> [player] [-s]`
-   `/yov delete <variable> [player] [-s]`
-   `/yov check <variable> [player]`
-   `/yov migrate <from> <to>`
-   `/yov userclear <player>`

------------------------------------------------------------------------

# 📜 Commands & Usage

## Creating Variables

    /yov set test 1
    /yov set test 1.0
    /yov set test hello world
    /yov set level 5 playerName

## Modifying Variables

    /yov add test 5
    /yov rem test 1
    /yov delete test

### Silent mode

    /yov add test 1 player -s

------------------------------------------------------------------------

# 🔍 Checking Variables

    /yov check test
    /yov check coins playerName

------------------------------------------------------------------------

# 🧩 PlaceholderAPI Usage

## Global variable

    %yov_test%

## Player variable

    %yov_player_key:coins%

## Rounded values

    %rounded:coins%
    %rounded_1:coins%
    %rounded_2:coins%

------------------------------------------------------------------------

# 🌍 Integration Example (ConditionalEvents)

``` yml
conditions:
  check-level: '%yov_player_key:level% >= 20'
```

Or dynamically:

    /yov set example 1 %player_name% -s

------------------------------------------------------------------------

# 🔐 Permissions

  Permission   Description
  ------------ -------------
  yov.admin    Full access

------------------------------------------------------------------------

# 📦 Storage Types

Supports: - YAML - SQLite - MariaDB

Switch storage via config, then:

    /yov migrate <old> <new>

------------------------------------------------------------------------

# 📜 Global & Player Structure (YAML)

``` yaml
global:
  season: "1"

players:
  haizenfell:
    coins: "500"
    kills: "10"
```
