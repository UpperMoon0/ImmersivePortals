package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/** Development-only gate: fault injection must follow both initial promotions. */
final class PortalSmokeChunkRecoveryReadiness {
    private PortalSmokeChunkRecoveryReadiness() {}

    static <T> boolean ready(
        CompletableFuture<T> ticking, CompletableFuture<T> entityTicking, Predicate<T> isSuccess
    ) {
        T tickingResult = ticking.getNow(null);
        T entityResult = entityTicking.getNow(null);
        return tickingResult != null && isSuccess.test(tickingResult)
            && entityResult != null && isSuccess.test(entityResult);
    }
}
