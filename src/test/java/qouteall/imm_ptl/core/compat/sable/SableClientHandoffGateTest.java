package qouteall.imm_ptl.core.compat.sable;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class SableClientHandoffGateTest {
    private record Request(UUID nonce, UUID support) {}
    @Test void dimensionPacketCannotReplacePendingClientRequestWithReversePrediction() {
        Request original = new Request(UUID.randomUUID(), UUID.randomUUID());
        Request pending = original;
        UUID portalGate = null; // authoritative dimension packet cleared IP's ordinary gate
        if (portalGate == null && !SableClientHandoffGate.isActive(pending, null, null))
            pending = new Request(UUID.randomUUID(), null);
        assertSame(original, pending);
        assertEquals(original.nonce(), pending.nonce());
        assertEquals(original.support(), pending.support());
        Request ready = pending;
        pending = null;
        assertTrue(SableClientHandoffGate.isActive(pending, ready, null));
        Request rebase = ready;
        ready = null;
        assertTrue(SableClientHandoffGate.isActive(pending, ready, rebase));
        rebase = null;
        assertFalse(SableClientHandoffGate.isActive(pending, ready, rebase));
    }
    @Test void eitherInitiatorAndEveryPhaseBlocksPrediction() {
        assertTrue(SableClientHandoffGate.isActive(new Object(), null, null));
        assertTrue(SableClientHandoffGate.isActive(null, new Object(), null));
        assertTrue(SableClientHandoffGate.isActive(null, null, new Object()));
    }
    @Test void clearedRejectedExpiredOrDisconnectedStateAllowsPrediction() {
        assertFalse(SableClientHandoffGate.isActive(null, null, null));
    }
}
