package qouteall.imm_ptl.core.gametest;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;

/** Real native-body regressions for externally moved bodies at the dimension-stack seam. */
final class SableDimensionStackMovementGameTestImpl {
    static void externalMovementAndImmediateReverse(GameTestHelper helper) {
        ServerLevel upper = helper.getLevel().getServer().getLevel(Level.OVERWORLD);
        ServerLevel lower = helper.getLevel().getServer().getLevel(Level.NETHER);
        VerticalConnectingPortal.connectMutually(Level.OVERWORLD, Level.NETHER, false);
        java.util.List<java.util.UUID> bodies = new java.util.ArrayList<>();
        try {
            // Cover an exact-plane endpoint, tiny seam penetration, ordinary movement and a swept high-speed jump.
            // No rider is present: teleportation must be driven by the body itself.
            for (double penetration : new double[] {0.0, 0.01, 0.10, 2.0, 20.0}) {
                checkRoundTrip(lower, upper, penetration, bodies);
            }
            helper.succeed();
        } catch (RuntimeException error) {
            com.mojang.logging.LogUtils.getLogger().error("Sable movement regression failed", error);
            throw error;
        } finally {
            for (ServerLevel level : new ServerLevel[] {lower, upper}) {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                for (var id : bodies) {
                    if (container.getSubLevel(id) instanceof ServerSubLevel remaining) {
                        container.removeSubLevel(remaining, SubLevelRemovalReason.REMOVED);
                    }
                }
            }
            VerticalConnectingPortal.removeConnectingPortal(VerticalConnectingPortal.ConnectorType.floor, Level.OVERWORLD);
            VerticalConnectingPortal.removeConnectingPortal(VerticalConnectingPortal.ConnectorType.ceil, Level.NETHER);
        }
    }

    private static void checkRoundTrip(ServerLevel lower, ServerLevel upper, double penetration, java.util.List<java.util.UUID> bodies) {
        ServerSubLevelContainer source = SubLevelContainer.getContainer(lower);
        ServerSubLevelContainer target = SubLevelContainer.getContainer(upper);
        double ceiling = VerticalConnectingPortal.getConnectingPortal(lower,
            VerticalConnectingPortal.ConnectorType.ceil).getY();
        double floor = upper.getMinBuildHeight();
        Pose3d pose = new Pose3d();
        pose.position().set(500.0, ceiling - 2.0, 500.0);
        ServerSubLevel body = (ServerSubLevel) source.allocateNewSubLevel(pose);
        var id = body.getUniqueId();
        bodies.add(id);
        body.getPlot().newEmptyChunk(body.getPlot().getCenterChunk());
        body.getPlot().getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
        body.buildMassTracker();
        body.updateLastPose();
        body.updateBoundingBox();
        moveCenter(source, body, ceiling - 1.0);
        SableDimensionStackCompat.afterPhysicsSubstep(lower, source);
        Vector3d velocity = new Vector3d(3.0, 8.0, -2.0);
        Vector3d angular = new Vector3d(0.2, -0.3, 0.4);
        RigidBodyHandle handle = RigidBodyHandle.of(body);
        handle.addLinearAndAngularVelocity(velocity, angular);
        moveCenter(source, body, ceiling + penetration);
        SableDimensionStackCompat.afterPhysicsSubstep(lower, source);
        ServerSubLevel arrived = (ServerSubLevel) target.getSubLevel(id);
        require(arrived != null && source.getSubLevel(id) == null, "upward external movement missed Nether ceiling portal");
        require(Math.abs(centerY(arrived) - floor - penetration) < 1.0e-4,
            "body arrived at incorrect Overworld height: " + centerY(arrived) + " expected " + (floor + penetration));
        require(RigidBodyHandle.of(arrived).getLinearVelocity(new Vector3d()).distance(velocity) < 1.0e-4, "linear momentum changed");
        require(RigidBodyHandle.of(arrived).getAngularVelocity(new Vector3d()).distance(angular) < 1.0e-4, "angular momentum changed");
        // Release/reverse before reaching the old 0.25-block clearance threshold.
        // This must work even in the same tick as the forward crossing.
        moveCenter(target, arrived, floor - 0.01);
        SableDimensionStackCompat.afterPhysicsSubstep(upper, target);
        ServerSubLevel returned = (ServerSubLevel) source.getSubLevel(id);
        require(returned != null && target.getSubLevel(id) == null, "immediate return was swallowed at penetration=" + penetration);
        require(Math.abs(centerY(returned) - ceiling + 0.01) < 1.0e-4, "body remained above Nether roof after return");
        require(returned.getPlot().getEmbeddedLevelAccessor().getBlockState(BlockPos.ZERO).is(Blocks.STONE), "blocks lost on reverse crossing");
    }

    private static double centerY(ServerSubLevel body) {
        return body.logicalPose().transformPosition(new Vector3d(body.getSelfMassTracker().getCenterOfMass())).y;
    }

    private static void moveCenter(ServerSubLevelContainer container, ServerSubLevel body, double y) {
        Vector3d position = new Vector3d(body.logicalPose().position());
        position.y += y - centerY(body);
        RigidBodyHandle.of(body).teleport(position, body.logicalPose().orientation());
        container.physicsSystem().updatePose(body);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
