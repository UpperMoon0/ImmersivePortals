package qouteall.imm_ptl.core.gametest;

import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.List;

/** Sable-free holder for the optional Creative Physics Staff integration test. */
@GameTestHolder("sable")
public final class CreativePhysicsStaffGameTest {
    private CreativePhysicsStaffGameTest() {}

    @GameTestGenerator
    public static List<TestFunction> tests() {
        if (!ModList.get().isLoaded("sable") || !ModList.get().isLoaded("simulated")) {
            return List.of();
        }
        return List.of(new TestFunction(
            "staff", "creativephysicsstaff", "sable:physicstest.gravity", 240, 0, true,
            CreativePhysicsStaffGameTestImpl::start
        ));
    }
}
