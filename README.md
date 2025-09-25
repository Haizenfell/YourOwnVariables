The plugin allows you to create, modify, delete, and check custom variables. It was originally developed for personal use, but I decided to make it publicly available. PlaceholderAPI is required for it to work.


## Requirements

- PlaceholderAPI


Example usage:
/yov set test1 62 (creates an int variable)
/yov set test2 62.4 (creates a double variable)
/yov set test3 example (creates a string variable)
/yov set test4 32 PLAYER_NAME (creates a unique variable with the player’s name)

To modify variables, there are two commands: add and remove:
/yov remove test1
/yov add test2 5 (if the variable was 3, it will become 8)
/yov add test3 5 (if the variable was 3.0, it will become 8.0)
/yov add test3 -1 (if the variable was 8.0, it will become 7.0)

To check variable values, you can use two methods.
Via command:
/yov check test → [YOV] Variable value 'test': 8.0
/yov check test_PLAYERNAME (if it's a unique variable)

To export .db variables to .yml file
/yov export (only in console)

Add the -s flag to the end of commands to execute them silently.
/yov add newvariable 1 player_name -s

And via placeholders:
For unique variables: %yov_player_key:test% — the first part will be replaced with the player’s name, and after the colon is the variable name.
This is useful for displaying values in holograms or checking them with other plugins (I use it with ConditionalEvents).

For global variables: %yov_test% — the entire placeholder will be replaced with the variable’s value.

Example:
/papi parse --null %yov_test% → 8.0
/papi parse Haizenfell %yov_player_key:test% → 9 (reads the value of variable test_haizenfell)


Permissions:
- yov.admin — required to modify other players' variables.
