package qouteall.imm_ptl.core.compat.sable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SableRenderOwnerEpochTest {
    @Test void unpublishedOrInvalidatedOwnerCannotGrant() {
        var epoch=new SableRenderOwnerEpoch<Object>();
        assertFalse(epoch.test(value->{ fail("No snapshot"); return true; }));
        epoch.publish(new Object()); assertTrue(epoch.test(value->true));
        epoch.invalidate(); assertFalse(epoch.test(value->true));
    }
    @Test void resizeDuringNeighborReadRejectsOldGeneration() {
        var epoch=new SableRenderOwnerEpoch<Object>(); epoch.publish(new Object());
        assertFalse(epoch.test(value->{ epoch.invalidate(); return true; }));
    }
    @Test void closeDuringNeighborReadRejectsAndCannotReopen() {
        var epoch=new SableRenderOwnerEpoch<Object>(); epoch.publish(new Object());
        assertFalse(epoch.test(value->{ epoch.close(); return true; }));
        epoch.publish(new Object()); assertFalse(epoch.test(value->true));
        assertTrue(epoch.isClosed());
    }
    @Test void replacingWithSamePayloadStillChangesGeneration() {
        var epoch=new SableRenderOwnerEpoch<Object>(); Object value=new Object(); epoch.publish(value);
        assertFalse(epoch.test(old->{ epoch.invalidate(); epoch.publish(value); return true; }));
        assertTrue(epoch.test(current->current==value));
    }
    @Test void predicateFailureAndDistinctOwnerLifetimesStayIndependent() {
        var first=new SableRenderOwnerEpoch<String>(); var second=new SableRenderOwnerEpoch<String>();
        first.publish("first"); second.publish("second");
        assertFalse(first.test(value->false));
        assertTrue(first.test(value->{ second.close(); return value.equals("first"); }));
        assertFalse(second.test(value->true));
    }
}
