package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.List;

@GameTestHolder("sable")
public final class AssemblySchedulingGameTest {
    @GameTestGenerator
    public static List<TestFunction> tests() {
        if (!ModList.get().isLoaded("sable")) return List.of();
        return List.of(new TestFunction("assemblyScheduling", "assemblyscheduling.delayedAssertions",
            "sable:assemblytest.allblocks", 30_000_000, 0, true,
            AssemblyFixtureSequence::testDelayedAssembly));
    }
}
