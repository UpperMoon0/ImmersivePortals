package qouteall.imm_ptl.core.compat.sable;

/** All correlated handoff phases block reverse prediction, regardless of initiator. */
final class SableClientHandoffGate {
    private SableClientHandoffGate() {}
    static boolean isActive(Object pending, Object readyToApply, Object standingRebase) {
        return pending != null || readyToApply != null || standingRebase != null;
    }
}
