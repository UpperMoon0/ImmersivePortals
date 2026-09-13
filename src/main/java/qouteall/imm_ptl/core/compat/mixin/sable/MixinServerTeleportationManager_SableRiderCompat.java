package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

/**
 * A player riding a Sable-retained vehicle can cross the portal with their eye/seat before the
 * rigid body's center crosses. Move the owning sublevel first so destination Sable state is on
 * the wire before the player's dimension-change packet.
 */
@Mixin(value = ServerTeleportationManager.class, remap = false)
public abstract class MixinServerTeleportationManager_SableRiderCompat {
    @WrapOperation(
        method = "onPlayerTeleportedInClient",
        at = @At(
            value = "INVOKE",
            target = "Lqouteall/imm_ptl/core/teleportation/ServerTeleportationManager;teleportPlayer(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/phys/Vec3;)V"
        )
    )
    private void ip_moveRiddenSableBeforePlayer(
        ServerTeleportationManager manager,
        ServerPlayer player,
        ResourceKey<Level> destination,
        Vec3 newEyePos,
        Operation<Void> original,
        @Local Portal portal
    ) {
        SableDimensionStackCompat.beforePlayerPortalTeleport(player, portal);
        original.call(manager, player, destination, newEyePos);
    }
}
