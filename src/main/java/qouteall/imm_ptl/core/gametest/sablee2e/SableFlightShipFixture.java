package qouteall.imm_ptl.core.gametest.sablee2e;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * World-built ships, assembled through Sable's real block/BE transfer path.
 * The Create drive trains genuinely run, but do not provide lift or propulsion.
 * The flight runner supplies explicitly-labelled native-body velocity/force input.
 */
public final class SableFlightShipFixture {
    public enum Size { CONTROL, LARGE }

    private static final BlockPos REMOVED_PROBE = new BlockPos(-2, 2, 2);
    private static final BlockPos COLOR_PROBE = new BlockPos(0, 3, 2);
    private static final BlockPos LEVER_PROBE = new BlockPos(-2, 1, 0);
    private static final BlockPos LAMP_PROBE = new BlockPos(-2, 0, 0);
    private static final int INITIAL_RPM = 32;

    private final UUID id;
    private final Size size;
    private final SableFlightShipLayout layout;
    private final Map<BlockPos, BlockState> expectedStates;
    private final Map<BlockPos, String> expectedBlockEntities;
    private final Map<BlockPos, List<ItemStack>> expectedInventories;
    private final int assembledBlockCount;
    private final int plotChunks;
    private final int plotSections;

    private SableFlightShipFixture(UUID id, Size size, SableFlightShipLayout layout,
        Map<BlockPos, BlockState> states, Map<BlockPos, String> blockEntities,
        Map<BlockPos, List<ItemStack>> inventories, BlockPos plotCenter) {
        this.id = id;
        this.size = size;
        this.layout = layout;
        this.expectedStates = new LinkedHashMap<>(states);
        this.expectedBlockEntities = Map.copyOf(blockEntities);
        this.expectedInventories = Map.copyOf(inventories);
        this.assembledBlockCount = states.size();
        Set<String> chunks = new HashSet<>();
        Set<String> sections = new HashSet<>();
        states.keySet().forEach(relative -> {
            BlockPos absolute = plotCenter.offset(relative);
            String chunk = Math.floorDiv(absolute.getX(), 16) + ":" + Math.floorDiv(absolute.getZ(), 16);
            chunks.add(chunk);
            sections.add(chunk + ":" + Math.floorDiv(absolute.getY(), 16));
        });
        plotChunks = chunks.size();
        plotSections = sections.size();
    }

    public static SableFlightShipFixture assemble(ServerLevel level, BlockPos origin, Size size) {
        var layout = SableFlightShipLayout.create(size == Size.LARGE);
        var states = new LinkedHashMap<BlockPos, BlockState>();
        var blockEntities = new LinkedHashMap<BlockPos, String>();
        var inventories = new LinkedHashMap<BlockPos, List<ItemStack>>();
        var sourcePositions = new ArrayList<BlockPos>();
        AABB bounds = new AABB(origin.getX() - layout.halfWidth(), origin.getY(), origin.getZ() - layout.halfLength(),
            origin.getX() + layout.halfWidth() + 1, origin.getY() + layout.height(),
            origin.getZ() + layout.halfLength() + 1);
        Set<UUID> previousDrops = new HashSet<>();
        level.getEntitiesOfClass(ItemEntity.class, bounds.inflate(2)).forEach(item -> previousDrops.add(item.getUUID()));

        // Do not silently overwrite terrain or another fixture. The runner must select/clear
        // a disposable staging volume; every occupied layout coordinate must start empty.
        for (var entry : layout.blocks().entrySet()) {
            BlockPos relative = blockPos(entry.getKey());
            BlockPos absolute = origin.offset(relative);
            require(level.getBlockState(absolute).isAir(), "staging position is occupied: " + absolute);
            BlockState state = resolveState(entry.getValue());
            states.put(relative, state);
            sourcePositions.add(absolute);
        }
        for (var entry : states.entrySet()) {
            require(level.setBlockAndUpdate(origin.offset(entry.getKey()), entry.getValue()),
                "could not place ship block at " + entry.getKey());
        }
        // Barrels are isolated. The drain holds a non-processable diamond and has solid
        // output stops on every horizontal side, so it cannot naturally eject its cargo.
        for (int i = 0; i < layout.cargo().size(); i++) {
            BlockPos relative = blockPos(layout.cargo().get(i));
            IItemHandler inventory = inventory(level, origin.offset(relative), null);
            insertExact(inventory, 0, new ItemStack(i == 0 ? Items.DIAMOND : Items.EMERALD, 17 + i));
            insertExact(inventory, inventory.getSlots() - 1, new ItemStack(Items.COPPER_INGOT, 31 + i));
            inventories.put(relative, snapshot(inventory));
        }
        BlockPos drain = blockPos(layout.drain());
        IItemHandler drainInventory = inventory(level, origin.offset(drain), Direction.NORTH);
        insertExact(drainInventory, 0, new ItemStack(Items.DIAMOND));
        inventories.put(drain, snapshot(drainInventory));
        for (var entry : states.entrySet()) {
            BlockPos absolute = origin.offset(entry.getKey());
            if (level.getBlockEntity(absolute) != null) {
                blockEntities.put(entry.getKey(), blockEntityType(level, absolute));
            }
            // Keep the real world state after placement/neighbour callbacks as the baseline.
            entry.setValue(level.getBlockState(absolute));
        }
        var body = SubLevelAssemblyHelper.assembleBlocks(level, origin, sourcePositions,
            new BoundingBox3i(origin.getX() - layout.halfWidth(), origin.getY(), origin.getZ() - layout.halfLength(),
                origin.getX() + layout.halfWidth() + 1, origin.getY() + layout.height(),
                origin.getZ() + layout.halfLength() + 1));
        require(body instanceof ServerSubLevel, "assembly did not produce a server sublevel");
        ServerSubLevel assembled = (ServerSubLevel) body;
        require(RigidBodyHandle.of(assembled) != null, "assembled ship has no native rigid body");
        for (BlockPos source : sourcePositions) {
            require(level.getBlockState(source).isAir(), "assembly left a source block: " + source);
            require(level.getBlockEntity(source) == null, "assembly left a source block entity: " + source);
        }
        require(level.getEntitiesOfClass(ItemEntity.class, bounds.inflate(2)).stream()
            .allMatch(item -> previousDrops.contains(item.getUUID())), "assembly duplicated cargo or blocks into item drops");
        var fixture = new SableFlightShipFixture(assembled.getUniqueId(), size, layout,
            states, blockEntities, inventories, assembled.getPlot().getCenterBlock());
        if (size == Size.LARGE) {
            require(fixture.assembledBlockCount >= 3500 && fixture.plotChunks >= 12 && fixture.plotSections > fixture.plotChunks,
                "large fixture did not occupy enough real blocks/chunks/vertical sections");
        }
        fixture.verifyIntegrity(level, assembled);
        fixture.setMotorSpeed(level, assembled, INITIAL_RPM);
        return fixture;
    }

    public UUID id() { return id; }
    public Size size() { return size; }
    public SableFlightShipLayout layout() { return layout; }
    public int initialRpm() { return INITIAL_RPM; }
    public int assembledBlockCount() { return assembledBlockCount; }
    public int plotChunks() { return plotChunks; }
    public int plotSections() { return plotSections; }
    public Map<BlockPos, BlockState> expectedBlockStates() { return Collections.unmodifiableMap(expectedStates); }
    public Map<BlockPos, String> expectedBlockEntities() { return expectedBlockEntities; }
    public BlockPos removedProbePosition() { return REMOVED_PROBE; }
    public BlockPos colorProbePosition() { return COLOR_PROBE; }
    public BlockPos leverProbePosition() { return LEVER_PROBE; }
    public BlockPos lampProbePosition() { return LAMP_PROBE; }
    public BlockPos seatPosition(ServerSubLevel body) { return plotPosition(body, blockPos(layout.seat())); }
    public static BlockPos plotPosition(ServerSubLevel body, BlockPos relative) { return body.getPlot().getCenterBlock().offset(relative); }

    public ServerSubLevel requireBody(ServerLevel level) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        require(container != null, "Sable container missing in " + level.dimension().location());
        var body = container.getSubLevel(id);
        require(body instanceof ServerSubLevel, "ship missing in " + level.dimension().location() + ": " + id);
        return (ServerSubLevel) body;
    }

    /** Call after normal ticks, and again after changing speed, to prove live network continuity. */
    public Map<String, Object> verifyMachinery(ServerLevel level, ServerSubLevel body, int rpm) {
        var speeds = new ArrayList<Float>();
        for (var motorPosition : layout.motors()) {
            for (int offset = 0; offset < 3; offset++) {
                BlockPos position = plotPosition(body, blockPos(motorPosition.offset(0, 0, offset)));
                var entity = level.getBlockEntity(position);
                require(entity instanceof KineticBlockEntity, "kinetic block entity missing at " + position);
                float speed = ((KineticBlockEntity) entity).getSpeed();
                require(Float.isFinite(speed) && Math.abs(speed - rpm) < 0.01f,
                    "kinetic network has wrong speed at " + position + ": " + speed + " expected " + rpm);
                speeds.add(speed);
            }
        }
        return Map.of("motorCount", layout.motors().size(), "expectedRpm", rpm, "actualSpeeds", speeds,
            "nativePropulsion", true, "aeronauticsPropulsion", false);
    }

    public void setMotorSpeed(ServerLevel level, ServerSubLevel body, int rpm) {
        require(rpm >= -256 && rpm <= 256, "motor RPM must be between -256 and 256");
        for (var relative : layout.motors()) {
            var entity = level.getBlockEntity(plotPosition(body, blockPos(relative)));
            require(entity instanceof CreativeMotorBlockEntity, "creative motor missing after transfer");
            // The real Create behaviour invokes updateGeneratedRotation and sends BE data.
            ((CreativeMotorBlockEntity) entity).generatedSpeed.setValue(rpm);
        }
    }

    /** Toggle actual world blocks/redstone. Allow at least four normal ticks before asserting lamp-off. */
    public void mutateProbe(ServerLevel level, ServerSubLevel body, boolean enabled) {
        setExpected(level, body, REMOVED_PROBE, enabled ? Blocks.AIR.defaultBlockState() : Blocks.WHITE_CONCRETE.defaultBlockState());
        for (int x = 0; x <= 0; x++) for (int y = 2; y <= 4; y++) {
            setExpected(level, body, new BlockPos(x, y, 2),
                (enabled ? Blocks.RED_CONCRETE : Blocks.LIME_CONCRETE).defaultBlockState());
        }
        BlockState lever = expectedStates.get(LEVER_PROBE).setValue(BlockStateProperties.POWERED, enabled);
        setExpected(level, body, LEVER_PROBE, lever);
        BlockPos absolute = plotPosition(body, LEVER_PROBE);
        level.updateNeighborsAt(absolute, Blocks.LEVER);
        level.updateNeighborsAt(absolute.below(), Blocks.LEVER);
        // Do not force lamp state: its own redstone callback/scheduled tick must produce this.
        expectedStates.put(LAMP_PROBE, Blocks.REDSTONE_LAMP.defaultBlockState().setValue(BlockStateProperties.LIT, enabled));
    }

    public Map<String, Object> verifyIntegrity(ServerLevel level, ServerSubLevel body) {
        require(body.getUniqueId().equals(id), "wrong ship identity");
        require(requireBody(level) == body, "ship is not owned by the target container");
        require(RigidBodyHandle.of(body) != null, "ship lost its native body");
        int expectedNonAir = 0;
        for (var entry : expectedStates.entrySet()) {
            BlockPos position = plotPosition(body, entry.getKey());
            require(level.getBlockState(position).equals(entry.getValue()),
                "ship block changed at " + entry.getKey() + ": " + level.getBlockState(position) + " expected " + entry.getValue());
            if (!entry.getValue().isAir()) expectedNonAir++;
            String expectedEntity = expectedBlockEntities.get(entry.getKey());
            require(java.util.Objects.equals(expectedEntity, blockEntityType(level, position)),
                "ship block entity changed at " + entry.getKey());
        }
        int nonAir = 0;
        for (int x = -layout.halfWidth(); x <= layout.halfWidth(); x++) {
            for (int y = 0; y < layout.height(); y++) {
                for (int z = -layout.halfLength(); z <= layout.halfLength(); z++) {
                    if (!level.getBlockState(plotPosition(body, new BlockPos(x, y, z))).isAir()) nonAir++;
                }
            }
        }
        require(nonAir == expectedNonAir, "ship gained unexpected blocks: " + nonAir + " expected " + expectedNonAir);
        int itemCount = 0;
        for (var entry : expectedInventories.entrySet()) {
            Direction side = entry.getKey().equals(blockPos(layout.drain())) ? Direction.NORTH : null;
            IItemHandler actual = inventory(level, plotPosition(body, entry.getKey()), side);
            require(actual.getSlots() == entry.getValue().size(), "cargo slot count changed at " + entry.getKey());
            for (int slot = 0; slot < actual.getSlots(); slot++) {
                ItemStack expected = entry.getValue().get(slot);
                ItemStack observed = actual.getStackInSlot(slot);
                require(observed.getCount() == expected.getCount() && ItemStack.isSameItemSameComponents(observed, expected),
                    "cargo changed at " + entry.getKey() + " slot=" + slot);
                itemCount += observed.getCount();
            }
        }
        Vector3d velocity = RigidBodyHandle.of(body).getLinearVelocity(new Vector3d());
        require(Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z), "non-finite ship velocity");
        return Map.of("shipId", id.toString(), "size", size.name(), "assembledBlocks", assembledBlockCount,
            "currentBlocks", nonAir, "plotChunks", plotChunks, "plotSections", plotSections,
            "blockEntities", expectedBlockEntities.size(), "cargoItems", itemCount,
            "assemblyMethod", "SubLevelAssemblyHelper.assembleBlocks", "dimension", level.dimension().location().toString());
    }

    private void setExpected(ServerLevel level, ServerSubLevel body, BlockPos relative, BlockState state) {
        level.setBlockAndUpdate(plotPosition(body, relative), state);
        expectedStates.put(relative, state);
    }

    public static BlockPos blockPos(SableFlightShipLayout.Position position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static BlockState resolveState(String name) {
        ResourceLocation id = ResourceLocation.parse(name);
        require(BuiltInRegistries.BLOCK.containsKey(id), "fixture block unavailable: " + name);
        BlockState state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        if (name.equals("create:creative_motor")) state = state.setValue(BlockStateProperties.FACING, Direction.SOUTH);
        if (name.equals("create:shaft") || name.equals("create:cogwheel")) state = state.setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
        if (name.equals("minecraft:lever")) state = state.setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR);
        return state;
    }

    private static String blockEntityType(ServerLevel level, BlockPos position) {
        var entity = level.getBlockEntity(position);
        return entity == null ? null : String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
    }

    private static IItemHandler inventory(ServerLevel level, BlockPos position, Direction side) {
        IItemHandler result = level.getCapability(Capabilities.ItemHandler.BLOCK, position,
            level.getBlockState(position), level.getBlockEntity(position), side);
        require(result != null, "cargo inventory unavailable at " + position);
        return result;
    }

    private static void insertExact(IItemHandler inventory, int slot, ItemStack stack) {
        require(inventory.insertItem(slot, stack, false).isEmpty(), "could not seed exact cargo stack");
    }

    private static List<ItemStack> snapshot(IItemHandler inventory) {
        var result = new ArrayList<ItemStack>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) result.add(inventory.getStackInSlot(slot).copy());
        return List.copyOf(result);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
