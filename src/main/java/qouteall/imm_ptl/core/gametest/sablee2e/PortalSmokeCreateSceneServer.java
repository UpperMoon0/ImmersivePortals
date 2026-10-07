package qouteall.imm_ptl.core.gametest.sablee2e;

import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Two 16-RPM kinetic assemblies, placed facing +Z. Allow normal server ticks to assemble. */
final class PortalSmokeCreateSceneServer {
    private PortalSmokeCreateSceneServer() {}

    static void setup(ServerLevel level, int x, int y, int z) {
        BlockPos motor = new BlockPos(x, y, z);
        level.setBlockAndUpdate(motor, state("creative_motor")
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
        level.setBlockAndUpdate(motor.south(), state("shaft")
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        level.setBlockAndUpdate(motor.south(2), state("large_cogwheel")
            .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));

        // The slime carries contrasting blocks. Rotation about Z produces visible
        // motion even for a camera looking straight at the bearing face.
        BlockPos bearingMotor = motor.east(4);
        level.setBlockAndUpdate(bearingMotor.south(2), Blocks.SLIME_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(bearingMotor.south(2).above(), Blocks.GOLD_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(bearingMotor.south(2).below(), Blocks.BLUE_CONCRETE.defaultBlockState());
        level.setBlockAndUpdate(bearingMotor.south(), state("mechanical_bearing")
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
        // Power last, so the automatic bearing assembly sees the complete rotor.
        level.setBlockAndUpdate(bearingMotor, state("creative_motor")
            .setValue(BlockStateProperties.FACING, Direction.SOUTH));
    }

    static void setSpeed(ServerLevel level, int x, int y, int z, int rpm) {
        if (rpm < -256 || rpm > 256) throw new IllegalArgumentException("Motor RPM outside [-256, 256]");
        BlockPos motor = new BlockPos(x, y, z);
        for (BlockPos pos : List.of(motor, motor.east(4))) {
            if (!(level.getBlockEntity(pos) instanceof CreativeMotorBlockEntity entity)) {
                throw new IllegalStateException("Expected creative motor at " + pos);
            }
            // setValue invokes updateGeneratedRotation and syncs the BE, matching
            // Create's own speed control rather than writing kinetic internals.
            entity.generatedSpeed.setValue(rpm);
        }
    }

    private static BlockState state(String name) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", name)).defaultBlockState();
    }

    static Map<String, Object> describe(ServerLevel level, int x, int y, int z) {
        Map<String, Object> result = new LinkedHashMap<>();
        BlockPos motor = new BlockPos(x, y, z);
        result.put("present", true);
        result.put("dimension", level.dimension().location().toString());
        result.put("gameTime", level.getGameTime());
        result.put("origin", List.of(x, y, z));
        var shaft = level.getBlockEntity(motor.south());
        result.put("shaftSpeed", shaft instanceof KineticBlockEntity kinetic ? kinetic.getSpeed() : 0);
        var bearingEntity = level.getBlockEntity(motor.east(4).south());
        if (bearingEntity instanceof MechanicalBearingBlockEntity bearing) {
            result.put("bearingRunning", bearing.isRunning());
            result.put("bearingAngle", bearing.getInterpolatedAngle(0));
            var contraption = bearing.getMovedContraption();
            boolean alive = contraption != null && contraption.isAlive();
            result.put("contraptionCount", alive ? 1 : 0);
            result.put("contraptionBlocks", alive && contraption.getContraption() != null
                ? contraption.getContraption().getBlocks().size() : 0);
            result.put("contraptionId", alive ? contraption.getId() : -1);
            result.put("assemblyError", String.valueOf(bearing.getLastAssemblyException()));
        } else {
            result.put("bearingRunning", false);
            result.put("bearingAngle", 0);
            result.put("contraptionCount", 0);
            result.put("contraptionBlocks", 0);
            result.put("assemblyError", "Bearing block entity absent");
        }
        return result;
    }

}
