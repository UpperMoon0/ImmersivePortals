package qouteall.imm_ptl.core.gametest.sablee2e;

/** Development-only join of independently sampled client/server movement evidence. */
final class SableRemoteObserverReadiness {
    private SableRemoteObserverReadiness() {}

    static boolean ready(boolean clientReceipt, double serverDisplacement) {
        return clientReceipt && Double.isFinite(serverDisplacement) && serverDisplacement >= 0.25;
    }
}
