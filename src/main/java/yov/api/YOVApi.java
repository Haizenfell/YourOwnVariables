package yov.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface YOVApi {

    /**
     * Returns a fast in-memory value for a loaded or cached player.
     * This method never queries HuskSync. Use {@link #getCurrentVariable(String, String)}
     * when the player may be offline or absent from the local cache.
     */
    @Nullable
    String getVariable(@NotNull String playerName, @NotNull String variable);

    @NotNull
    default String getVariableOrDefault(@NotNull String playerName,
                                        @NotNull String variable,
                                        @NotNull String defaultValue) {
        String value = getVariable(playerName, variable);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns the local in-memory variable snapshot for a player.
     * This method never queries HuskSync.
     */
    @NotNull
    Map<String, String> getVariables(@NotNull String playerName);

    /**
     * Returns a variable from local memory when available, otherwise pulls
     * the current player data from HuskSync.
     */
    @NotNull
    CompletableFuture<Optional<String>> getCurrentVariable(@NotNull String playerName, @NotNull String variable);

    /**
     * Returns all variables from local memory when available, otherwise pulls
     * the current player data from HuskSync.
     */
    @NotNull
    CompletableFuture<Map<String, String>> getCurrentVariables(@NotNull String playerName);

    @NotNull
    CompletableFuture<YOVOperationResult> setVariable(@NotNull String playerName,
                                                      @NotNull String variable,
                                                      @NotNull String value);

    @NotNull
    CompletableFuture<YOVOperationResult> addVariable(@NotNull String playerName,
                                                      @NotNull String variable,
                                                      @NotNull BigDecimal amount);

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
    CompletableFuture<YOVOperationResult> remVariable(@NotNull String playerName,
                                                      @NotNull String variable,
                                                      @NotNull BigDecimal amount);

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
    CompletableFuture<YOVOperationResult> deleteVariable(@NotNull String playerName, @NotNull String variable);

    @NotNull
    CompletableFuture<YOVOperationResult> clearVariables(@NotNull String playerName);

    boolean isPlayerLoaded(@NotNull String playerName);

    boolean hasPlayerSnapshot(@NotNull String playerName);

    @NotNull
    List<String> getKnownVariableNames();

    @Nullable
    String normalizeVariable(@Nullable String variable);
}
