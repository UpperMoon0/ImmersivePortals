package qouteall.imm_ptl.core.gametest.sablee2e.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.ryanhcode.sable.neoforge.gametest.AssemblyTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.gametest.sablee2e.AssemblyFixtureSequence;

/** Snapshot all sided views at the assembly boundary, after the intervening block tick. */
@Mixin(value = AssemblyTest.class, remap = false)
public abstract class MixinSableAssemblyTest {
    @Inject(method = "testAllBlocks", at = @At("HEAD"))
    private static void ip_createFixtureSequence(GameTestHelper helper, CallbackInfo ci,
        @Share("fixtureSequence") LocalRef<AssemblyFixtureSequence> sequence) {
        sequence.set(new AssemblyFixtureSequence(helper));
    }

    @Redirect(method = "testAllBlocks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/gametest/framework/GameTestHelper;runAtTickTime(JLjava/lang/Runnable;)V"))
    private static void ip_scheduleFixture(GameTestHelper helper, long tick, Runnable task,
        @Share("fixtureSequence") LocalRef<AssemblyFixtureSequence> sequence) {
        sequence.get().schedule(tick, task);
    }

    @Redirect(method = "lambda$testAllBlocks$2", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/gametest/framework/GameTestHelper;runAfterDelay(JLjava/lang/Runnable;)V"))
    private static void ip_delayedAssembly(GameTestHelper helper, long delay, Runnable task) {
        AssemblyFixtureSequence.scheduleAssembly(helper, delay, task);
    }

    @Redirect(method = "lambda$testAllBlocks$2", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/neoforge/items/IItemHandlerModifiable;setStackInSlot(ILnet/minecraft/world/item/ItemStack;)V"))
    private static void ip_insertThroughPublicInventory(IItemHandlerModifiable inventory,
        int slot, ItemStack stack) {
        // setStackInSlot bypasses slot validation, including Mekanism QIO's
        // non-persistent visual connector slots. Seed only accepted contents.
        inventory.insertItem(slot, stack, false);
    }

    @Inject(method = "lambda$testAllBlocks$1", at = @At("HEAD"))
    private static void ip_snapshotFinalInventories(CallbackInfo ci,
        @Local(argsOnly = true) ServerLevel level,
        @Local(argsOnly = true, ordinal = 0) BlockPos pos,
        @Local(argsOnly = true) NonNullList<ItemStack>[] inventories
    ) {
        Direction[] faces = {null, Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST};
        for (int i = 0; i < faces.length; i++) {
            var inventory = level.getCapability(Capabilities.ItemHandler.BLOCK, pos,
                level.getBlockState(pos), level.getBlockEntity(pos), faces[i]);
            if (inventory == null) {
                inventories[i] = null;
                continue;
            }
            var snapshot = NonNullList.withSize(inventory.getSlots(), ItemStack.EMPTY);
            for (int slot = 0; slot < snapshot.size(); slot++) {
                snapshot.set(slot, inventory.getStackInSlot(slot).copy());
            }
            inventories[i] = snapshot;
        }
    }
}
