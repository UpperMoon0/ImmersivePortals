package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.List;

/**
 * Discovery boundary for QIO assembly tests. NeoForge reflects holders before
 * invoking generators, so even private methods and lambda bodies must be free
 * of optional Sable/Mekanism types. Keep all typed code in the implementation.
 */
@GameTestHolder("sable")
public final class QioAssemblyGameTest {
    private QioAssemblyGameTest() {}

    @GameTestGenerator
    public static List<TestFunction> tests() {
        if (!ModList.get().isLoaded("sable") || !ModList.get().isLoaded("mekanism")) {
            return List.of();
        }
        return QioAssemblyGameTestImpl.tests();
    }
}
