package qouteall.imm_ptl.core.compat.mixin.sable;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstClientHandoff;
import qouteall.imm_ptl.core.compat.sable.SableServerFirstTeleportNetworking;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.imm_ptl.core.teleportation.TeleportationUtil;

import java.util.UUID;

/** Replaces the ambiguous normal teleport packet with an explicit server-first Sable handshake. */
@Mixin(value = ClientTeleportationManager.class, remap = false)
public abstract class MixinClientTeleportationManager_SableServerFirstAck {
    @Shadow
    private static UUID pendingServerFirstPortalId;

    @Shadow
    private static long lastTeleportGameTime;

    @Inject(
        method = "requestServerFirstTeleport",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void ip_useExplicitServerFirstAcknowledgement(
        TeleportationUtil.Teleportation teleportation,
        CallbackInfo ci
    ) {
        LocalPlayer player = ClientTeleportationManager.client.player;
        Validate.isTrue(player != null);

        Portal portal = teleportation.portal();
        ResourceKey<Level> sourceDimension = player.level().dimension();
        Vec3 eyePos = McHelper.getEyePos(player);

        pendingServerFirstPortalId = portal.getUUID();
        lastTeleportGameTime = ClientTeleportationManager.tickTimeForTeleportation;
        SableServerFirstClientHandoff.begin(portal);

        player.connection.send(new ServerboundCustomPayloadPacket(
            new SableServerFirstTeleportNetworking.Request(
                PortalAPI.clientDimKeyToInt(sourceDimension),
                eyePos,
                portal.getUUID()
            )
        ));
        ci.cancel();
    }
}
