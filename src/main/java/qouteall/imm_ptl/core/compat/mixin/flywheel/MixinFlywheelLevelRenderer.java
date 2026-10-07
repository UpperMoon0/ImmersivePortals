package qouteall.imm_ptl.core.compat.mixin.flywheel;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.compat.IPFlywheelCompat;

/** Flywheel 1.0.6 owns one context field per renderer, including same-level recursion. */
@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class MixinFlywheelLevelRenderer {
    @Dynamic("Added by Flywheel's priority-1001 LevelRendererMixin")
    @Shadow(remap = false)
    private RenderContextImpl flywheel$renderContext;

    @WrapMethod(method = "renderLevel")
    private void ip_restoreFlywheelRenderContext(
        DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
        LightTexture lightTexture, Matrix4f modelMatrix, Matrix4f projectionMatrix, Operation<Void> original
    ) {
        RenderContextImpl previous = flywheel$renderContext;
        try {
            original.call(deltaTracker, renderBlockOutline, camera, gameRenderer,
                lightTexture, modelMatrix, projectionMatrix);
        } finally {
            flywheel$renderContext = previous;
            if (previous != null) IPFlywheelCompat.recordNestedContextRestored();
        }
    }
}
