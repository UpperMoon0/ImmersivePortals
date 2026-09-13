package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable.SableDimensionStackCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

import java.util.UUID;

/**
 * A player riding a Sable-retained vehicle can cross with their eye/seat before the rigid-body
 * COM. Stage and commit the owning Sable dependency chain before IP changes the player's world.
 * If that transaction cannot be prepared (for example a legacy hidden-plot collision), cancel
 * this teleport and correct the client instead of separating rider and body across dimensions.
 */
@Mixin(value = ServerTeleportationManager.class, remap = false)
public abstract class MixinServerTeleportationManager_SableRiderCompat {
    @Inject(
        method = "onPlayerTeleportedInClient",
        at = @At(
            value = "INVOKE",
            target = "Lqouteall/imm_ptl/core/teleportation/ServerTeleportationManager;teleportPlayer(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/phys/Vec3;)V"
        ),
        cancellable = true
    )
    private void ip_prepareRiddenSableBeforePlayer(
        ServerPlayer player,
        ResourceKey<Level> dimensionBefore,
        Vec3 eyePosBeforeTeleportation,
        UUID portalId,
        CallbackInfo ci,
        @Local Portal portal
    ) {
        if (SableDimensionStackCompat.beforePlayerPortalTeleport(player, portal)) return;

        ServerTeleportationManager manager = (ServerTeleportationManager) (Object) this;
        manager.forceTeleportPlayer(player, dimensionBefore, player.position(), true);
        ci.cancel();
    }
}
