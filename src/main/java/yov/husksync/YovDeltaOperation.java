package yov.husksync;

import java.math.BigDecimal;
import java.util.Objects;

public record YovDeltaOperation(Type type, String variable, String operand) {

    public YovDeltaOperation {
        type = Objects.requireNonNull(type, "type");
        switch (type) {
            case SET -> {
                if (variable == null || variable.isBlank()) {
                    throw new IllegalArgumentException("SET requires a variable name.");
                }
                Objects.requireNonNull(operand, "SET requires a value.");
            }
            case DELETE -> {
                if (variable == null || variable.isBlank()) {
                    throw new IllegalArgumentException("DELETE requires a variable name.");
                }
                operand = null;
            }
            case ADD, REMOVE -> {
                if (variable == null || variable.isBlank()) {
                    throw new IllegalArgumentException(type + " requires a variable name.");
                }
                if (operand == null) {
                    throw new IllegalArgumentException(type + " requires an amount.");
                }
                new BigDecimal(operand);
            }
            case CLEAR -> {
                variable = null;
                operand = null;
            }
        }
    }

    public static YovDeltaOperation set(String variable, String value) {
        return new YovDeltaOperation(Type.SET, variable, value);
    }

    public static YovDeltaOperation delete(String variable) {
        return new YovDeltaOperation(Type.DELETE, variable, null);
    }

    public static YovDeltaOperation add(String variable, BigDecimal amount) {
        return new YovDeltaOperation(Type.ADD, variable, amount.toString());
    }

    public static YovDeltaOperation remove(String variable, BigDecimal amount) {
        return new YovDeltaOperation(Type.REMOVE, variable, amount.toString());
    }

    public static YovDeltaOperation clear() {
        return new YovDeltaOperation(Type.CLEAR, null, null);
    }

    public enum Type {
        SET,
        DELETE,
        ADD,
        REMOVE,
        CLEAR
    }
}
