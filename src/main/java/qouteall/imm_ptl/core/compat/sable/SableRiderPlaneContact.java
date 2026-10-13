package qouteall.imm_ptl.core.compat.sable;

/** Bounded front-to-plane history; an initial on-plane sample never grants a crossing. */
final class SableRiderPlaneContact {
    private final long maximumTicks;
    private Object lastState;
    private long firstContactTick;
    private long lastSampleTick;

    SableRiderPlaneContact(long maximumTicks) {
        if (maximumTicks < 0) throw new IllegalArgumentException("Negative contact lifetime");
        this.maximumTicks = maximumTicks;
    }

    boolean sample(Object beforeState, Object afterState, long tick,
                   double beforeZ, double afterZ, boolean insideAperture) {
        if (beforeState == null || afterState == null || !Double.isFinite(beforeZ)
            || !Double.isFinite(afterZ) || !insideAperture) {
            lastState = null;
            return false;
        }
        if (lastState != null && tick <= lastSampleTick) {
            lastState = null;
            return false;
        }
        if (lastState != null && (expired(tick) || tick != lastSampleTick + 1 || beforeState != lastState)) {
            // Discard disconnected history. A fresh front-to-plane segment can still
            // establish its own contact, but an on-plane departure cannot inherit it.
            lastState = null;
        }
        if (beforeZ > 0 && afterZ == 0) {
            lastState = afterState;
            firstContactTick = lastSampleTick = tick;
            return false;
        }
        if (lastState != null && beforeZ == 0 && afterZ <= 0) {
            if (afterZ < 0) {
                lastState = null;
                return true;
            }
            lastState = afterState;
            lastSampleTick = tick;
            return false;
        }
        lastState = null;
        return false;
    }

    boolean isArmed() { return lastState != null; }

    boolean expired(long now) {
        return lastState == null || now < firstContactTick || now - firstContactTick > maximumTicks;
    }
}
