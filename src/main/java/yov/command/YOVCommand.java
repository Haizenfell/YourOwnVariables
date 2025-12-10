package yov.command;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.cache.VariableCache;
import yov.service.MigrationService;
import yov.service.VariableService;

import java.util.*;

public class YOVCommand implements TabExecutor {

    private final YOVPlugin plugin;
    private final VariableService variableService;
    private final VariableCache cache;
    private final MigrationService migrationService;

    public YOVCommand(YOVPlugin plugin,
                      VariableService variableService,
                      VariableCache cache,
                      MigrationService migrationService) {
        this.plugin = plugin;
        this.variableService = variableService;
        this.cache = cache;
        this.migrationService = migrationService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        String PREFIX = YOVPlugin.PREFIX;

        if (!(sender instanceof Player) || sender.hasPermission("yov.admin")) {

            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sender.sendMessage(PREFIX + "§e/yov help §7- Show this help message");
                sender.sendMessage(PREFIX + "§e/yov reload §7- Reload config, storage and cache");
                sender.sendMessage(PREFIX + "§e/yov set <variable> <value> [player] [-s]");
                sender.sendMessage(PREFIX + "§e/yov add <variable> <amount> [player] [-s]");
                sender.sendMessage(PREFIX + "§e/yov rem <variable> <amount> [player] [-s]");
                sender.sendMessage(PREFIX + "§e/yov delete <variable> [player] [-s]");
                sender.sendMessage(PREFIX + "§e/yov check <variable> [player]");
                sender.sendMessage(PREFIX + "§e/yov storage §7- Show current storage type");
                sender.sendMessage(PREFIX + "§e/yov migrate <from> <to> §7- Migrate between storages (console only)");
                sender.sendMessage(PREFIX + "§e/yov userclear <player> §7- Remove all variables of player");
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                plugin.reloadYOV(sender);
                return true;
            }

            if (args.length == 3 && args[0].equalsIgnoreCase("migrate")) {
                migrationService.migrate(args[1], args[2], sender);
                return true;
            }

            if (args.length == 2 && args[0].equalsIgnoreCase("userclear")) {
                String playerName = args[1];
                variableService.clearPlayerVariables(playerName, sender);
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("storage")) {
                sender.sendMessage(PREFIX + "§eCurrent storage: §6" + plugin.getStorageType());
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(PREFIX + "§cUsage: /yov <help|reload|migrate|set|add|rem|delete|check|userclear>");
                return true;
            }

            boolean silent = args[args.length - 1].equalsIgnoreCase("-s");
            if (silent) args = Arrays.copyOf(args, args.length - 1);

            String action = args[0].toLowerCase(Locale.ROOT);
            String baseKey = args[1];
            String value = args.length >= 3 ? args[2] : null;
            String playerName = args.length >= 4 ? args[3] : null;

            String key = playerName != null
                    ? playerName.toLowerCase(Locale.ROOT) + "_" + baseKey.toLowerCase(Locale.ROOT)
                    : baseKey.toLowerCase(Locale.ROOT);

            switch (action) {
                case "delete" -> variableService.deleteVariable(key, sender, silent);
                case "add" -> {
                    if (value != null) variableService.addVariable(key, value, sender, silent);
                }
                case "rem" -> {
                    if (value != null) variableService.remVariable(key, value, sender, silent);
                }
                case "set" -> {
                    if (value != null) variableService.setVariable(key, value, sender, silent);
                }
                case "check" -> {
                    String val = variableService.getSynchronizedValue(key);
                    sender.sendMessage(PREFIX + key + ": " + (val != null ? val : "null"));
                }
                default -> sender.sendMessage(PREFIX + "§cUnknown action: " + action);
            }
            return true;

        } else {
            sender.sendMessage(PREFIX + "§cYou do not have permission to use this command!");
            return true;
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command command,
                                               @NotNull String alias,
                                               @NotNull String[] args) {

        if (args.length == 1) {
            return filterList(
                    Arrays.asList("help", "reload", "set", "add", "rem", "delete", "check", "storage", "migrate", "userclear"),
                    args[0]);
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("migrate")) {
                return filterList(Arrays.asList("sqlite", "mariadb", "yaml"), args[1]);
            }
            if (args[0].equalsIgnoreCase("userclear")) {
                List<String> players = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
                return filterList(players, args[1]);
            }
            return filterList(new ArrayList<>(cache.getMap().keySet()), args[1]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("migrate")) {
            return filterList(Arrays.asList("sqlite", "mariadb", "yaml"), args[2]);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return filterList(List.of("Value"), args[2]);
        }

        if (args.length == 4) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
            return filterList(players, args[3]);
        }

        if (args.length == 5) {
            return filterList(List.of("-s"), args[4]);
        }

        return List.of();
    }

    private List<String> filterList(@NotNull List<String> list, @Nullable String prefix) {
        if (prefix == null || prefix.isEmpty()) return list;
        List<String> filtered = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String s : list) {
            if (s.toLowerCase(Locale.ROOT).startsWith(lower)) filtered.add(s);
        }
        return filtered;
    }
}
