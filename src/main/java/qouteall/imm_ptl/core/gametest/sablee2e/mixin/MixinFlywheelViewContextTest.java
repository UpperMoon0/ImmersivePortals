package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.flywheel.FlywheelRenderScope;
import qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeRenderContextWitness;

/** The Iris portal callback happens after LevelRenderer returns but inside this whole view. */
@Mixin(GameRenderer.class)
public abstract class MixinFlywheelViewContextTest {
    @WrapMethod(method = "renderLevel")
    private void ip_verifyWholeViewContext(DeltaTracker deltaTracker, Operation<Void> original) {
        Object renderer = Minecraft.getInstance().levelRenderer;
        try (var view = PortalSmokeRenderContextWitness.LIVE.enter(() -> Minecraft.getInstance().levelRenderer,
            () -> PortalSmokeRenderContextWitness.readContext(renderer), FlywheelRenderScope::currentScope)) {
            original.call(deltaTracker);
        }
    }
}
