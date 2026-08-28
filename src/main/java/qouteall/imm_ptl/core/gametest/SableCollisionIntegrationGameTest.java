package qouteall.imm_ptl.core.gametest;

import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.SableCompat;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.ducks.IEEntity;

/** Runtime verification of the final Entity transformation with Sable 2.0.5. */
@GameTestHolder("sable")
public final class SableCollisionIntegrationGameTest {
    private SableCollisionIntegrationGameTest() {}

    @PrefixGameTestTemplate(false)
    @GameTest(template = "physicstest.gravity", timeoutTicks = 20)
    public static void collisionWrappersComposeExactlyOnce(GameTestHelper helper) {
        if (!SableCompat.ACTIVE) {
            helper.fail("Sable compatibility mixins were not activated");
            return;
        }

        CountingEntity entity = new CountingEntity(helper.getLevel());
        entity.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 2, 2))));
        double initialX = entity.getX();
        CountingPortalCollisionHandler handler = new CountingPortalCollisionHandler();
        ((IEEntity) (Object) entity).ip_setPortalCollisionHandler(handler);

        boolean previousCrossPortalCollision = IPGlobal.crossPortalCollision;
        boolean previousServerCollision = IPGlobal.enableServerCollision;
        try {
            IPGlobal.crossPortalCollision = true;
            IPGlobal.enableServerCollision = true;
            entity.move(MoverType.SELF, new Vec3(0.25, 0.0, 0.0));
        } finally {
            IPGlobal.crossPortalCollision = previousCrossPortalCollision;
            IPGlobal.enableServerCollision = previousServerCollision;
        }

        if (handler.collisionEntryChecks != 1) {
            helper.fail("IP collision hook count was " + handler.collisionEntryChecks + ", expected 1");
            return;
        }
        if (Math.abs(entity.getX() - initialX - 0.25) > 1.0e-9) {
            helper.fail("Vanilla collision path did not apply the expected movement");
            return;
        }
        if (((EntityMovementExtension) (Object) entity).sable$getCollisionInfo() == null) {
            helper.fail("Sable collision redirect did not populate collision state");
            return;
        }
        helper.succeed();
    }

    private static final class CountingPortalCollisionHandler extends PortalCollisionHandler {
        int collisionEntryChecks;

        @Override
        public boolean hasCollisionEntry() {
            this.collisionEntryChecks++;
            return false;
        }
    }

    private static final class CountingEntity extends Entity {
        CountingEntity(Level level) {
            super(EntityType.ARMOR_STAND, level);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {}

        @Override
        protected void readAdditionalSaveData(CompoundTag tag) {}

        @Override
        protected void addAdditionalSaveData(CompoundTag tag) {}
    }
}
