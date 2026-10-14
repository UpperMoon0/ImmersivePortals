package qouteall.imm_ptl.core.gametest;

import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat;
import qouteall.imm_ptl.core.portal.Portal;

import java.lang.reflect.Method;
import java.util.UUID;

/** Native guard regression, not an end-to-end flight or network handoff certification. */
final class SableMountedEyeApproachGameTestImpl {
    static void run(GameTestHelper helper) {
        var level = helper.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        Pose3d pose = new Pose3d();
        pose.position().set(600, 100, 600);
        ServerSubLevel body = (ServerSubLevel) container.allocateNewSubLevel(pose);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "MountedEyeTest"));
        ArmorStand seat = new ArmorStand(level, 0, 0, 0);
        ArmorStand intermediate = new ArmorStand(level, 0, 0, 0);
        Portal portal = Portal.ENTITY_TYPE.create(level);
        if (portal == null) throw new IllegalStateException("Portal entity type is unavailable");
        try {
            body.getPlot().newEmptyChunk(body.getPlot().getCenterChunk());
            body.getPlot().getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
            body.buildMassTracker();
            body.updateLastPose();
            body.updateBoundingBox();
            RigidBodyHandle handle = RigidBodyHandle.of(body);
            helper.assertTrue(handle != null && handle.isValid(), "Fixture needs a real native body");
            portal.setOriginPos(new Vec3(600, 100, 600));
            portal.setDestinationDimension(Level.NETHER);
            portal.setDestination(new Vec3(600, 100, 600));
            Method approach = SableDimensionStackCompat.class.getDeclaredMethod(
                "isRiddenBodyApproachingPortal", ServerSubLevel.class, Entity.class, Entity.class, Portal.class);
            approach.setAccessible(true);

            helper.assertTrue(player.startRiding(seat, true), "Direct rider must actually mount the seat");
            checkAngles(helper, body, handle, player, seat, portal, approach);
            player.stopRiding();
            helper.assertTrue(intermediate.startRiding(seat, true), "Intermediate passenger must mount the seat");
            helper.assertTrue(player.startRiding(intermediate, true), "Player must mount the intermediate passenger");
            helper.assertTrue(player.getVehicle() == intermediate && intermediate.getVehicle() == seat,
                "Nested graph must remain connected");
            // The outer retained vehicle's immediate passenger is the intermediate entity,
            // matching the same traversal used by the authoritative crossing witnesses.
            checkAngles(helper, body, handle, intermediate, seat, portal, approach);
            helper.succeed();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot exercise production mounted-eye guard", error);
        } finally {
            player.stopRiding();
            intermediate.stopRiding();
            player.discard();
            intermediate.discard();
            seat.discard();
            portal.discard();
            if (container.getSubLevel(body.getUniqueId()) == body) {
                container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            }
        }
    }

    private static void checkAngles(GameTestHelper helper, ServerSubLevel body, RigidBodyHandle handle,
                                    Entity passenger, Entity seat, Portal portal, Method approach)
        throws ReflectiveOperationException {
        Vector3d center = new Vector3d(body.getSelfMassTracker().getCenterOfMass());
        seat.setPos(center.x, center.y, center.z);
        double eyeHeight = seat.getPassengerRidingPosition(passenger).add(McHelper.getEyeOffset(passenger)).y - seat.getY();
        helper.assertTrue(eyeHeight > 0.1, "Actual mounted eye must be above the seat origin");
        // Put COM between seat and eye: they must have opposite angular normal velocities.
        seat.setPos(center.x, center.y - eyeHeight / 2, center.z);
        for (boolean pitch : new boolean[] {false, true}) {
            portal.setOrientationAndSize(pitch ? new Vec3(-1, 0, 0) : new Vec3(0, 0, -1),
                new Vec3(0, 1, 0), 8, 8); // normals -Z (pitch) and +X (roll)
            Vector3d angular = pitch ? new Vector3d(2, 0, 0) : new Vector3d(0, 0, 2);
            setVelocity(handle, angular);
            helper.assertTrue(handle.getLinearVelocity(new Vector3d()).length() < 1e-6,
                "COM must be stationary, so it cannot supply a crossing fallback");
            helper.assertTrue(handle.getAngularVelocity(new Vector3d()).distance(angular) < 1e-6,
                "Native angular velocity must match the roll/pitch fixture");
            helper.assertTrue((boolean) approach.invoke(null, body, passenger, seat, portal),
                "Inward mounted eye must pass despite outward seat motion: pitch=" + pitch);
            setVelocity(handle, angular.negate());
            helper.assertTrue(!(boolean) approach.invoke(null, body, passenger, seat, portal),
                "Outward mounted eye must fail despite inward seat motion: pitch=" + pitch);
            setVelocity(handle, new Vector3d());
            helper.assertTrue(!(boolean) approach.invoke(null, body, passenger, seat, portal),
                "Stationary mounted eye cannot authorize a stale request");
        }
    }

    private static void setVelocity(RigidBodyHandle handle, Vector3d angular) {
        handle.addLinearAndAngularVelocity(handle.getLinearVelocity(new Vector3d()).negate(),
            angular.sub(handle.getAngularVelocity(new Vector3d()), new Vector3d()));
    }
}
