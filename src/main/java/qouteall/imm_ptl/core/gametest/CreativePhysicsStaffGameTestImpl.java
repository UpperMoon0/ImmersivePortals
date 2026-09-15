package qouteall.imm_ptl.core.gametest;

import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;

import java.lang.reflect.Method;
import java.util.UUID;

final class CreativePhysicsStaffGameTestImpl {
    static void start(GameTestHelper helper) {
        try {
            new Scenario(helper).start();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Creative Physics Staff API changed", error);
        }
    }

    private static final class Scenario {
        final GameTestHelper helper;
        final ServerLevel lower;
        final ServerLevel upper;
        final ServerSubLevelContainer source;
        final ServerSubLevelContainer target;
        final Object handler;
        final Method drag;
        final Method stop;
        final FakePlayer player;
        UUID bodyId;
        int ticks;
        boolean crossed;
        boolean finished;

        Scenario(GameTestHelper helper) throws ReflectiveOperationException {
            this.helper = helper;
            lower = helper.getLevel().getServer().getLevel(Level.NETHER);
            upper = helper.getLevel().getServer().getLevel(Level.OVERWORLD);
            source = SubLevelContainer.getContainer(lower);
            target = SubLevelContainer.getContainer(upper);
            Class<?> type = Class.forName("dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler");
            handler = type.getMethod("get", ServerLevel.class).invoke(null, lower);
            drag = type.getMethod("drag", UUID.class, UUID.class, Vector3dc.class, Vector3dc.class, Quaterniondc.class);
            stop = type.getMethod("stopDragging", UUID.class);
            player = FakePlayerFactory.get(lower, new GameProfile(UUID.randomUUID(), "StaffPortalTest"));
        }

        void start() throws ReflectiveOperationException {
            VerticalConnectingPortal.connectMutually(Level.OVERWORLD, Level.NETHER, false);
            double ceiling = VerticalConnectingPortal.getConnectingPortal(lower,
                VerticalConnectingPortal.ConnectorType.ceil).getY();
            // Clear the test column so terrain cannot mask the portal/constraint behavior.
            for (int x = 509; x <= 515; x++) for (int z = 509; z <= 515; z++) {
                for (int y = (int) ceiling - 8; y <= (int) ceiling + 8; y++) {
                    lower.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
                for (int y = upper.getMinBuildHeight(); y <= upper.getMinBuildHeight() + 12; y++) {
                    upper.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
            player.setPos(508, ceiling - 4, 512);
            player.xOld = player.getX(); player.yOld = player.getY(); player.zOld = player.getZ();
            player.setNoGravity(true);
            player.getAbilities().flying = true;
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("simulated", "creative_physics_staff"))));
            lower.addNewPlayer(player);
            Pose3d pose = new Pose3d();
            pose.position().set(512, ceiling - 1.5, 512);
            ServerSubLevel body = (ServerSubLevel) source.allocateNewSubLevel(pose);
            bodyId = body.getUniqueId();
            body.getPlot().newEmptyChunk(body.getPlot().getCenterChunk());
            body.getPlot().getEmbeddedLevelAccessor().setBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState(), 3);
            body.updateLastPose(); body.updateBoundingBox();
            source.addForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            Vector3d goal = new Vector3d(512, ceiling + 0.15, 512)
                .sub(player.getX(), player.getEyeY(), player.getZ());
            drag.invoke(handler, player.getUUID(), bodyId, goal,
                new Vector3d(body.getPlot().getCenterBlock().getX() + 0.5,
                    body.getPlot().getCenterBlock().getY() + 0.5,
                    body.getPlot().getCenterBlock().getZ() + 0.5), new Quaterniond());
            helper.onEachTick(this::tick);
        }

        void tick() {
            if (finished) return;
            try {
                ticks++;
                if (ticks > 220) throw new IllegalStateException("staff crossing timed out; reached Overworld=" + crossed);
                ServerSubLevel arrived = (ServerSubLevel) target.getSubLevel(bodyId);
                if (!crossed && arrived != null) {
                    crossed = true;
                    if (source.getSubLevel(bodyId) != null) throw new IllegalStateException("staff left duplicate Nether body");
                    if (RigidBodyHandle.of(arrived).getLinearVelocity(new Vector3d()).y <= 0) {
                        throw new IllegalStateException("staff upward momentum lost at handoff");
                    }
                    // Use the tool's real release path; do not teleport or zero the body.
                    stop.invoke(handler, player.getUUID());
                }
                if (crossed && source.getSubLevel(bodyId) instanceof ServerSubLevel returned) {
                    if (arrived != null) throw new IllegalStateException("staff return left duplicate Overworld body");
                    if (RigidBodyHandle.of(returned).getLinearVelocity(new Vector3d()).y >= 0) {
                        throw new IllegalStateException("staff body did not fall naturally back to Nether");
                    }
                    if (!returned.getPlot().getEmbeddedLevelAccessor().getBlockState(BlockPos.ZERO).is(Blocks.STONE)) {
                        throw new IllegalStateException("staff round trip lost blocks");
                    }
                    cleanup();
                    System.out.println("creativephysicsstaff: actual drag constraint, release and gravity return passed");
                    helper.succeed();
                }
            } catch (Exception error) {
                cleanup();
                throw new IllegalStateException("Creative Physics Staff portal regression", error);
            }
        }

        void cleanup() {
            finished = true;
            try { stop.invoke(handler, player.getUUID()); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            for (ServerSubLevelContainer container : new ServerSubLevelContainer[] {source, target}) {
                if (container.getSubLevel(bodyId) instanceof ServerSubLevel body) {
                    container.removeForceLoadTicket(body, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
                    container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
                }
            }
            lower.removePlayerImmediately(player, Entity.RemovalReason.DISCARDED);
            VerticalConnectingPortal.removeConnectingPortal(VerticalConnectingPortal.ConnectorType.floor, Level.OVERWORLD);
            VerticalConnectingPortal.removeConnectingPortal(VerticalConnectingPortal.ConnectorType.ceil, Level.NETHER);
        }
    }
}
