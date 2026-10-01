package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.neoforge.gametest.PhysicsTest;
import dev.ryanhcode.sable.neoforge.gametest.SableTestHelper;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measure a full interval after body creation rather than counting setup ticks. */
@Mixin(value = PhysicsTest.class, remap = false)
public abstract class MixinSablePhysicsTest {
    @Inject(method = "testGravity", at = @At("HEAD"), cancellable = true)
    private static void ip_measureGravity(GameTestHelper helper, CallbackInfo ci) {
        var container = SubLevelContainer.getContainer(helper.getLevel());
        var system = container.physicsSystem();
        var body = SableTestHelper.spawnSingleBlockSubLevel(container,
            SableTestHelper.absolutePosition(helper, new Vector3d(2.5, 12, 2.5)),
            Blocks.DIAMOND_BLOCK.defaultBlockState());
        helper.runAfterDelay(2, () -> {
            var handle = system.getPhysicsHandle(body);
            Vector3d startPosition = new Vector3d(body.logicalPose().position());
            Vector3d startVelocity = handle.getLinearVelocity(new Vector3d());
            Vector3d gravity = new Vector3d(DimensionPhysicsData.getGravity(helper.getLevel(), startPosition));
            double step = 1.0 / 20 / system.getConfig().substepsPerTick;
            double drag = DimensionPhysicsData.getUniversalDrag(helper.getLevel());
            Vector3d expectedVelocity = new Vector3d(startVelocity);
            Vector3d expectedDelta = new Vector3d();
            // Rapier advances positions with small solver steps, then applies
            // universal damping once at the end of each Sable physics substep.
            double smallStep = step / system.getConfig().solverIterations;
            for (int i = 0; i < 20 * system.getConfig().substepsPerTick; i++) {
                expectedDelta.fma(step, expectedVelocity)
                    .fma(0.5 * step * (step + smallStep), gravity);
                expectedVelocity.fma(step, gravity).div(1 + step * drag);
            }
            helper.runAfterDelay(20, () -> {
                if (body.isRemoved()) {
                    helper.fail("Sublevel was removed during gravity interval");
                    return;
                }
                Vector3d velocity = handle.getLinearVelocity(new Vector3d());
                if (!expectedVelocity.equals(velocity, 1e-2)) {
                    helper.fail("Sublevel acceleration didn't follow gravity: " + expectedVelocity.distance(velocity));
                    return;
                }
                Vector3d delta = body.logicalPose().position().sub(startPosition, new Vector3d());
                if (!expectedDelta.equals(delta, 1e-2)) {
                    helper.fail("Sublevel displacement didn't follow gravity: " + expectedDelta.distance(delta));
                    return;
                }
                helper.succeed();
            });
        });
        ci.cancel();
    }
}
