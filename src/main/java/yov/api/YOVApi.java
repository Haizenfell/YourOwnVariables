package yov.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface YOVApi {

    @Nullable
    String getVariable(@NotNull UUID playerId, @NotNull String variable);

    @Nullable
    String getVariable(@NotNull String playerName, @NotNull String variable);

    @NotNull
    Map<String, String> getVariables(@NotNull UUID playerId);

    @NotNull
    default String getVariableOrDefault(@NotNull UUID playerId,
                                        @NotNull String variable,
                                        @NotNull String defaultValue) {
        String value = getVariable(playerId, variable);
        return value != null ? value : defaultValue;
    }

    @NotNull
    default String getVariableOrDefault(@NotNull String playerName,
                                        @NotNull String variable,
                                        @NotNull String defaultValue) {
        String value = getVariable(playerName, variable);
        return value != null ? value : defaultValue;
    }

    @NotNull
    Map<String, String> getVariables(@NotNull String playerName);

    @NotNull
    CompletableFuture<Map<String, String>> whenPlayerReady(@NotNull UUID playerId);

    @NotNull
    CompletableFuture<Optional<String>> getCurrentVariable(@NotNull UUID playerId, @NotNull String variable);

    @NotNull
    CompletableFuture<Optional<String>> getCurrentVariable(@NotNull String playerName, @NotNull String variable);

    @NotNull
    CompletableFuture<Map<String, String>> getCurrentVariables(@NotNull UUID playerId);

    @NotNull
    CompletableFuture<Map<String, String>> getCurrentVariables(@NotNull String playerName);

    @NotNull
    CompletableFuture<YOVOperationResult> setVariable(@NotNull UUID playerId,
                                                      @NotNull String variable,
                                                      @NotNull String value);

    @NotNull
    CompletableFuture<YOVOperationResult> setVariable(@NotNull String playerName,
                                                      @NotNull String variable,
                                                      @NotNull String value);

    @NotNull
    CompletableFuture<YOVOperationResult> addVariable(@NotNull UUID playerId,
                                                      @NotNull String variable,
                                                      @NotNull BigDecimal amount);

    @NotNull
    CompletableFuture<YOVOperationResult> addVariable(@NotNull String playerName,
                                                      @NotNull String variable,
                                                      @NotNull BigDecimal amount);

    @NotNull
    default CompletableFuture<YOVOperationResult> addVariable(@NotNull UUID playerId,
                                                              @NotNull String variable,
                                                              @NotNull String amount) {
        try {
            return addVariable(playerId, variable, new BigDecimal(amount.trim()));
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(YOVOperationResult.failure("Invalid number: " + amount));
        }
    }

    @NotNull
    default CompletableFuture<YOVOperationResult> addVariable(@NotNull String playerName,
                                                              @NotNull String variable,
                                                              @NotNull String amount) {
        try {
            return addVariable(playerName, variable, new BigDecimal(amount.trim()));
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(YOVOperationResult.failure("Invalid number: " + amount));
        }
    }

    @NotNull
    CompletableFuture<YOVOperationResult> remVariable(@NotNull UUID playerId,
                                                      @NotNull String variable,
                                                      @NotNull BigDecimal amount);

    @NotNull
    CompletableFuture<YOVOperationResult> remVariable(@NotNull String playerName,
                                                      @NotNull String variable,
                                                      @NotNull BigDecimal amount);

    @NotNull
    default CompletableFuture<YOVOperationResult> remVariable(@NotNull UUID playerId,
                                                              @NotNull String variable,
                                                              @NotNull String amount) {
        try {
            return remVariable(playerId, variable, new BigDecimal(amount.trim()));
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(YOVOperationResult.failure("Invalid number: " + amount));
        }
    }

    @NotNull
    default CompletableFuture<YOVOperationResult> remVariable(@NotNull String playerName,
                                                              @NotNull String variable,
                                                              @NotNull String amount) {
        try {
            return remVariable(playerName, variable, new BigDecimal(amount.trim()));
        } catch (RuntimeException ex) {
            return CompletableFuture.completedFuture(YOVOperationResult.failure("Invalid number: " + amount));
        }
    }

    @NotNull
    CompletableFuture<YOVOperationResult> deleteVariable(@NotNull UUID playerId, @NotNull String variable);

    @NotNull
    CompletableFuture<YOVOperationResult> deleteVariable(@NotNull String playerName, @NotNull String variable);

    @NotNull
    CompletableFuture<YOVOperationResult> clearVariables(@NotNull UUID playerId);

    @NotNull
    CompletableFuture<YOVOperationResult> clearVariables(@NotNull String playerName);

    boolean isPlayerReady(@NotNull UUID playerId);

    @Deprecated
    boolean isPlayerLoaded(@NotNull String playerName);

    boolean hasPlayerSnapshot(@NotNull String playerName);

    boolean hasPlayerSnapshot(@NotNull UUID playerId);

    @NotNull
    List<String> getKnownVariableNames();

    @Nullable
    String normalizeVariable(@Nullable String variable);
}
