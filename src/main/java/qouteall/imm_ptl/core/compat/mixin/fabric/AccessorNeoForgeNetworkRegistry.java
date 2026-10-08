package qouteall.imm_ptl.core.compat.mixin.fabric;

import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = NetworkRegistry.class, remap = false)
public interface AccessorNeoForgeNetworkRegistry {
    @Accessor("setup")
    static boolean ip$getSetup() { throw new AssertionError("Mixin accessor"); }

    @Accessor("setup")
    static void ip$setSetup(boolean setup) { throw new AssertionError("Mixin accessor"); }
}
