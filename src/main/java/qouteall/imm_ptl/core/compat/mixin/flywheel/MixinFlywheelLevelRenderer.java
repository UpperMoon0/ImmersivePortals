package qouteall.imm_ptl.core.compat.mixin.flywheel;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;
import qouteall.imm_ptl.core.compat.IPFlywheelCompat;

/** Flywheel 1.0.6 owns one context field per renderer, including same-level recursion. */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class MixinFlywheelLevelRenderer {
    @Shadow private ClientLevel level;

    // Accessors resolve after all mixins have merged their fields. A @Shadow
    // resolves during preparation, before Flywheel's field exists in some
    // config/priority orders, and crashes the actual client transformer.
    @Accessor(value = "flywheel$renderContext", remap = false)
    public abstract RenderContextImpl ip_getFlywheelRenderContext();

    @Accessor(value = "flywheel$renderContext", remap = false)
    public abstract void ip_setFlywheelRenderContext(RenderContextImpl context);

    @WrapMethod(method = "renderLevel")
    private void ip_restoreFlywheelRenderContext(
        DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelMatrix, Matrix4f projectionMatrix, Operation<Void> original
    ) {
        RenderContextImpl previous = ip_getFlywheelRenderContext();
        try (var scope = IPFlywheelCompat.enterWorldRender(level)) {
            original.call(deltaTracker, renderBlockOutline, camera, gameRenderer,
                lightTexture, modelMatrix, projectionMatrix);
        } finally {
            ip_setFlywheelRenderContext(previous);
            IPFlywheelCompat.recordContextRestored(previous, ip_getFlywheelRenderContext());
        }
    }
}
