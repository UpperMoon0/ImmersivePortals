package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Keep native single-precision physics fixtures near the origin. */
@Mixin(GameTestServer.class)
public abstract class MixinGameTestServer {
    @ModifyVariable(method = "startTests", at = @At("STORE"), ordinal = 0)
    private BlockPos ip_testOrigin(BlockPos randomizedOrigin) {
        return new BlockPos(0, -59, 0);
    }
}
