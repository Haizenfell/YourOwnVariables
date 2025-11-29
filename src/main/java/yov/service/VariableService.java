/*
 * This file is part of YourOwnVariables.
 * Copyright (C) 2025 Haizenfell
 *
 * Licensed under the YourOwnVariables Proprietary License.
 * Unauthorized copying, modification, distribution, or reverse engineering
 * of this software is strictly prohibited.
 *
 * Full license text is provided in the LICENSE file.
 */
package yov.service;

import org.bukkit.command.CommandSender;
import yov.async.WriteQueue;
import yov.cache.VariableCache;
import yov.storage.StorageBackend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class VariableService {

    private final StorageBackend backend;
    private final VariableCache cache;
    private final Logger logger;
    private final String prefix;
    private final WriteQueue writeQueue;

    public VariableService(StorageBackend backend,
                           VariableCache cache,
                           Logger logger,
                           String prefix,
                           WriteQueue writeQueue) {
        this.backend = backend;
        this.cache = cache;
        this.logger = logger;
        this.prefix = prefix;
        this.writeQueue = writeQueue;
    }

    public WriteQueue getWriteQueue() {
        return writeQueue;
    }

    public void setVariable(String key, String value, CommandSender sender, boolean silent) {
        cache.put(key, value);
        try {
            writeQueue.enqueueSet(key, value);
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
            writeQueue.enqueueDelete(key);
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

        cache.compute(key, (k, oldVal) -> {

            if (oldVal == null) oldVal = "0";

            try {
                if (isWholeNumber(oldVal) && isWholeNumber(amountStr)) {
                    int current = Integer.parseInt(oldVal);
                    int delta = Integer.parseInt(amountStr);
                    int result = isAdd ? current + delta : current - delta;

                    enqueueModify(k, String.valueOf(result));

                    if (!silent) {
                        sender.sendMessage(prefix + "§aVariable '" + k + "' " +
                                (isAdd ? "increased" : "decreased") +
                                " by " + delta + ". New value: " + result);
                    }

                    return String.valueOf(result);
                }

                double current = Double.parseDouble(oldVal);
                double delta = Double.parseDouble(amountStr);
                double result = isAdd ? current + delta : current - delta;

                enqueueModify(k, String.valueOf(result));

                if (!silent) {
                    sender.sendMessage(prefix + "§aVariable '" + k + "' " +
                            (isAdd ? "increased" : "decreased") +
                            " by " + delta + ". New value: " + result);
                }

                return String.valueOf(result);

            } catch (Exception e) {
                if (!silent) {
                    sender.sendMessage(prefix + "§cError: Invalid number.");
                }
                return oldVal;
            }
        });
    }

    private void enqueueModify(String key, String value) {
        writeQueue.enqueueSet(key, value);
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
                writeQueue.enqueueDelete(key);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error deleting variable " + key + " for userclear", e);
            }
            removed++;
        }

        sender.sendMessage(prefix + "§aRemoved §e" + removed + " §avariables for player §e" + playerName + "§a.");
    }
}
