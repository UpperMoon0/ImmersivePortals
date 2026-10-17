package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.Map;
import java.util.UUID;

/** Pure evidence predicates shared by native fixtures and regression tests. */
final class SableStandingBalloonAcceptance {
    private SableStandingBalloonAcceptance() {}
    static boolean allPresent(Map<String, Boolean> chunks) {
        return !chunks.isEmpty() && chunks.values().stream().allMatch(Boolean.TRUE::equals);
    }
    static boolean allAbsent(Map<String, Boolean> chunks) {
        return !chunks.isEmpty() && chunks.values().stream().allMatch(Boolean.FALSE::equals);
    }
    static boolean completed(UUID expectedBody, UUID completedBody, UUID handoff,
                             String source, String destination, long failedAttempts) {
        return expectedBody != null && expectedBody.equals(completedBody) && handoff != null
            && "minecraft:the_nether".equals(source) && "minecraft:overworld".equals(destination)
            && failedAttempts == 0;
    }
}
