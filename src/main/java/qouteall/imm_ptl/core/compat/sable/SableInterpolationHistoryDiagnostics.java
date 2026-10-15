package qouteall.imm_ptl.core.compat.sable;

import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.util.List;

/** Optional graft observer. Production bytecode must never reference excluded test drivers. */
public final class SableInterpolationHistoryDiagnostics {
    @FunctionalInterface
    public interface Observer {
        void verify(ClientSubLevel body, int fullSyncTick,
            List<SubLevelSnapshotInterpolator.Snapshot> expected,
            List<SubLevelSnapshotInterpolator.Snapshot> actual);
    }

    private static Observer observer;

    private SableInterpolationHistoryDiagnostics() {}

    public static void setObserver(Observer value) {
        observer = value;
    }

    public static void verify(ClientSubLevel body, int fullSyncTick,
        List<SubLevelSnapshotInterpolator.Snapshot> expected,
        List<SubLevelSnapshotInterpolator.Snapshot> actual) {
        if (observer != null) observer.verify(body, fullSyncTick, expected, actual);
    }
}
