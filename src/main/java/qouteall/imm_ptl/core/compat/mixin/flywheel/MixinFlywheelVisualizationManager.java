package qouteall.imm_ptl.core.compat.mixin.flywheel;

import net.minecraft.world.level.LevelAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.systems.RenderSystem;
import qouteall.imm_ptl.core.compat.flywheel.FlywheelRenderScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.IPFlywheelCompat;

/** Both Flywheel and Create consult this before drawing or skipping vanilla geometry. */
@Mixin(targets = "dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl", remap = false)
public abstract class MixinFlywheelVisualizationManager {
    @Inject(method = "supportsVisualization", at = @At("HEAD"), cancellable = true)
    private static void ip_useVanillaInAlternateViews(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
        if (IPFlywheelCompat.useVanillaRenderer()) {
            IPFlywheelCompat.recordPortalFallback();
            cir.setReturnValue(false);
        }
    }

    /**
     * Chunk compilation can register a main-world BE while IP has temporarily
     * switched Minecraft.level for a remote view. Use the unswitched player's
     * level only on workers; leave Flywheel's backend and special-level checks
     * intact, and preserve the current view's identity on the render thread.
     */
    @Redirect(method = "supportsVisualization", at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private static ClientLevel ip_stableLevelForWorkerRegistration(Minecraft client) {
        var player = client.player;
        ClientLevel playerLevel = player != null && player.level() instanceof ClientLevel level ? level : null;
        return FlywheelRenderScope.visualizationLevel(RenderSystem.isOnRenderThread(), client.level, playerLevel);
    }

}
