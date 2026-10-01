package qouteall.imm_ptl.core.gametest;

import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.List;

/** A loaded drain must transfer its item exactly once when Sable assembles it. */
@GameTestHolder("sable")
public final class SableItemDrainGameTest {
    @GameTestGenerator
    public static List<TestFunction> tests() {
        if (!ModList.get().isLoaded("sable") || !ModList.get().isLoaded("create")) return List.of();
        return List.of(new TestFunction("itemDrain", "sableitemdrain.preservesItemWithoutDrops",
            "sable:physicstest.gravity", 20, 0, true, SableItemDrainGameTestImpl::assembleLoadedDrain));
    }
}
