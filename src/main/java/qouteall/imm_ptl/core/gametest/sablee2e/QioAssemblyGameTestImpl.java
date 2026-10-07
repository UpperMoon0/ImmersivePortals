package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import mekanism.common.tile.qio.TileEntityQIODashboard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.ArrayList;
import java.util.List;

/** Preserve real QIO crafting contents across every phase of its ten-tick update cycle. */
final class QioAssemblyGameTestImpl {
    private QioAssemblyGameTestImpl() {}
    public static List<TestFunction> tests() {
        var tests = new ArrayList<TestFunction>();
        for (int phase = 0; phase < 10; phase++) {
            int tickPhase = phase;
            tests.add(new TestFunction("qioAssembly", "qioassembly.phase" + phase,
                "sable:physicstest.gravity", 60, 0, true, helper -> place(helper, tickPhase)));
        }
        return tests;
    }

    private static void place(GameTestHelper helper, int phase) {
        var level = helper.getLevel();
        long delay = Math.floorMod(phase - level.getGameTime() - 1, 10) + 1;
        helper.runAfterDelay(delay, () -> {
            if (Math.floorMod(level.getGameTime(), 10) != phase) {
                helper.fail("QIO fixture did not start at the requested tick phase");
                return;
            }
            BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
            var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("mekanism:qio_dashboard"));
            var state = block.getStateDefinition().getPossibleStates().getFirst();
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(pos, state, 3);
            var tile = (TileEntityQIODashboard) level.getBlockEntity(pos);
            // QIO exposes a read-only automation handler; seed a persistent crafting input
            // through its own inventory API rather than forcing the handler's visual slots.
            ItemStack contents = new ItemStack(Items.OCELOT_SPAWN_EGG);
            tile.getInventorySlots(null).getFirst().setStack(contents.copy());
            helper.runAfterDelay(1, () -> {
                var source = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, (Direction) null);
                if (source == null) {
                    helper.fail("QIO source inventory missing");
                    return;
                }
                var expected = new ArrayList<ItemStack>();
                for (int slot = 0; slot < source.getSlots(); slot++) {
                    expected.add(source.getStackInSlot(slot).copy());
                }
                if (!ItemStack.isSameItemSameComponents(contents, expected.getFirst())
                    || expected.getFirst().getCount() != 1) {
                    helper.fail("QIO crafting input was not loaded before assembly");
                    return;
                }
                var container = SubLevelContainer.getContainer(level);
                var body = SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos, pos.below()),
                    new BoundingBox3i(pos.getX(), pos.getY() - 1, pos.getZ(),
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
                try {
                    var destinationPos = body.getPlot().getCenterBlock();
                    var destination = level.getCapability(Capabilities.ItemHandler.BLOCK,
                        destinationPos, state, level.getBlockEntity(destinationPos), null);
                    if (destination == null) {
                        helper.fail("QIO destination missing phase=" + phase + " state="
                            + level.getBlockState(destinationPos) + " entity=" + level.getBlockEntity(destinationPos));
                        return;
                    }
                    if (destination.getSlots() != expected.size()) {
                        helper.fail("QIO crafting slot count changed during assembly");
                        return;
                    }
                    for (int slot = 0; slot < expected.size(); slot++) {
                        var actual = destination.getStackInSlot(slot);
                        var saved = expected.get(slot);
                        if (!ItemStack.isSameItemSameComponents(saved, actual) || saved.getCount() != actual.getCount()) {
                            helper.fail("QIO crafting inventory changed during assembly at slot " + slot);
                            return;
                        }
                    }
                } finally {
                    container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
                }
                helper.succeed();
            });
        });
    }
}
