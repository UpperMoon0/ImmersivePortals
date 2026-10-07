package qouteall.imm_ptl.core.mixin.client.sync;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handlers.ClientPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Complex spawn data must follow the same world as the preceding vanilla spawn. */
@Mixin(value = ClientPayloadHandler.class, remap = false)
public abstract class MixinNeoForgeClientPayloadHandler {
    @Redirect(
        method = "handle(Lnet/neoforged/neoforge/network/payload/AdvancedAddEntityPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntity(I)Lnet/minecraft/world/entity/Entity;")
    )
    private static Entity ip_findSpawnedEntity(
        Level playerWorld, int entityId, AdvancedAddEntityPayload payload, IPayloadContext context
    ) {
        // withSwitchedWorld redirects ClientPacketListener.level, while the player
        // deliberately stays in their real dimension. NeoForge's player-world lookup
        // therefore misses remote entities and silently drops their complex spawn data.
        return ((ClientPacketListener) context.listener()).getLevel().getEntity(entityId);
    }
}
