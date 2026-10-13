package qouteall.imm_ptl.core.gametest;

import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.EntityExtension;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.CollisionInfo;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.List;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.SableCompat;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.ducks.IEEntity;

final class SableCollisionIntegrationGameTestImpl {
    static void collisionWrappersComposeExactlyOnce(GameTestHelper helper) {
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

    /** Real first-move mushroom beside a horizontal portal, matching the ship-run crash. */
    static void firstItemMoveThroughPortalHole(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos ceiling = helper.absolutePos(new BlockPos(2, 6, 2));
        Vec3 source = new Vec3(ceiling.getX() + 0.5, ceiling.getY(), ceiling.getZ() + 0.5);
        Map<BlockPos, BlockState> originalBlocks = new LinkedHashMap<>();
        Portal portal = Portal.ENTITY_TYPE.create(level);
        if (portal == null) throw new IllegalStateException("Portal entity type is unavailable");
        portal.setOriginPos(source);
        portal.setOrientationAndSize(new Vec3(1, 0, 0), new Vec3(0, 0, 1), 4, 4);
        portal.setDestinationDimension(level.dimension());
        portal.setDestination(source.add(0, 12, 0));
        ItemEntity item = mushroom(level, source.add(0, -0.30, 0));
        Vec3 attemptedMove = new Vec3(0, 0.16, 0);
        boolean previousCrossPortalCollision = IPGlobal.crossPortalCollision;
        boolean previousServerCollision = IPGlobal.enableServerCollision;
        try {
            IPGlobal.crossPortalCollision = true;
            IPGlobal.enableServerCollision = true;
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                for (int y : new int[] {-1, 0, 11, 12, 13}) {
                    BlockPos pos = ceiling.offset(x, y, z);
                    originalBlocks.put(pos, level.getBlockState(pos));
                    level.setBlock(pos, y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
            }
            helper.assertTrue(level.addFreshEntity(portal), "Portal must enter the real entity lookup");
            helper.assertTrue(level.addFreshEntity(item), "Fresh mushroom must enter the real entity lookup");
            var movement = (EntityMovementExtension) (Object) item;
            helper.assertTrue(movement.sable$getCollisionInfo() == null, "Fixture must exercise the first-ever movement");
            helper.assertTrue(((EntityExtension) (Object) item).sable$vanillaCollide(attemptedMove).y < 0.10,
                "Without portal clipping, the solid ceiling must obstruct this move");
            ((IEEntity) item).ip_notifyCollidingWithPortal(portal);
            helper.assertTrue(((IEEntity) item).ip_getPortalCollisionHandler().hasCollisionEntry(), "Real portal entry missing");
            Vec3 before = item.position();
            item.setDeltaMovement(attemptedMove);
            item.move(MoverType.SELF, attemptedMove);
            helper.assertTrue(item.position().distanceTo(before.add(attemptedMove)) < 1e-7,
                "Sable composition must preserve the portal hole instead of clipping against the ceiling");
            CollisionInfo first = movement.sable$getCollisionInfo();
            helper.assertTrue(first != null && !item.verticalCollision && !item.horizontalCollision,
                "First portal movement must publish fresh, unobstructed Sable state");

            // Deliberately poison the previous move: a null-only patch would
            // replay this inherited motion and sublevel contact on the next one.
            first.inheritedMotion = new Vec3(3, 0, 0);
            first.subLevelHorizontalCollision = true;
            first.horizontalCollision = true;
            first.verticalCollision = true;
            item.move(MoverType.SELF, attemptedMove);
            helper.assertTrue(movement.sable$getCollisionInfo() != first, "Second portal move reused stale Sable state");
            helper.assertTrue(item.position().distanceTo(before.add(attemptedMove.scale(2))) < 1e-7,
                "Portal move replayed old inherited motion");
            helper.assertTrue(!item.horizontalCollision && !item.verticalCollision, "Portal move reused old collision flags");
        } finally {
            IPGlobal.crossPortalCollision = previousCrossPortalCollision;
            IPGlobal.enableServerCollision = previousServerCollision;
            item.discard();
            portal.discard();
            originalBlocks.forEach((pos, state) -> level.setBlock(pos, state, 3));
        }
        helper.succeed();
    }

    static void bypassedMovementHasFreshState(GameTestHelper helper) {
        Vec3 origin = Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 12, 2)));
        ItemEntity item = mushroom(helper.getLevel(), origin);
        var movement = (EntityMovementExtension) (Object) item;
        boolean previousCrossPortalCollision = IPGlobal.crossPortalCollision;
        boolean previousServerCollision = IPGlobal.enableServerCollision;
        try {
            IPGlobal.crossPortalCollision = true;
            IPGlobal.enableServerCollision = false;
            Vec3 down = new Vec3(0.25, -0.16, 0);
            item.move(MoverType.SELF, down);
            CollisionInfo first = movement.sable$getCollisionInfo();
            helper.assertTrue(first != null && item.position().equals(origin), "Disabled first move must safely stay still");
            helper.assertTrue(item.horizontalCollision && item.verticalCollision && item.verticalCollisionBelow,
                "Disabled move flags must describe the actual zero result");
            first.inheritedMotion = new Vec3(3, 0, 0);
            first.subLevelHorizontalCollision = true;
            IPGlobal.enableServerCollision = true;
            item.move(MoverType.SELF, new Vec3(0, 61, 0));
            CollisionInfo second = movement.sable$getCollisionInfo();
            helper.assertTrue(second != first && item.position().equals(origin), "Extreme move replayed stale ship motion");
            helper.assertTrue(!item.horizontalCollision && item.verticalCollision && !item.verticalCollisionBelow,
                "Extreme move reused the previous collision flags");
            helper.assertTrue(second.inheritedMotion == null && !second.subLevelHorizontalCollision
                && second.firstCollisions == null, "Bypass fabricated native sublevel collision state");
            // Re-enable ordinary collision, then zero movement, to ensure the
            // exceptional paths do not poison the next native redirect.
            item.move(MoverType.SELF, new Vec3(0.25, 0, 0));
            helper.assertTrue(item.position().distanceTo(origin.add(0.25, 0, 0)) < 1e-7,
                "Ordinary movement did not recover after bypass");
            CollisionInfo third = movement.sable$getCollisionInfo();
            item.move(MoverType.SELF, Vec3.ZERO);
            helper.assertTrue(movement.sable$getCollisionInfo() != third, "Stationary move must still initialize native state");
        } finally {
            IPGlobal.crossPortalCollision = previousCrossPortalCollision;
            IPGlobal.enableServerCollision = previousServerCollision;
            item.discard();
        }
        helper.succeed();
    }

    static void recursiveTerrainQueriesAreNotPortalMoves(GameTestHelper helper) {
        ItemEntity item = mushroom(helper.getLevel(), Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 12, 2))));
        int[] calls = {0};
        boolean[] fail = {false};
        PortalCollisionHandler handler = new PortalCollisionHandler() {
            @Override public boolean hasCollisionEntry() { return true; }
            @Override public Vec3 handleCollision(Entity entity, Vec3 move) {
                calls[0]++;
                if (calls[0] > 2) throw new IllegalStateException("Recursive portal collision hook");
                ((EntityExtension) entity).sable$vanillaCollide(move);
                if (fail[0]) throw new IllegalArgumentException("expected terrain failure");
                return move;
            }
        };
        ((IEEntity) item).ip_setPortalCollisionHandler(handler);
        boolean previousCrossPortalCollision = IPGlobal.crossPortalCollision;
        boolean previousServerCollision = IPGlobal.enableServerCollision;
        try {
            IPGlobal.crossPortalCollision = true;
            IPGlobal.enableServerCollision = true;
            item.move(MoverType.SELF, new Vec3(0.1, 0, 0));
            helper.assertTrue(calls[0] == 1, "A nested terrain query must not re-enter IP collision");
            fail[0] = true;
            boolean threw = false;
            try { item.move(MoverType.SELF, new Vec3(0.1, 0, 0)); }
            catch (IllegalArgumentException expected) { threw = expected.getMessage().equals("expected terrain failure"); }
            helper.assertTrue(threw, "Expected terrain error must propagate");
            ((EntityExtension) (Object) item).sable$vanillaCollide(new Vec3(0.1, 0, 0));
            helper.assertTrue(calls[0] == 2, "Exception leaked the move scope into an unrelated terrain query");
        } finally {
            IPGlobal.crossPortalCollision = previousCrossPortalCollision;
            IPGlobal.enableServerCollision = previousServerCollision;
            item.discard();
        }
        helper.succeed();
    }

    static void nestedMoveDoesNotPublishOuterCollision(GameTestHelper helper) {
        int[] calls = {0};
        CollisionInfo[] outer = {null};
        CollisionInfo[] nested = {null};
        // getDeltaMovement is read by Sable before calling its native solver.
        // Re-enter there, then make a terrain query using the inner record's
        // exact motion object: identity gating alone must not accept it.
        class ReentrantEntity extends CountingEntity {
            boolean enter;
            ReentrantEntity() { super(helper.getLevel()); }
            @Override public Vec3 getDeltaMovement() {
                if (enter) {
                    enter = false;
                    move(MoverType.SELF, new Vec3(0.10, 0, 0));
                    var innerInfo = ((EntityMovementExtension) (Object) this).sable$getCollisionInfo();
                    ((EntityExtension) (Object) this).sable$vanillaCollide(innerInfo.motion);
                    helper.assertTrue(calls[0] == 1, "Inner publication armed the unfinished outer solve");
                }
                return super.getDeltaMovement();
            }
        }
        ReentrantEntity entity = new ReentrantEntity();
        entity.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 12, 2))));
        PortalCollisionHandler handler = new PortalCollisionHandler() {
            boolean reentered;
            @Override public boolean hasCollisionEntry() { return true; }
            @Override public Vec3 handleCollision(Entity moved, Vec3 move) {
                calls[0]++;
                if (calls[0] == 2 && !reentered) {
                    reentered = true;
                    outer[0] = ((EntityMovementExtension) moved).sable$getCollisionInfo();
                    moved.move(MoverType.SELF, new Vec3(0, 0, 0.10));
                    nested[0] = ((EntityMovementExtension) moved).sable$getCollisionInfo();
                    helper.assertTrue(nested[0] != outer[0], "Nested move must publish a distinct native result");
                    nested[0].inheritedMotion = new Vec3(3, 0, 0);
                    nested[0].subLevelHorizontalCollision = true;
                    nested[0].horizontalCollision = true;
                }
                return move;
            }
        };
        ((IEEntity) entity).ip_setPortalCollisionHandler(handler);
        boolean previousCrossPortalCollision = IPGlobal.crossPortalCollision;
        boolean previousServerCollision = IPGlobal.enableServerCollision;
        try {
            IPGlobal.crossPortalCollision = true;
            IPGlobal.enableServerCollision = true;
            Vec3 before = entity.position();
            entity.enter = true;
            entity.move(MoverType.SELF, new Vec3(0.20, 0, 0));
            helper.assertTrue(calls[0] == 3, "Each of the outer and two nested moves must run the portal hook once");
            helper.assertTrue(((EntityMovementExtension) (Object) entity).sable$getCollisionInfo() == outer[0],
                "Outer native redirect consumed the nested terrain callback's record");
            helper.assertTrue(!entity.horizontalCollision, "Outer move inherited nested collision flags");
            helper.assertTrue(entity.position().distanceTo(before.add(0.30, 0, 0.10)) < 1e-7,
                "Outer move replayed nested inherited motion");
        } finally {
            IPGlobal.crossPortalCollision = previousCrossPortalCollision;
            IPGlobal.enableServerCollision = previousServerCollision;
            entity.discard();
        }
        helper.succeed();
    }

    static void nativeShipContactSurvivesPortalCollision(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos block = helper.absolutePos(new BlockPos(2, 12, 2));
        BlockState previousBlock = level.getBlockState(block);
        level.setBlock(block, Blocks.STONE.defaultBlockState(), 3);
        var body = SubLevelAssemblyHelper.assembleBlocks(level, block, List.of(block),
            new BoundingBox3i(block.getX(), block.getY(), block.getZ(),
                block.getX() + 1, block.getY() + 1, block.getZ() + 1));
        // Assembly has not ticked yet. Publish both plot and world bounds so
        // the real native broad-phase query sees the fresh supporting body.
        body.getPlot().updateBoundingBox();
        body.updateBoundingBox();
        ItemEntity item = mushroom(level, Vec3.atCenterOf(block).add(0, 0.55, 0));
        Portal portal = Portal.ENTITY_TYPE.create(level);
        if (portal == null) throw new IllegalStateException("Portal entity type is unavailable");
        portal.setOriginPos(item.position());
        portal.setOrientationAndSize(new Vec3(0, 0, 1), new Vec3(0, 1, 0), 4, 4);
        portal.setDestinationDimension(level.dimension());
        portal.setDestination(item.position().add(0, 8, 0));
        boolean previousCrossPortalCollision = IPGlobal.crossPortalCollision;
        boolean previousServerCollision = IPGlobal.enableServerCollision;
        try {
            IPGlobal.crossPortalCollision = true;
            IPGlobal.enableServerCollision = true;
            level.addFreshEntity(portal);
            ((IEEntity) item).ip_notifyCollidingWithPortal(portal);
            Vec3 before = item.position();
            item.move(MoverType.SELF, new Vec3(0, -0.20, 0));
            var movement = (EntityMovementExtension) (Object) item;
            helper.assertTrue(item.getY() > before.y - 0.10, "Portal path skipped the real Sable deck collision");
            helper.assertTrue(item.verticalCollisionBelow && movement.sable$getCollisionInfo().verticalCollisionBelow,
                "Portal composition lost the native supporting contact");
            helper.assertTrue(movement.sable$getTrackingSubLevel() == body, "Portal composition lost native ship tracking");
        } finally {
            IPGlobal.crossPortalCollision = previousCrossPortalCollision;
            IPGlobal.enableServerCollision = previousServerCollision;
            item.discard();
            portal.discard();
            var container = SubLevelContainer.getContainer(level);
            if (container.getSubLevel(body.getUniqueId()) == body) container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            level.setBlock(block, previousBlock, 3);
        }
        helper.succeed();
    }

    static void guardedLivingMovementHasSafeState(GameTestHelper helper) {
        var level = helper.getLevel();
        Vec3 origin = Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 12, 2)));
        ArmorStand living = new ArmorStand(level, origin.x, origin.y, origin.z);
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "CollisionGuardTest"));
        player.setPos(origin);
        boolean previousServerCollision = IPGlobal.enableServerCollision;
        try {
            IPGlobal.enableServerCollision = false;
            living.setDeltaMovement(0.25, -0.16, 0);
            living.travel(Vec3.ZERO); // Includes Sable's real LivingEntity travel-return consumer.
            helper.assertTrue(living.position().distanceTo(origin) < 1e-7, "Disabled living movement must stay still");
            CollisionInfo livingInfo = ((EntityMovementExtension) (Object) living).sable$getCollisionInfo();
            helper.assertTrue(livingInfo != null && livingInfo.inheritedMotion == null && livingInfo.firstCollisions == null,
                "Guarded living movement must have legal, fresh no-contact state");
            player.move(MoverType.SELF, new Vec3(0.25, 0, 0));
            helper.assertTrue(player.position().distanceTo(origin.add(0.25, 0, 0)) < 1e-7,
                "Disabled server collision must preserve unrestricted player movement");
            CollisionInfo playerInfo = ((EntityMovementExtension) player).sable$getCollisionInfo();
            helper.assertTrue(playerInfo != null && !player.horizontalCollision && !player.verticalCollision,
                "Disabled player collision flags must describe the unrestricted result");
            player.travel(Vec3.ZERO); // Exercise the null-tolerant Sable living consumer too.
            IPGlobal.enableServerCollision = true;
            Vec3 before = living.position();
            livingInfo.inheritedMotion = new Vec3(3, 0, 0);
            living.setDeltaMovement(61, 0, 0);
            living.travel(Vec3.ZERO);
            helper.assertTrue(living.position().distanceTo(before) < 1e-7, "Extreme living move replayed inherited motion");
        } finally {
            IPGlobal.enableServerCollision = previousServerCollision;
            living.discard();
            player.discard();
        }
        helper.succeed();
    }

    private static ItemEntity mushroom(Level level, Vec3 position) {
        ItemEntity item = new ItemEntity(level, position.x, position.y, position.z, new ItemStack(Items.BROWN_MUSHROOM));
        item.setDeltaMovement(Vec3.ZERO);
        return item;
    }

    private static final class CountingPortalCollisionHandler extends PortalCollisionHandler {
        int collisionEntryChecks;

        @Override
        public boolean hasCollisionEntry() {
            this.collisionEntryChecks++;
            return false;
        }
    }

    private static class CountingEntity extends Entity {
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
