package varplugin;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Main extends JavaPlugin implements TabExecutor {
    private DatabaseManager database;
    private static final String PREFIX = "§7[§bYOV§7] ";
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
            getLogger().info("PlaceholderAPI hook enabled.");
        }

        getLogger().info("is enabled.");
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
        getLogger().info("§cis disabled.");
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
        String existingVal = cache.getOrDefault(key, "0");
        boolean isWhole = isWholeNumber(existingVal) && isWholeNumber(amountStr);

        try {
            if (isWhole) {
                int current = Integer.parseInt(existingVal);
                int toAdd = Integer.parseInt(amountStr);
                int result = current + toAdd;
                setVariable(key, String.valueOf(result), sender, silent);
                if (!silent) sender.sendMessage(PREFIX + "§aVariable '" + key + "' increased by " + toAdd + ". New value: " + result);
            } else {
                double current = Double.parseDouble(existingVal);
                double toAdd = Double.parseDouble(amountStr);
                double result = current + toAdd;
                setVariable(key, String.valueOf(result), sender, silent);
                if (!silent) sender.sendMessage(PREFIX + "§aVariable '" + key + "' increased by " + toAdd + ". New value: " + result);
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
            var yaml = new org.bukkit.configuration.file.YamlConfiguration();
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

            if (args.length == 1 && args[0].equalsIgnoreCase("export")) {
                if (!(sender instanceof Player)) {
                    exportToYamlAsync(sender);
                } else {
                    sender.sendMessage(PREFIX + "§cThis command is only available in the console!");
                }
                return true;
            }

            if (args.length < 2) {
                sender.sendMessage(PREFIX + "§cUsage: /yov <export|set|add|delete|check> <variable> [value] [player] [-s]");
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
        if (args.length == 1) return Arrays.asList("set","add","delete","check","export");
        else if (args.length == 2) return new ArrayList<>(cache.keySet());
        else if (args.length == 3 && args[0].equalsIgnoreCase("set")) return List.of("Value");
        else if (args.length == 4) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) players.add(p.getName());
            return players;
        } else if (args.length == 5) return List.of("-s");
        return List.of();
    }

    public class VarPlaceholder extends PlaceholderExpansion {
        @Override public String getIdentifier() { return "yov"; }
        @Override public String getAuthor() { return "Haizenfell"; }
        @Override public String getVersion() { return "3.0"; }

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
        @Override public String getVersion() { return "3.0"; }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (player == null) return "null";
            String fullKey = identifier + "_" + player.getName().toLowerCase();
            return cache.getOrDefault(fullKey, "null");
        }
    }
}
