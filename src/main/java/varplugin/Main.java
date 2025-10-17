package varplugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main extends JavaPlugin implements TabExecutor {
    private DatabaseManager database;
    private static final String PREFIX = "§7[§6YOV§7] ";
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        try {
            database = new DatabaseManager(getDataFolder());
            database.connect();
            loadCache();
        } catch (Exception e) {
            e.printStackTrace();
            getLogger().severe("Error connecting to SQLite!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Objects.requireNonNull(getCommand("yov")).setExecutor(this);
        Objects.requireNonNull(getCommand("yov")).setTabCompleter(this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VarPlaceholder().register();
            new VarPlayerKeyPlaceholder().register();
            new RoundedPlaceholder().register();
            getLogger().info("PlaceholderAPI hook enabled.");
        }

        getLogger().info("VarPlugin is enabled.");
    }

    private void loadCache() {
        try {
            for (String key : database.getAllKeys()) {
                cache.put(key, database.getVariable(key));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            getLogger().warning("Error loading cache!");
        }
    }

    @Override
    public void onDisable() {
        try {
            if (database != null) database.close();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        getLogger().info("VarPlugin is disabled.");
    }

    private void setVariable(String key, String value, CommandSender sender, boolean silent) {
        cache.put(key, value);
        try { database.setVariable(key, value); } catch (SQLException e) { e.printStackTrace(); }
        if (!silent) sender.sendMessage(PREFIX + "§aVariable '" + key + "' set to '" + value + "'");
    }

    private void deleteVariable(String key, CommandSender sender, boolean silent) {
        cache.remove(key);
        try { database.deleteVariable(key); } catch (SQLException e) { e.printStackTrace(); }
        if (!silent) sender.sendMessage(PREFIX + "§aVariable '" + key + "' deleted.");
    }

    private void addVariable(String key, String amountStr, CommandSender sender, boolean silent) {
        modifyVariable(key, amountStr, sender, silent, true);
    }

    private void remVariable(String key, String amountStr, CommandSender sender, boolean silent) {
        modifyVariable(key, amountStr, sender, silent, false);
    }

    private void modifyVariable(String key, String amountStr, CommandSender sender, boolean silent, boolean isAdd) {
        String existingVal = cache.getOrDefault(key, "0");
        try {
            if (isWholeNumber(existingVal) && isWholeNumber(amountStr)) {
                int current = Integer.parseInt(existingVal);
                int delta = Integer.parseInt(amountStr);
                int result = isAdd ? current + delta : current - delta;
                setVariable(key, String.valueOf(result), sender, silent);
                if (!silent)
                    sender.sendMessage(PREFIX + "§aVariable '" + key + "' " + (isAdd ? "increased" : "decreased") + " by " + delta + ". New value: " + result);
            } else {
                double current = Double.parseDouble(existingVal);
                double delta = Double.parseDouble(amountStr);
                double result = isAdd ? current + delta : current - delta;
                setVariable(key, String.valueOf(result), sender, silent);
                if (!silent)
                    sender.sendMessage(PREFIX + "§aVariable '" + key + "' " + (isAdd ? "increased" : "decreased") + " by " + delta + ". New value: " + result);
            }
        } catch (NumberFormatException e) {
            if (!silent) sender.sendMessage(PREFIX + "§cError: Variable or value is not a number.");
        }
    }

    private boolean isWholeNumber(String s) {
        try { Integer.parseInt(s); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private void exportToYamlAsync(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            cache.forEach(yaml::set);
            try {
                yaml.save(new File(getDataFolder(), "variables.yml"));
                sender.sendMessage(PREFIX + "§aExport complete!");
            } catch (Exception e) {
                e.printStackTrace();
                sender.sendMessage(PREFIX + "§cExport error!");
            }
        });
    }


    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player) || sender.hasPermission("yov.admin")) {

            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sender.sendMessage(PREFIX + "§e/yov help §7- Show this help message");
                sender.sendMessage(PREFIX + "§e/yov set <variable> <value> [player] [-s] §7- Set variable to value");
                sender.sendMessage(PREFIX + "§e/yov add <variable> <amount> [player] [-s] §7- Add number to variable");
                sender.sendMessage(PREFIX + "§e/yov rem <variable> <amount> [player] [-s] §7- Subtract number from variable");
                sender.sendMessage(PREFIX + "§e/yov delete <variable> [player] [-s] §7- Delete variable");
                sender.sendMessage(PREFIX + "§e/yov check <variable> [player] §7- Show variable value");
                sender.sendMessage(PREFIX + "§e/yov export §7- Export all variables to variables.yml");
                return true;
            }

            if (args.length == 1 && args[0].equalsIgnoreCase("export")) {
                if (!(sender instanceof Player)) {
                    exportToYamlAsync(sender);
                } else {
                    sender.sendMessage(PREFIX + "§cThis command is only available in the console!");
                }
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(PREFIX + "§cUsage: /yov <help|export|set|add|rem|delete|check> <variable> [value] [player] [-s]");
                return true;
            }

            boolean silent = args[args.length - 1].equalsIgnoreCase("-s");
            if (silent) args = Arrays.copyOf(args, args.length - 1);

            String action = args[0].toLowerCase();
            String baseKey = args[1];
            String value = args.length >= 3 ? args[2] : null;
            String player = args.length >= 4 ? args[3] : null;
            String key = player != null ? baseKey + "_" + player.toLowerCase() : baseKey.toLowerCase();

            switch (action) {
                case "delete" -> deleteVariable(key, sender, silent);
                case "add" -> { if (value != null) addVariable(key, value, sender, silent); }
                case "rem" -> { if (value != null) remVariable(key, value, sender, silent); }
                case "set" -> { if (value != null) setVariable(key, value, sender, silent); }
                case "check" -> { 
                    String val = cache.get(key); 
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
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1)
            return filterList(Arrays.asList("help","set","add","rem","delete","check","export"), args[0]);
        if (args.length == 2)
            return filterList(new ArrayList<>(cache.keySet()), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("set"))
            return filterList(List.of("Value"), args[2]);
        if (args.length == 4) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
            return filterList(players, args[3]);
        }
        if (args.length == 5)
            return filterList(List.of("-s"), args[4]);
        return List.of();
    }

    private List<String> filterList(List<String> list, String prefix) {
        if (prefix == null || prefix.isEmpty()) return list;
        List<String> filtered = new ArrayList<>();
        for (String s : list) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) filtered.add(s);
        }
        return filtered;
    }

    public class VarPlaceholder extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "yov"; }
        @Override public String getAuthor() { return "Haizenfell"; }
        @Override public String getVersion() { return "3.5"; }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (identifier == null) return null;
            if (identifier.startsWith("player_key:")) {
                if (player == null) return "null";
                String fullKey = identifier.substring("player_key:".length()) + "_" + player.getName().toLowerCase();
                return cache.getOrDefault(fullKey, "null");
            } else return cache.getOrDefault(identifier, "null");
        }
    }

    public class VarPlayerKeyPlaceholder extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "yov_player_key"; }
        @Override public String getAuthor() { return "Haizenfell"; }
        @Override public String getVersion() { return "3.5"; }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (player == null) return "null";
            String fullKey = identifier + "_" + player.getName().toLowerCase();
            return cache.getOrDefault(fullKey, "null");
        }
    }

    public class RoundedPlaceholder extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "rounded"; }
        @Override public String getAuthor() { return "Haizenfell"; }
        @Override public String getVersion() { return "3.5"; }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (identifier == null) return null;
            try {
                int decimals = 0;
                boolean isPlayerKey = identifier.startsWith("player_key");
                String keyPart = identifier;

                if (isPlayerKey) keyPart = identifier.substring("player_key".length());
                if (keyPart.startsWith("_")) {
                    String[] split = keyPart.substring(1).split(":", 2);
                    try { decimals = Integer.parseInt(split[0]); } catch (NumberFormatException ignored) {}
                    keyPart = ":" + split[1];
                }
                if (!keyPart.startsWith(":")) return "null";

                String key = keyPart.substring(1);
                if (isPlayerKey) {
                    if (player == null) return "null";
                    key += "_" + player.getName().toLowerCase();
                }
                String value = cache.getOrDefault(key, "null");
                if (value.equals("null")) return "null";

                double num = Double.parseDouble(value);
                return String.format("%." + decimals + "f", num);
            } catch (Exception e) {
                return "null";
            }
        }
    }
}
