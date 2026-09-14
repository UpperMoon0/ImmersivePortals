package qouteall.imm_ptl.core.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Sable-free holder; the typed implementation is loaded only when Sable is present. */
@GameTestHolder("sable")
public final class SableDimensionStackMovementGameTest {
    private SableDimensionStackMovementGameTest() {}

    @PrefixGameTestTemplate(false)
    @GameTest(template = "physicstest.gravity", timeoutTicks = 40)
    public static void externalMovementAndImmediateReverse(GameTestHelper helper) {
        if (!ModList.get().isLoaded("sable")) {
            helper.fail("Sable is required for this GameTest");
            return;
        }
        SableDimensionStackMovementGameTestImpl.externalMovementAndImmediateReverse(helper);
    }
}
