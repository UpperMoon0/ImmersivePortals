package qouteall.imm_ptl.core.compat.mixin.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SableDimensionStackCompatContractTest {
    @Test
    void sablePhysicsTickStillProvidesSafePostStepHook() throws Exception {
        ClassNode physics = readClass(
            "dev/ryanhcode/sable/sublevel/system/SubLevelPhysicsSystem.class"
        );
        MethodNode tick = findMethod(
            physics,
            "tick",
            "(Ldev/ryanhcode/sable/api/sublevel/SubLevelContainer;)V"
        );
        assertNotNull(tick, "Sable physics tick signature changed");
        assertTrue(invokesNamed(tick, "updateLastPose"),
            "dimension-stack crossing depends on Sable snapshotting lastPose before physics");
        assertTrue(invokesNamed(tick, "tickPipelinePhysics"),
            "dimension-stack migration must run after Sable advances the physics pipeline");
    }

    @Test
    void sableSerializerSupportsCrossLevelReconstruction() throws Exception {
        assertNotNull(SubLevelSerializer.class.getMethod(
            "toData", ServerSubLevel.class, List.class
        ));
        assertNotNull(SubLevelSerializer.class.getMethod(
            "fullyLoad", net.minecraft.server.level.ServerLevel.class, SubLevelData.class
        ));
        assertNotNull(SubLevelContainer.class.getMethod(
            "getContainer", Level.class
        ));
        assertNotNull(ServerSubLevelContainer.class.getMethod("getOccupancy"));
    }

    @Test
    void ridingPassengersRemainPartOfDimensionStackMigration() throws Exception {
        ClassNode sableRidingMixin = readClass(
            "dev/ryanhcode/sable/mixin/entity/entity_rotations_and_riding/EntityMixin.class"
        );
        MethodNode ridingTick = sableRidingMixin.methods.stream()
            .filter(method -> method.name.equals("sable$onRidingTick"))
            .findFirst()
            .orElse(null);
        assertNotNull(ridingTick, "Sable riding mixin changed");
        assertTrue(invokesNamed(ridingTick, "kickRidingEntity"),
            "Sable riders may live in logical world space instead of the hidden plot");

        ClassNode compat = readClass(
            "qouteall/imm_ptl/core/compat/sable/SableDimensionStackCompat.class"
        );
        MethodNode capture = compat.methods.stream()
            .filter(method -> method.name.equals("capturePlotEntities"))
            .findFirst()
            .orElse(null);
        assertNotNull(capture, "dimension-stack entity capture is missing");
        assertTrue(invokesNamed(capture, "getPassengers"),
            "dimension-stack migration must follow the full riding graph");
        assertTrue(invokesNamed(capture, "kickRidingEntity"),
            "plot-space riders must be converted to logical world space before portal transfer");
    }

    @Test
    void dimensionStackMixinIsPackagedAndEnabled() throws Exception {
        assertNotNull(getClass().getClassLoader().getResource(
            "qouteall/imm_ptl/core/compat/mixin/sable/MixinSubLevelPhysicsSystem_SableDimensionStackCompat.class"
        ));
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
            "imm_ptl_compat.mixins.json"
        )) {
            assertNotNull(stream, "Compatibility mixin config is missing");
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(json.contains("sable.MixinSubLevelPhysicsSystem_SableDimensionStackCompat"));
        }
    }

    private static ClassNode readClass(String resource) throws Exception {
        try (InputStream stream = SableDimensionStackCompatContractTest.class
            .getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(stream, "Sable is missing from the test runtime");
            ClassNode node = new ClassNode();
            new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
            .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst()
            .orElse(null);
    }

    private static boolean invokesNamed(MethodNode method, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
