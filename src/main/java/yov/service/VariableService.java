package yov.service;

import org.bukkit.command.CommandSender;
import yov.cache.VariableCache;
import yov.storage.StorageBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VariableService {

    private StorageBackend backend;
    private final VariableCache cache;
    private final Logger logger;
    private final String prefix;

    public VariableService(StorageBackend backend,
                           VariableCache cache,
                           Logger logger,
                           String prefix) {
        this.backend = backend;
        this.cache = cache;
        this.logger = logger;
        this.prefix = prefix;
    }

    public void setBackend(StorageBackend backend) {
        this.backend = backend;
    }

    public void setVariable(String key, String value, CommandSender sender, boolean silent) {
        cache.put(key, value);
        try {
            backend.set(key, value);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error saving variable " + key, e);
        }
        if (!silent) {
            sender.sendMessage(prefix + "§aVariable '" + key + "' set to '" + value + "'");
        }
    }

    public void deleteVariable(String key, CommandSender sender, boolean silent) {
        cache.remove(key);
        try {
            backend.delete(key);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error deleting variable " + key, e);
        }
        if (!silent) {
            sender.sendMessage(prefix + "§aVariable '" + key + "' deleted.");
        }
    }

    public void addVariable(String key, String amountStr, CommandSender sender, boolean silent) {
        modifyVariable(key, amountStr, sender, silent, true);
    }

    public void remVariable(String key, String amountStr, CommandSender sender, boolean silent) {
        modifyVariable(key, amountStr, sender, silent, false);
    }

    private boolean isWholeNumber(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void modifyVariable(String key, String amountStr, CommandSender sender,
                                boolean silent, boolean isAdd) {

        String existingVal = cache.getOrDefault(key, "0");
        try {
            if (isWholeNumber(existingVal) && isWholeNumber(amountStr)) {
                int current = Integer.parseInt(existingVal);
                int delta = Integer.parseInt(amountStr);
                int result = isAdd ? current + delta : current - delta;

                setVariable(key, String.valueOf(result), sender, silent);

                if (!silent) {
                    sender.sendMessage(prefix + "§aVariable '" + key + "' "
                            + (isAdd ? "increased" : "decreased")
                            + " by " + delta + ". New value: " + result);
                }
            } else {
                double current = Double.parseDouble(existingVal);
                double delta = Double.parseDouble(amountStr);
                double result = isAdd ? current + delta : current - delta;

                setVariable(key, String.valueOf(result), sender, silent);

                if (!silent) {
                    sender.sendMessage(prefix + "§aVariable '" + key + "' "
                            + (isAdd ? "increased" : "decreased")
                            + " by " + delta + ". New value: " + result);
                }
            }
        } catch (NumberFormatException e) {
            if (!silent) {
                sender.sendMessage(prefix + "§cError: Variable or value is not a number.");
            }
        }
    }

    public void clearPlayerVariables(String playerName, CommandSender sender) {
        String prefixKey = playerName.toLowerCase() + "_";

        Map<String, String> map = cache.getMap();
        List<String> toRemove = new ArrayList<>();

        for (String key : map.keySet()) {
            if (key.startsWith(prefixKey)) {
                toRemove.add(key);
            }
        }

        int removed = 0;

        for (String key : toRemove) {
            cache.remove(key);
            try {
                backend.delete(key);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error deleting variable " + key + " for userclear", e);
            }
            removed++;
        }

        sender.sendMessage(prefix + "§aRemoved §e" + removed + " §avariables for player §e" + playerName + "§a.");
    }
}
