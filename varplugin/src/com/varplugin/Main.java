package com.varplugin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Main extends JavaPlugin implements TabExecutor {

    private File varFile;
    private FileConfiguration varConfig;
    private final String PREFIX = "§7[§bYOV§7] ";

    @Override
    public void onEnable() {
        loadVariables();

        getCommand("var").setExecutor(this);
        getCommand("var").setTabCompleter(this);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new VarPlaceholder().register();
            new VarPlayerKeyPlaceholder().register();
            getLogger().info("PlaceholderAPI hook enabled.");
        }

        getLogger().info("YourOwnVariables enabled.");
    }

    @Override
    public void onDisable() {
        saveVariables();
        getLogger().info("YourOwnVariables disabled.");
    }

    private void loadVariables() {
        varFile = new File(getDataFolder(), "variables.yml");
        if (!varFile.exists()) {
            varFile.getParentFile().mkdirs();
            try {
                varFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        varConfig = YamlConfiguration.loadConfiguration(varFile);
    }

    private void saveVariables() {
        try {
            varConfig.save(varFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void setVariable(String key, String value, CommandSender sender) {
        varConfig.set(key.toLowerCase(), value);
        saveVariables();
        sender.sendMessage(PREFIX + "§aVariable '" + key + "' set to '" + value + "'");
    }

    private void deleteVariable(String key, CommandSender sender) {
        if (varConfig.contains(key.toLowerCase())) {
            varConfig.set(key.toLowerCase(), null);
            saveVariables();
            sender.sendMessage(PREFIX + "§aVariable '" + key + "' deleted.");
        } else {
            sender.sendMessage(PREFIX + "§cVariable '" + key + "' not found.");
        }
    }

    private void checkVariable(String key, CommandSender sender) {
        String val = varConfig.getString(key.toLowerCase(), null);
        if (val != null) {
            sender.sendMessage(PREFIX + "§aVariable value '" + key + "': " + val);
        } else {
            sender.sendMessage(PREFIX + "§cVariable '" + key + "' not found.");
        }
    }

    private void addVariable(String key, String amountStr, CommandSender sender) {
        String existingVal = varConfig.getString(key.toLowerCase());
        if (existingVal == null) existingVal = "0";

        try {
            boolean isWhole = isWholeNumber(existingVal) && isWholeNumber(amountStr);

            if (isWhole) {
                int current = Integer.parseInt(existingVal);
                int toAdd = Integer.parseInt(amountStr);
                int result = current + toAdd;
                varConfig.set(key.toLowerCase(), String.valueOf(result));
                saveVariables();
                sender.sendMessage(PREFIX + "§aVariable '" + key + "' increased by " + toAdd + ". New value: " + result);
            } else {
                double current = Double.parseDouble(existingVal);
                double toAdd = Double.parseDouble(amountStr);
                double result = current + toAdd;
                varConfig.set(key.toLowerCase(), String.valueOf(result));
                saveVariables();
                sender.sendMessage(PREFIX + "§aVariable '" + key + "' increased by " + toAdd + ". New value: " + result);
            }
        } catch (NumberFormatException e) {
            sender.sendMessage(PREFIX + "§cError: Variable or value is not a number.");
        }
    }

    private boolean isWholeNumber(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean canModifyPlayerKey(String key, CommandSender sender, String targetPlayer) {
        if (targetPlayer == null) return true;
        if (!(sender.isOp() || sender.hasPermission("var.admin"))) {
            if (sender instanceof Player && !((Player) sender).getName().equalsIgnoreCase(targetPlayer)) {
                sender.sendMessage(PREFIX + "§cYou cannot change other people's variables.");
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "§cUsage: /var <set|add|delete|check> <variable> [value] [player]");
            return true;
        }

        String action = args[0].toLowerCase();
        String baseKey = args[1];
        String value = args.length >= 3 ? args[2] : null;
        String player = args.length >= 4 ? args[3] : null;

        String key = (player != null) ? baseKey + "_" + player.toLowerCase() : baseKey.toLowerCase();

        if (!canModifyPlayerKey(key, sender, player)) return true;

        switch (action) {
            case "set":
                if (value == null) {
                    sender.sendMessage(PREFIX + "§cTo set the change, you need to enter a value.");
                    return true;
                }
                setVariable(key, value, sender);
                break;
            case "add":
                if (value == null) {
                    sender.sendMessage(PREFIX + "§cTo add you need to specify a number.");
                    return true;
                }
                addVariable(key, value, sender);
                break;
            case "delete":
                deleteVariable(key, sender);
                break;
            case "check":
                checkVariable(key, sender);
                break;
            default:
                sender.sendMessage(PREFIX + "§cUnknown action. Use set, add, delete, or check.");
                break;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("set", "add", "delete", "check");
        } else if (args.length == 2) {
            List<String> keys = new ArrayList<>(varConfig.getKeys(false));
            return keys;
        } else if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return List.of("Value");
        } else if (args.length == 4) {
            List<String> players = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                players.add(p.getName());
            }
            return players;
        }
        return List.of();
    }

    public class VarPlaceholder extends PlaceholderExpansion {

        @Override
        public String getIdentifier() {
            return "var";
        }

        @Override
        public String getAuthor() {
            return "Haizenfell";
        }

        @Override
        public String getVersion() {
            return "1.0";
        }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (identifier == null) return null;

            if (identifier.startsWith("player_key:")) {
                if (player == null) return "null";

                String baseKey = identifier.substring("player_key:".length());
                String fullKey = baseKey + "_" + player.getName().toLowerCase();
                String value = varConfig.getString(fullKey);
                return value != null ? value : "null";
            }

            String value = varConfig.getString(identifier);
            return value != null ? value : "null";
        }
    }

    public class VarPlayerKeyPlaceholder extends PlaceholderExpansion {

        @Override
        public String getIdentifier() {
            return "var_player_key";
        }

        @Override
        public String getAuthor() {
            return "Haizenfell";
        }

        @Override
        public String getVersion() {
            return "1.0";
        }

        @Override
        public String onPlaceholderRequest(Player player, String identifier) {
            if (player == null) return "null";

            String fullKey = identifier + "_" + player.getName().toLowerCase();
            String value = varConfig.getString(fullKey);
            return value != null ? value : "null";
        }
    }
}
