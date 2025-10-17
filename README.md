# YourOwnVariables (YOV) Plugin

The **YourOwnVariables (YOV)** plugin allows you to **create, modify, delete, and check custom variables**. Originally developed for personal use, it is now publicly available.  
**Note:** PlaceholderAPI is required for the plugin to function properly.

## Requirements

- [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)

## Commands & Usage

### Creating Variables

`/yov set test1 1` # Creates an integer variable

`/yov set test2 1.0` # Creates a double variable

`/yov set test3 love my` # Creates a string variable

`/yov set test4 32 PLAYER_NAME` # Creates a unique variable tied to a player



### Modifying Variables

Use the `add` or `remove` commands to update existing variables:

`/yov add test2 5` # If variable was 3, it becomes 8

`/yov add test3 5` # If variable was 3.0, it becomes 8.0

`/yov rem test3 1` # If variable was 8.0, it becomes 7.0

`/yov remove test1` # Removes the variable


**Silent execution:** add `-s` to execute commands without sending messages:

`/yov add newvariable 1 PLAYER_NAME -s`


### Checking Variables

Via command:

`/yov check test` # → [YOV] Variable value 'test': 8.0

`/yov check test_PLAYERNAME` # For unique variables


Export variables from `.db` to `.yml` (console only):

`/yov export`


## Placeholders

### Unique Variables

`%yov_player_key:<variable_name>%`

# rounding support:
`%yov_player_key:test% == 14.543 → %rounded_player_key:test% == 15`
`%yov_player_key:test% == 14.443 → %rounded_player_key:test% == 14`
Rounding always goes up.

# fractional rounding options:
`%yov_player_key:test% == 14.543`
`%rounded_player_key_1:test% == 14.5`
` %rounded_player_key_2:test% == 14.54`

- The first part is replaced with the player's name.  
- Useful for holograms or integration with other plugins (e.g., ConditionalEvents).
- in other plugins you can use `/yov set example 1 %player_name% -s` to make player variable

**Example:**

`/papi parse Haizenfell %yov_player_key:test%` # Reads the value of variable test_Haizenfell


### Global Variables

`%yov_<variable_name>%`


- Entire placeholder is replaced with the variable's value.

**Example:**

`/papi parse --null %yov_test% # → 8.0`


## Permissions

- `yov.admin` — Required to modify other players' variables.
