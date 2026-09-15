package qouteall.imm_ptl.core.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Sable-free holder; the typed collision implementation is loaded only with Sable. */
@GameTestHolder("sable")
public final class SableCollisionIntegrationGameTest {
    private SableCollisionIntegrationGameTest() {}

    @PrefixGameTestTemplate(false)
    @GameTest(template = "physicstest.gravity", timeoutTicks = 20)
    public static void collisionWrappersComposeExactlyOnce(GameTestHelper helper) {
        if (!ModList.get().isLoaded("sable")) {
            helper.fail("Sable is required for this GameTest");
            return;
        }
        SableCollisionIntegrationGameTestImpl.collisionWrappersComposeExactlyOnce(helper);
    }
}
