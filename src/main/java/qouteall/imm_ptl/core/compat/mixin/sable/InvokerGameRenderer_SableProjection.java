package qouteall.imm_ptl.core.compat.mixin.sable;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read-only access to the renderer's effective FOV, including dynamic/NeoForge modifiers. */
@Mixin(GameRenderer.class)
public interface InvokerGameRenderer_SableProjection {
    @Invoker("getFov") double ip_sableEffectiveFov(Camera camera,float partialTick,boolean useFovSetting);
}
