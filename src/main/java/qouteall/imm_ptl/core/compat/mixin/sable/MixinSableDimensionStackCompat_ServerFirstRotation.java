package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.GravityChangerInterface;
import qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstTeleportNetworking;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Correlates rider teleports performed directly by Sable's migration transaction with the
 * portal transform that caused them. Pure physics-first migrations prepare the client before
 * the authoritative entity move and acknowledge only after the whole transaction returns. If
 * migration runs inside a client teleport request, the outer request handler owns the handoff.
 */
@Mixin(value = SableDimensionStackCompat.class, remap = false)
public abstract class MixinSableDimensionStackCompat_ServerFirstRotation {
    @Unique
    private static final ThreadLocal<Portal> ip_currentMigrationPortal = new ThreadLocal<>();

    @Unique
    private static final ThreadLocal<Map<UUID, UUID>> ip_playerHandoffs =
        ThreadLocal.withInitial(HashMap::new);

    @Inject(method = "migrateSubLevel", at = @At("HEAD"))
    private static void ip_beginMigrationTransformContext(
        ServerSubLevelContainer sourceContainer,
        ServerSubLevel sourceSubLevel,
        Portal portal,
        Pose3d previousPhysicsPose,
        CallbackInfoReturnable<ServerSubLevel> cir
    ) {
        ip_currentMigrationPortal.set(portal);
        ip_playerHandoffs.get().clear();
    }

    @WrapOperation(
        method = "transferPlotEntities",
        at = @At(
            value = "INVOKE",
            target = "Lqouteall/imm_ptl/core/teleportation/ServerTeleportationManager;teleportEntityGeneral(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/server/level/ServerLevel;)Lnet/minecraft/world/entity/Entity;"
        )
    )
    private static Entity ip_correlateRiderBeforeAuthoritativeMove(
        Entity entity,
        Vec3 destinationPosition,
        ServerLevel destinationWorld,
        Operation<Entity> original
    ) {
        Portal portal = ip_currentMigrationPortal.get();
        if (portal != null && entity instanceof ServerPlayer player) {
            // Request-first ordering is already inside onPlayerTeleportedInClient. Its handler
            // owns the one correlated Ack after the outer IP path completes.
            UUID activeClientHandoff =
                SableServerFirstTeleportNetworking.getActiveClientRequestHandoffId(portal.getUUID());
            if (activeClientHandoff == null) {
                Map<UUID, UUID> handoffs = ip_playerHandoffs.get();
                UUID playerId = player.getUUID();
                if (!handoffs.containsKey(playerId)) {
                    UUID handoffId = UUID.randomUUID();
                    handoffs.put(playerId, handoffId);
                    // This must precede teleportEntityGeneral. The Prepare, authoritative position
                    // packet, and terminal Ack then describe one ordered server-first transaction.
                    SableServerFirstTeleportNetworking.sendServerInitiatedPrepare(
                        player, handoffId, portal
                    );
                }
            }
        }
        return original.call(entity, destinationPosition, destinationWorld);
    }

    @Inject(method = "migrateSubLevel", at = @At("RETURN"))
    private static void ip_finishMigrationTransformContext(
        ServerSubLevelContainer sourceContainer,
        ServerSubLevel sourceSubLevel,
        Portal portal,
        Pose3d previousPhysicsPose,
        CallbackInfoReturnable<ServerSubLevel> cir
    ) {
        Map<UUID, UUID> handoffs = ip_playerHandoffs.get();
        Portal migrationPortal = ip_currentMigrationPortal.get();
        try {
            if (migrationPortal == null || handoffs.isEmpty()) return;

            boolean success = cir.getReturnValue() != null;
            for (Map.Entry<UUID, UUID> entry : handoffs.entrySet()) {
                ServerPlayer player = sourceContainer.getLevel().getServer()
                    .getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    // Request-first crossings are excluded from this map and use the normal outer
                    // IP server path. Pure physics-first crossings must mirror that path's gravity
                    // change here, once, after the Sable transaction has committed successfully.
                    if (success && migrationPortal.getTeleportChangesGravity()) {
                        Direction oldGravityDir = GravityChangerInterface.invoker.getGravityDirection(player);
                        GravityChangerInterface.invoker.setBaseGravityDirectionServer(
                            player, migrationPortal.getTransformedGravityDirection(oldGravityDir)
                        );
                    }
                    SableServerFirstTeleportNetworking.sendServerInitiatedAck(
                        player, entry.getValue(), migrationPortal, success
                    );
                }
            }
        }
        finally {
            handoffs.clear();
            ip_playerHandoffs.remove();
            ip_currentMigrationPortal.remove();
        }
    }
}
