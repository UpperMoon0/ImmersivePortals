package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import java.util.IdentityHashMap;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.util.List;
import java.util.Map;

/** Test-only preservation oracle at the graft boundary, before normal native pruning. */
public final class SableInterpolationHistoryProbe {
    private static final Map<ClientSubLevel, Integer> verified = new IdentityHashMap<>();
    private SableInterpolationHistoryProbe() {}

    public static void verify(ClientSubLevel body, int fullSyncTick,
        List<SubLevelSnapshotInterpolator.Snapshot> expected,
        List<SubLevelSnapshotInterpolator.Snapshot> actual) {
        checkCopies(expected, actual);
        require(actual.stream().anyMatch(s -> s.gameTick() == fullSyncTick - 1)
            && actual.stream().anyMatch(s -> s.gameTick() == fullSyncTick), "Native full-sync samples missing after graft");
        verified.put(body, fullSyncTick);
    }

    static void reset() { verified.clear(); }
    static void requireVerified(ClientSubLevel body) {
        require(verified.containsKey(body), "Current destination instance has no verified interpolation graft");
    }

    static void checkCopies(List<SubLevelSnapshotInterpolator.Snapshot> expected,
        List<SubLevelSnapshotInterpolator.Snapshot> actual) {
        for (var sample : expected) {
            require(actual.stream().anyMatch(value -> value.gameTick() == sample.gameTick()
                && value.pose().position().distance(sample.pose().position()) < 1e-8
                && Math.abs(value.pose().orientation().dot(sample.pose().orientation())) > 1 - 1e-8
                && value.pose().scale().distance(sample.pose().scale()) < 1e-8
                && value.pose().rotationPoint().distance(sample.pose().rotationPoint()) < 1e-8),
                "Eligible transformed history sample missing or changed at graft: tick=" + sample.gameTick());
        }
    }

    static void verifyReceiptIsolation(ClientSubLevel first, ClientSubLevel other) {
        first.getInterpolator().receiveSnapshot(0, new dev.ryanhcode.sable.companion.math.Pose3d());
        first.getInterpolator().receiveSnapshot(1, new dev.ryanhcode.sable.companion.math.Pose3d());
        verify(first, 1, List.of(), first.getInterpolator().buffer);
        requireVerified(first);
        boolean rejected = false;
        try { requireVerified(other); }
        catch (IllegalStateException missing) { rejected = true; }
        require(rejected, "Another instance with the same body UUID reused a graft receipt");
        first.getInterpolator().buffer.clear();
    }

    static void verifyNativePruning() {
        var pose = new dev.ryanhcode.sable.companion.math.Pose3d();
        var interpolator = new SubLevelSnapshotInterpolator(pose);
        for (int tick : new int[] {1707, 1708, 1709, 1718, 1719}) interpolator.receiveSnapshot(tick, pose);
        var expected = List.copyOf(interpolator.buffer.subList(0, 3));
        checkCopies(expected, interpolator.buffer);
        boolean rejected = false;
        try { checkCopies(expected, interpolator.buffer.subList(3, 5)); }
        catch (IllegalStateException missing) { rejected = true; }
        require(rejected, "Preservation oracle accepted discarded eligible history");
        interpolator.tick(1718);
        require(interpolator.buffer.size() == 2 && interpolator.buffer.getFirst().gameTick() == 1718,
            "Pinned native pruning control changed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
