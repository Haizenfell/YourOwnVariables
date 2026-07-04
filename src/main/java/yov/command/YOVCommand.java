package yov.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.cache.VariableCache;
import yov.service.VariableService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class YOVCommand implements TabExecutor {

    private final YOVPlugin plugin;
    private final VariableService variableService;
    private final VariableCache cache;

    public YOVCommand(YOVPlugin plugin, VariableService variableService, VariableCache cache) {
        this.plugin = plugin;
        this.variableService = variableService;
        this.cache = cache;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] rawArgs) {
        if (!sender.hasPermission("yov.admin")) {
            sender.sendMessage(YOVPlugin.PREFIX + "§cYou do not have permission to use this command.");
            return true;
        }

        List<String> args = new ArrayList<>(Arrays.asList(rawArgs));
        boolean silent = args.removeIf(arg -> arg.equalsIgnoreCase("-s"));

        if (args.isEmpty() || args.get(0).equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String action = args.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                if (args.size() != 1) return usage(sender, "/yov reload");
                plugin.reloadYOV(sender);
                return true;
            }
            case "set" -> {
                ParsedSet parsed = parseSet(sender, args);
                if (parsed == null) return usage(sender, "/yov set <variable> <value> [player] [-s]");
                variableService.setVariable(parsed.player(), parsed.variable(), parsed.value(), sender, silent);
                return true;
            }
            case "add" -> {
                ParsedNumeric parsed = parseNumeric(sender, args);
                if (parsed == null) return usage(sender, "/yov add <variable> <amount> [player] [-s]");
                variableService.addVariable(parsed.player(), parsed.variable(), parsed.amount(), sender, silent);
                return true;
            }
            case "rem", "remove" -> {
                ParsedNumeric parsed = parseNumeric(sender, args);
                if (parsed == null) return usage(sender, "/yov rem <variable> <amount> [player] [-s]");
                variableService.remVariable(parsed.player(), parsed.variable(), parsed.amount(), sender, silent);
                return true;
            }
            case "delete", "del" -> {
                ParsedTarget parsed = parseTarget(sender, args, 2);
                if (parsed == null) return usage(sender, "/yov delete <variable> [player] [-s]");
                variableService.deleteVariable(parsed.player(), parsed.variable(), sender, silent);
                return true;
            }
            case "check" -> {
                ParsedTarget parsed = parseTarget(sender, args, 2);
                if (parsed == null) return usage(sender, "/yov check <variable> [player]");
                variableService.checkVariable(parsed.player(), parsed.variable(), sender);
                return true;
            }
            case "userclear" -> {
                if (args.size() != 2) return usage(sender, "/yov userclear <player>");
                variableService.clearPlayerVariables(args.get(1), sender);
                return true;
            }
            default -> {
                sender.sendMessage(YOVPlugin.PREFIX + "§cUnknown action: " + action);
                return true;
            }
        }
    }

    @Override
    public @NotNull List<String> onTabComplete(@NotNull CommandSender sender,
                                               @NotNull Command command,
                                               @NotNull String alias,
                                               @NotNull String[] args) {
        if (!sender.hasPermission("yov.admin")) return List.of();

        if (args.length == 1) {
            return filterList(List.of("help", "reload", "set", "add", "rem", "delete", "check", "userclear"), args[0]);
        }

        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("userclear")) {
                return onlinePlayers(args[1]);
            }
            return filterList(variableNames(), args[1]);
        }

        if (args.length == 4 && List.of("add", "rem", "set").contains(args[0].toLowerCase(Locale.ROOT))) {
            return onlinePlayers(args[3]);
        }

        if (args.length == 3 && List.of("delete", "check").contains(args[0].toLowerCase(Locale.ROOT))) {
            return onlinePlayers(args[2]);
        }

        if (args.length >= 3) {
            String last = args[args.length - 1];
            if ("-s".startsWith(last.toLowerCase(Locale.ROOT))) return List.of("-s");
        }

        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov reload §7- Reload config and hooks");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov set <variable> <value> [player] [-s]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov add <variable> <amount> [player] [-s]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov rem <variable> <amount> [player] [-s]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov delete <variable> [player] [-s]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov check <variable> [player]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov userclear <player>");
    }

    private boolean usage(CommandSender sender, String usage) {
        sender.sendMessage(YOVPlugin.PREFIX + "§cUsage: " + usage);
        return true;
    }

    private ParsedSet parseSet(CommandSender sender, List<String> args) {
        if (args.size() < 3) return null;

        String variable = args.get(1);
        String player;
        String value;

        boolean explicitPlayer = hasExplicitPlayer(sender, args);
        if (explicitPlayer) {
            if (args.size() < 4) return null;
            player = args.get(args.size() - 1);
            value = String.join(" ", args.subList(2, args.size() - 1));
        } else {
            if (!(sender instanceof Player playerSender)) return null;
            player = playerSender.getName();
            value = String.join(" ", args.subList(2, args.size()));
        }

        if (value.isBlank()) return null;
        return new ParsedSet(player, variable, value);
    }

    private ParsedNumeric parseNumeric(CommandSender sender, List<String> args) {
        if (args.size() < 3 || args.size() > 4) return null;
        String player = resolvePlayer(sender, args, 3);
        if (player == null) return null;
        return new ParsedNumeric(player, args.get(1), args.get(2));
    }

    private ParsedTarget parseTarget(CommandSender sender, List<String> args, int maxWithoutPlayer) {
        if (args.size() < 2 || args.size() > maxWithoutPlayer + 1) return null;
        String player = resolvePlayer(sender, args, 2);
        if (player == null) return null;
        return new ParsedTarget(player, args.get(1));
    }

    private String resolvePlayer(CommandSender sender, List<String> args, int playerIndex) {
        if (args.size() > playerIndex) return args.get(playerIndex);
        if (sender instanceof Player player) return player.getName();
        return null;
    }

    private boolean hasExplicitPlayer(CommandSender sender, List<String> args) {
        if (sender instanceof ConsoleCommandSender) return true;
        if (args.size() == 4) return true;
        if (args.size() < 4) return false;

        String last = args.get(args.size() - 1);
        return variableService.isKnownOfflinePlayer(last);
    }

    private List<String> variableNames() {
        List<String> names = new ArrayList<>();
        for (String key : cache.getMap().keySet()) {
            int split = key.indexOf('_');
            if (split >= 0 && split + 1 < key.length()) {
                names.add(key.substring(split + 1));
            }
        }
        return names.stream().distinct().sorted().toList();
    }

    private List<String> onlinePlayers(String prefix) {
        List<String> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.add(player.getName());
        }
        return filterList(players, prefix);
    }

    private List<String> filterList(@NotNull List<String> list, @Nullable String prefix) {
        if (prefix == null || prefix.isEmpty()) return list;
        List<String> filtered = new ArrayList<>();
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (String value : list) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) filtered.add(value);
        }
        return filtered;
    }

    private record ParsedSet(String player, String variable, String value) {
    }

    private record ParsedNumeric(String player, String variable, String amount) {
    }

    private record ParsedTarget(String player, String variable) {
    }
}
