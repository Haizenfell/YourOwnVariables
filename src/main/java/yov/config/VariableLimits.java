package yov.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

public record VariableLimits(int maxVariablesPerPlayer,
                             int maxVariableNameBytes,
                             int maxValueBytes,
                             int maxDataBytesPerPlayer,
                             int maxPendingOnlinePerPlayer,
                             int maxPendingOfflinePerPlayer,
                             int maxPendingOfflineGlobal,
                             int maxPendingApiCallsGlobal) {

    public static final int DEFAULT_MAX_VARIABLES = 256;
    public static final int DEFAULT_MAX_NAME_BYTES = 128;
    public static final int DEFAULT_MAX_VALUE_BYTES = 16 * 1024;
    public static final int DEFAULT_MAX_DATA_BYTES = 256 * 1024;
    public static final int DEFAULT_MAX_PENDING_ONLINE = 256;
    public static final int DEFAULT_MAX_PENDING_OFFLINE_PER_PLAYER = 64;
    public static final int DEFAULT_MAX_PENDING_OFFLINE_GLOBAL = 2048;
    public static final int DEFAULT_MAX_PENDING_API_CALLS_GLOBAL = 8192;

    public static VariableLimits from(FileConfiguration config) {
        return new VariableLimits(
                positive(config.getInt("limits.max-variables-per-player", DEFAULT_MAX_VARIABLES),
                        DEFAULT_MAX_VARIABLES),
                positive(config.getInt("limits.max-variable-name-bytes", DEFAULT_MAX_NAME_BYTES),
                        DEFAULT_MAX_NAME_BYTES),
                positive(config.getInt("limits.max-value-bytes", DEFAULT_MAX_VALUE_BYTES),
                        DEFAULT_MAX_VALUE_BYTES),
                positive(config.getInt("limits.max-data-bytes-per-player", DEFAULT_MAX_DATA_BYTES),
                        DEFAULT_MAX_DATA_BYTES),
                positive(config.getInt("limits.max-pending-online-mutations-per-player",
                                DEFAULT_MAX_PENDING_ONLINE),
                        DEFAULT_MAX_PENDING_ONLINE),
                positive(config.getInt("limits.max-pending-offline-mutations-per-player",
                                DEFAULT_MAX_PENDING_OFFLINE_PER_PLAYER),
                        DEFAULT_MAX_PENDING_OFFLINE_PER_PLAYER),
                positive(config.getInt("limits.max-pending-offline-mutations-global",
                                DEFAULT_MAX_PENDING_OFFLINE_GLOBAL),
                        DEFAULT_MAX_PENDING_OFFLINE_GLOBAL),
                positive(config.getInt("limits.max-pending-api-calls-global",
                                DEFAULT_MAX_PENDING_API_CALLS_GLOBAL),
                        DEFAULT_MAX_PENDING_API_CALLS_GLOBAL));
    }

    public Optional<String> validateEntry(String variable, String value) {
        if (variable == null || variable.isBlank()) {
            return Optional.of("Variable name cannot be empty.");
        }
        if (value == null) {
            return Optional.of("Variable value cannot be null.");
        }

        int nameBytes = utf8Bytes(variable);
        if (nameBytes > maxVariableNameBytes) {
            return Optional.of("Variable name is too large (" + nameBytes + "/"
                    + maxVariableNameBytes + " UTF-8 bytes).");
        }
        int valueBytes = utf8Bytes(value);
        if (valueBytes > maxValueBytes) {
            return Optional.of("Variable value is too large (" + valueBytes + "/"
                    + maxValueBytes + " UTF-8 bytes).");
        }
        return Optional.empty();
    }

    public Optional<String> validateSnapshot(Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) return Optional.empty();
        if (variables.size() > maxVariablesPerPlayer) {
            return Optional.of("Player has too many variables (" + variables.size() + "/"
                    + maxVariablesPerPlayer + ").");
        }

        long serializedBytes = 16L;
        boolean first = true;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            Optional<String> invalid = validateEntry(entry.getKey(), entry.getValue());
            if (invalid.isPresent()) return invalid;
            if (!first) serializedBytes++;
            first = false;
            serializedBytes += jsonStringBytes(entry.getKey()) + 1L
                    + jsonStringBytes(entry.getValue());
            if (serializedBytes > maxDataBytesPerPlayer) {
                return Optional.of("Player variable data is too large (more than "
                        + maxDataBytesPerPlayer + " serialized bytes).");
            }
        }
        return Optional.empty();
    }

    public int estimatedSerializedBytes(Map<String, String> variables) {
        long bytes = 16L;
        if (variables != null) {
            boolean first = true;
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                if (!first) bytes++;
                first = false;
                bytes += jsonStringBytes(entry.getKey()) + 1L + jsonStringBytes(entry.getValue());
                if (bytes >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
        }
        return (int) bytes;
    }

    private static int positive(int configured, int fallback) {
        return configured > 0 ? configured : fallback;
    }

    private static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static long jsonStringBytes(String value) {
        if (value == null) return 4L;
        long bytes = 2L;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '"' || codePoint == '\\' || codePoint == '\b'
                    || codePoint == '\f' || codePoint == '\n' || codePoint == '\r'
                    || codePoint == '\t') {
                bytes += 2L;
            } else if (codePoint >= 0 && codePoint <= 0x1f) {
                bytes += 6L;
            } else if (codePoint <= 0x7f) {
                bytes++;
            } else if (codePoint <= 0x7ff) {
                bytes += 2L;
            } else if (codePoint <= 0xffff) {
                bytes += 3L;
            } else {
                bytes += 4L;
            }
        }
        return bytes;
    }
}
