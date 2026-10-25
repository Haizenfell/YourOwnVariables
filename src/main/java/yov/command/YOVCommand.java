package yov.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import yov.YOVPlugin;
import yov.metrics.YOVMetrics;
import yov.service.VariableService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class YOVCommand implements TabExecutor {

    private final YOVPlugin plugin;
    private final VariableService variableService;

    public YOVCommand(YOVPlugin plugin, VariableService variableService) {
        this.plugin = plugin;
        this.variableService = variableService;
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
        boolean silent = args.size() > 1 && args.getLast().equalsIgnoreCase("-s");
        if (silent) args.removeLast();

        if (args.isEmpty()) {
            sendHelp(sender);
            return true;
        }

        String action = args.get(0).toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> {
                if (args.size() != 1) return usage(sender, "/yov help");
                sendHelp(sender);
                return true;
            }
            case "reload" -> {
                if (args.size() != 1) return usage(sender, "/yov reload");
                plugin.reloadYOV(sender);
                return true;
            }
            case "status" -> {
                if (args.size() != 1) return usage(sender, "/yov status");
                sendStatus(sender);
                return true;
            }
            case "set" -> {
                ParsedSet parsed = parseSet(sender, args);
                if (parsed == null) {
                    return usage(sender, "/yov set <variable> <value> [player] [-s]");
                }
                variableService.setVariable(parsed.player(), parsed.variable(), parsed.value(), sender, silent);
                return true;
            }
            case "add" -> {
                ParsedNumeric parsed = parseNumeric(sender, args);
                if (parsed == null) {
                    return usage(sender, "/yov add <variable> <amount> [player] [-s]");
                }
                variableService.addVariable(parsed.player(), parsed.variable(), parsed.amount(), sender, silent);
                return true;
            }
            case "rem", "remove" -> {
                ParsedNumeric parsed = parseNumeric(sender, args);
                if (parsed == null) {
                    return usage(sender, "/yov rem <variable> <amount> [player] [-s]");
                }
                variableService.remVariable(parsed.player(), parsed.variable(), parsed.amount(), sender, silent);
                return true;
            }
            case "delete", "del" -> {
                ParsedTarget parsed = parseTarget(sender, args);
                if (parsed == null) {
                    return usage(sender, "/yov delete <variable> [player] [-s]");
                }
                variableService.deleteVariable(parsed.player(), parsed.variable(), sender, silent);
                return true;
            }
            case "check" -> {
                ParsedTarget parsed = parseTarget(sender, args);
                if (parsed == null) {
                    return usage(sender, "/yov check <variable> [player]");
                }
                variableService.checkVariable(parsed.player(), parsed.variable(), sender);
                return true;
            }
            case "userclear" -> {
                String player = args.size() == 2 ? resolvePlayer(sender, args, 1) : null;
                if (player == null) {
                    return usage(sender, "/yov userclear <player>");
                }
                variableService.clearPlayerVariables(player, sender);
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
            return filterList(List.of("help", "reload", "status", "set", "add", "rem", "delete", "check", "userclear"), args[0]);
        }

        if (args.length == 0 || containsArgument(args, "-s")) return List.of();

        String action = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && action.equals("userclear")) {
            return onlinePlayers(args[1]);
        }

        if (args.length == 2) {
            if (List.of("set", "add", "rem", "remove", "delete", "del", "check").contains(action)) {
                return filterList(variableNames(), args[1]);
            }
            return List.of();
        }

        if (args.length == 3 && List.of("delete", "del", "check").contains(action)) {
            return targetSuggestions(sender, args[2], action.equals("delete") || action.equals("del"));
        }

        if (args.length == 4 && List.of("set", "add", "rem", "remove").contains(action)) {
            return targetSuggestions(sender, args[3], true);
        }

        if ((args.length == 5 && List.of("set", "add", "rem", "remove").contains(action))
                || (args.length == 4 && List.of("delete", "del").contains(action))) {
            return filterList(List.of("-s"), args[args.length - 1]);
        }

        return List.of();
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov reload §7- Reload config and hooks");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov status §7- Runtime counters and session state");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov set <variable> <value> [player] [-s]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov add <variable> <amount> [player] [-s]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov rem <variable> <amount> [player] [-s]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov delete <variable> [player] [-s]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov check <variable> [player]");
        sender.sendMessage(YOVPlugin.PREFIX + "§e/yov userclear <player>");
        sender.sendMessage(YOVPlugin.PREFIX + "§7Console must always specify the player as the last positional argument.");
    }

    private void sendStatus(CommandSender sender) {
        YOVMetrics.Snapshot status = variableService.metricsSnapshot();
        int failedSessions = Math.max(0,
                status.sessions() - status.readySessions() - status.syncingSessions());
        sender.sendMessage(YOVPlugin.PREFIX + "§eRuntime §7uptime=§f"
                + formatUptime(status.uptime()) + " §7sessions=§f" + status.sessions()
                + " §7(ready=§a" + status.readySessions() + "§7, syncing=§e"
                + status.syncingSessions() + "§7, failed=§c" + failedSessions + "§7)");
        sender.sendMessage(YOVPlugin.PREFIX + "§eReads §7fast=§f" + status.fastReads()
                + " §7hits=§f" + status.fastReadHits() + " §7(§f"
                + percent(status.fastReadHits(), status.fastReads()) + "%§7) offline=§f"
                + status.offlineReads() + " §7failed=§c" + status.offlineReadFailed());
        sender.sendMessage(YOVPlugin.PREFIX + "§eSync §7started=§f" + status.syncStarted()
                + " §7ok=§a" + status.syncSucceeded() + " §7failed=§c" + status.syncFailed()
                + " §7avg/max=§f" + status.averageSyncMillis() + "/"
                + status.maxSyncMillis() + "ms");
        sender.sendMessage(YOVPlugin.PREFIX + "§eMutations §7accepted=§f"
                + status.mutationsAccepted() + " §7ok=§a" + status.mutationsSucceeded()
                + " §7failed=§c" + status.mutationsFailed() + " §7rejected=§c"
                + status.mutationsRejected());
        sender.sendMessage(YOVPlugin.PREFIX + "§eQueues §7online=§f" + status.pendingOnline()
                + "§7/peak=§f" + status.peakPendingOnline() + " §7offline=§f"
                + status.pendingOffline() + "§7/peak=§f" + status.peakPendingOffline()
                + " §7reject(limit/backpressure/remote)=§f" + status.limitRejected() + "/"
                + status.backpressureRejected() + "/" + status.remoteOnlineRejected());
    }

    private String formatUptime(Duration uptime) {
        long seconds = Math.max(0L, uptime.toSeconds());
        long days = seconds / 86_400L;
        long hours = seconds % 86_400L / 3_600L;
        long minutes = seconds % 3_600L / 60L;
        long remainder = seconds % 60L;
        if (days > 0L) return days + "d" + hours + "h";
        if (hours > 0L) return hours + "h" + minutes + "m";
        if (minutes > 0L) return minutes + "m" + remainder + "s";
        return remainder + "s";
    }

    private long percent(long numerator, long denominator) {
        return denominator == 0L ? 0L : Math.min(100L, numerator * 100L / denominator);
    }

    private boolean usage(CommandSender sender, String usage) {
        sender.sendMessage(YOVPlugin.PREFIX + "§cUsage: " + usage);
        return true;
    }

    private ParsedSet parseSet(CommandSender sender, List<String> args) {
        if (args.size() < 3 || args.size() > 4) return null;
        String player = resolvePlayer(sender, args, 3);
        if (player == null || args.get(2).isBlank()) return null;
        return new ParsedSet(player, args.get(1), args.get(2));
    }

    private ParsedNumeric parseNumeric(CommandSender sender, List<String> args) {
        if (args.size() < 3 || args.size() > 4) return null;
        String player = resolvePlayer(sender, args, 3);
        if (player == null) return null;
        return new ParsedNumeric(player, args.get(1), args.get(2));
    }

    private ParsedTarget parseTarget(CommandSender sender, List<String> args) {
        if (args.size() < 2 || args.size() > 3) return null;
        String player = resolvePlayer(sender, args, 2);
        if (player == null) return null;
        return new ParsedTarget(player, args.get(1));
    }

    private @Nullable String resolvePlayer(CommandSender sender, List<String> args, int playerIndex) {
        if (args.size() > playerIndex) {
            String player = args.get(playerIndex);
            return player.isBlank() || player.startsWith("-") ? null : player;
        }
        return sender instanceof Player player ? player.getName() : null;
    }

    private List<String> variableNames() {
        return variableService.knownVariableNames();
    }

    private List<String> onlinePlayers(String prefix) {
        List<String> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            players.add(player.getName());
        }
        return filterList(players, prefix);
    }

    private List<String> targetSuggestions(CommandSender sender,
                                           String prefix,
                                           boolean allowSilent) {
        List<String> suggestions = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            suggestions.add(player.getName());
        }
        if (allowSilent && sender instanceof Player) {
            suggestions.add("-s");
        }
        return filterList(suggestions, prefix);
    }

    private boolean containsArgument(String[] args, String expected) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(expected)) return true;
        }
        return false;
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
