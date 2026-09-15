package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the portal handoff seed a destination client copy before moving its rider/player. */
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public interface InvokerSubLevelTrackingSystem_SablePortalCompat {
    @Invoker("sendFullSync")
    void ip_sendFullSync(
        ServerPlayer player,
        ServerSubLevel subLevel,
        @Nullable CustomPacketPayload extraPacket
    );
}
