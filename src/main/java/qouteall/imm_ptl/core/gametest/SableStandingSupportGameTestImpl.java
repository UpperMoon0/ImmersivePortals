package qouteall.imm_ptl.core.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.compat.sable.SableStandingPassenger;
import qouteall.imm_ptl.core.compat.sable.SableInterface;
import qouteall.imm_ptl.core.portal.Portal;

final class SableStandingSupportGameTestImpl {
    static void run(GameTestHelper helper) {
        var level=helper.getLevel();
        BlockPos pos=helper.absolutePos(new BlockPos(2,12,2));
        var previous=level.getBlockState(pos);
        level.setBlock(pos,Blocks.OAK_PLANKS.defaultBlockState(),3);
        var body=SubLevelAssemblyHelper.assembleBlocks(level,pos,java.util.List.of(pos),
            new BoundingBox3i(pos.getX(),pos.getY(),pos.getZ(),pos.getX()+1,pos.getY()+1,pos.getZ()+1));
        body.getPlot().updateBoundingBox();body.updateBoundingBox();
        var standing=new ItemEntity(level,pos.getX()+.5,pos.getY()+1.05,pos.getZ()+.5,new ItemStack(Items.DIAMOND));
        var nearby=new ItemEntity(level,pos.getX()+2,pos.getY()+1.05,pos.getZ()+.5,new ItemStack(Items.DIAMOND));
        Portal portal=qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal.ENTITY_TYPE.create(level);
        try {
            standing.move(MoverType.SELF,new Vec3(0,-.2,0));
            helper.assertTrue(SableStandingPassenger.getSupport(standing)==body,"Native collision support was not recognized");
            helper.assertTrue(SableStandingPassenger.getSupport(nearby)==null,"Nearby entity was mistaken for a passenger");
            Portal finite=Portal.ENTITY_TYPE.create(level);
            try {
                finite.setDestinationDimension(net.minecraft.world.level.Level.NETHER);
                helper.assertTrue(!SableInterface.invoker.shouldUseServerFirstStandingTeleport(standing,finite),
                    "Standing on a stationary ship blocked ordinary on-foot portal traversal");
            } finally { finite.discard(); }
            portal.setDestinationDimension(net.minecraft.world.level.Level.NETHER);
            helper.assertTrue(SableInterface.invoker.shouldUseServerFirstStandingTeleport(standing,portal),
                "Standing passenger bypassed coordinated handoff");
            helper.assertTrue(!SableInterface.invoker.shouldUseServerFirstStandingTeleport(nearby,portal),
                "Untracked nearby entity entered ship handoff");
            try {
                var guard = qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat.class.getDeclaredMethod(
                    "isStandingBodyApproachingPortal", dev.ryanhcode.sable.sublevel.ServerSubLevel.class,
                    net.minecraft.world.entity.Entity.class, Portal.class);
                guard.setAccessible(true);
                var handle = dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle.of(
                    (dev.ryanhcode.sable.sublevel.ServerSubLevel)body);
                handle.addLinearAndAngularVelocity(new org.joml.Vector3d(0,-3,0)
                    .sub(handle.getLinearVelocity(new org.joml.Vector3d())),new org.joml.Vector3d());
                portal.setOrientationAndSize(new Vec3(0,0,1),new Vec3(1,0,0),8,8);
                Vec3 eye = qouteall.imm_ptl.core.McHelper.getEyePos(standing);
                portal.setOriginPos(eye.add(0,-1,0));
                helper.assertTrue(!(boolean)guard.invoke(null,body,standing,portal),
                    "Native inward motion accepted a standing eye still in front of the portal");
                portal.setOriginPos(eye.add(0,.01,0));
                helper.assertTrue((boolean)guard.invoke(null,body,standing,portal),
                    "Actual standing eye entry with inward native motion was rejected");
                handle.addLinearAndAngularVelocity(new org.joml.Vector3d(0,6,0),new org.joml.Vector3d());
                helper.assertTrue(!(boolean)guard.invoke(null,body,standing,portal),
                    "Outward standing body accepted a stale request");
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            portal.setScaling(2);
            helper.assertTrue(!SableInterface.invoker.shouldUseServerFirstStandingTeleport(standing,portal),
                "Standing path bypassed unsupported ship scaling");
            SubLevelContainer.getContainer(level).removeSubLevel(body,SubLevelRemovalReason.REMOVED);
            helper.assertTrue(SableStandingPassenger.getSupport(standing)==null,"Retired support remained eligible");
        } finally {
            standing.discard();nearby.discard();portal.discard();
            var container=SubLevelContainer.getContainer(level);
            if(container.getSubLevel(body.getUniqueId())==body)container.removeSubLevel(body,SubLevelRemovalReason.REMOVED);
            level.setBlock(pos,previous,3);
        }
        helper.succeed();
    }
}
