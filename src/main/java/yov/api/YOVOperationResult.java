package yov.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record YOVOperationResult(boolean success,
                                 String message,
                                 String value,
                                 Map<String, String> variables) {

    public YOVOperationResult {
        message = message == null ? "" : message;
        variables = variables == null || variables.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public static YOVOperationResult success(String message, String value, Map<String, String> variables) {
        return new YOVOperationResult(true, message, value, variables);
    }

    public static YOVOperationResult failure(String message) {
        return new YOVOperationResult(false, message, null, Map.of());
    }

    public boolean failed() {
        return !success;
    }
}
