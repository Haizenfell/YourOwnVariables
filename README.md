The plugin allows you to create, modify, delete, and check custom variables. It was originally developed for personal use, but I decided to make it publicly available. PlaceholderAPI is required for it to work.


## Requirements

- PlaceholderAPI


Example usage:
/var set test1 62 (creates an int variable)
/var set test2 62.4 (creates a double variable)
/var set test3 example (creates a string variable)
/var set test4 32 PLAYER_NAME (creates a unique variable with the player’s name)

To modify variables, there are two commands: add and remove:
/var remove test1
/var add test2 5 (if the variable was 3, it will become 8)
/var add test3 5 (if the variable was 3.0, it will become 8.0)

To check variable values, you can use two methods.
Via command:
/var check test → [YOV] Variable value 'test': 8.0
/var check test_PLAYERNAME (if it's a unique variable)

And via placeholders:
For unique variables: %var_player_key:test% — the first part will be replaced with the player’s name, and after the colon is the variable name.
This is useful for displaying values in holograms or checking them with other plugins (I use it with ConditionalEvents).

For global variables: %var_test% — the entire placeholder will be replaced with the variable’s value.

Example:
/papi parse --null %var_test% → 8.0
/papi parse Haizenfell %var_player_key:test% → 9 (reads the value of variable test_haizenfell)


Permissions:
- var.admin — required to modify other players' variables. 
