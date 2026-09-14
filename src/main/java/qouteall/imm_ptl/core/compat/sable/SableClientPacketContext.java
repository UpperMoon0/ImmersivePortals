package qouteall.imm_ptl.core.compat.sable;

import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.network.PacketRedirectionClient;

/** Resolves Veil packet contexts against Immersive Portals' redirected client world. */
public final class SableClientPacketContext {
    private SableClientPacketContext() {}

    public static Level resolve(PacketContext context) {
        ResourceKey<Level> redirectedDimension = PacketRedirectionClient.clientTaskRedirection.get();
        if (redirectedDimension != null) {
            ClientLevel redirectedWorld = ClientWorldLoader.getOptionalWorld(redirectedDimension);
            if (redirectedWorld != null) {
                return redirectedWorld;
            }
        }
        return context.level();
    }
}
