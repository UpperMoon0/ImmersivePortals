package qouteall.imm_ptl.core.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.List;

final class SableItemDrainGameTestImpl {
    static void assembleLoadedDrain(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(2, 3, 2));
        var drain = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("create:item_drain"));
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(pos, drain.defaultBlockState(), 3);
        var source = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.NORTH);
        ItemStack contents = new ItemStack(Items.OCELOT_SPAWN_EGG);
        if (source == null || !source.insertItem(0, contents.copy(), false).isEmpty()) {
            helper.fail("Could not fill item drain");
            return;
        }
        var container = SubLevelContainer.getContainer(level);
        var body = SubLevelAssemblyHelper.assembleBlocks(level, pos, List.of(pos, pos.below()),
            new BoundingBox3i(pos.getX(), pos.getY() - 1, pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1));
        try {
            var destination = level.getCapability(Capabilities.ItemHandler.BLOCK,
                body.getPlot().getCenterBlock(), Direction.NORTH);
            if (destination == null || destination.getStackInSlot(0).getCount() != 1
                || !ItemStack.isSameItemSameComponents(contents, destination.getStackInSlot(0))) {
                helper.fail("Assembled drain lost its held item");
                return;
            }
            if (!level.getEntitiesOfClass(ItemEntity.class, helper.getBounds()).isEmpty()) {
                helper.fail("Assembling the drain duplicated its held item into a drop");
                return;
            }
            if (!level.getBlockState(pos).isAir()) {
                helper.fail("Assembly left the source drain in place");
                return;
            }
        } finally {
            if (container.getSubLevel(body.getUniqueId()) == body) {
                container.removeSubLevel(body, SubLevelRemovalReason.REMOVED);
            }
        }
        helper.succeed();
    }
}
