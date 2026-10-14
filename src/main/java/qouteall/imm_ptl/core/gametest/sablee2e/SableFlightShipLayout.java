package qouteall.imm_ptl.core.gametest.sablee2e;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic, dependency-free layout; all positions are relative to the assembly origin. */
public final class SableFlightShipLayout {
    public record Position(int x, int y, int z) {
        public Position offset(int dx, int dy, int dz) {
            return new Position(x + dx, y + dy, z + dz);
        }
    }

    private final int halfWidth;
    private final int halfLength;
    private final int height;
    private final Map<Position, String> blocks;
    private final List<Position> motors;
    private final List<Position> cargo;
    private final Position seat = new Position(0, 1, 0);
    private final Position drain = new Position(0, 1, 3);

    private SableFlightShipLayout(boolean large) {
        halfWidth = large ? 24 : 3;
        halfLength = large ? 32 : 5;
        height = large ? 17 : 5;
        var placed = new LinkedHashMap<Position, String>();
        // A continuous deck keeps every fitting physically connected. The large hull
        // spans multiple hidden-plot chunks in both horizontal directions and Y sections.
        for (int x = -halfWidth; x <= halfWidth; x++) {
            for (int z = -halfLength; z <= halfLength; z++) {
                put(placed, x, 0, z, "minecraft:spruce_planks");
                if (Math.abs(x) == halfWidth || Math.abs(z) == halfLength) {
                    put(placed, x, 1, z, "minecraft:stone_bricks");
                }
            }
        }
        int ribSpacing = large ? 8 : 5;
        for (int z = -halfLength; z <= halfLength; z += ribSpacing) {
            for (int y = 1; y < height; y++) {
                put(placed, -halfWidth, y, z, "minecraft:oak_log");
                put(placed, halfWidth, y, z, "minecraft:oak_log");
            }
            for (int x = -halfWidth; x <= halfWidth; x++) {
                put(placed, x, height - 1, z, "minecraft:glass");
            }
        }
        for (int z = -halfLength; z <= halfLength; z++) {
            put(placed, 0, height - 1, z, "minecraft:waxed_copper_block");
        }
        // Distinct extremity markers make dropped far-away sections easy to diagnose.
        put(placed, -halfWidth, 0, -halfLength, "minecraft:gold_block");
        put(placed, halfWidth, 0, halfLength, "minecraft:diamond_block");
        put(placed, -halfWidth, height - 1, halfLength, "minecraft:emerald_block");
        put(placed, halfWidth, height - 1, -halfLength, "minecraft:redstone_block");

        motors = List.of(new Position(-halfWidth + 2, 1, -halfLength + 1),
            new Position(halfWidth - 2, 1, -halfLength + 1));
        for (Position motor : motors) {
            placed.put(motor, "create:creative_motor");
            placed.put(motor.offset(0, 0, 1), "create:shaft");
            placed.put(motor.offset(0, 0, 2), "create:cogwheel");
        }
        cargo = List.of(new Position(-halfWidth + 1, 1, halfLength - 1),
            new Position(halfWidth - 1, 1, halfLength - 1));
        for (Position position : cargo) placed.put(position, "minecraft:barrel");
        placed.put(seat, "create:red_seat");
        placed.put(drain, "create:item_drain");
        for (int x = -1; x <= 1; x++) put(placed, x, 1, 2, "minecraft:spruce_planks");
        // Real illumination makes the three-color target visible in naturally dark
        // dimensions. This full solid block also remains the drain's front stop.
        put(placed, 0, 1, 2, "minecraft:sea_lantern");
        // A drain advances even non-processable items and ejects through an open side.
        // Solid stops retain its item through arbitrarily long observation barriers.
        put(placed, -1, 1, 3, "minecraft:spruce_planks");
        put(placed, 1, 1, 3, "minecraft:spruce_planks");
        put(placed, 0, 1, 4, "minecraft:spruce_planks");
        for (int x = -1; x <= 1; x++) for (int y = 2; y <= 4; y++) {
            put(placed, x, y, 2, x < 0 ? "minecraft:blue_concrete" : x > 0 ? "minecraft:yellow_concrete" : "minecraft:lime_concrete");
        }
        put(placed, -2, 2, 2, "minecraft:white_concrete");
        put(placed, -2, 0, 0, "minecraft:redstone_lamp");
        put(placed, -2, 1, 0, "minecraft:lever");
        blocks = Collections.unmodifiableMap(placed);
    }

    public static SableFlightShipLayout create(boolean large) { return new SableFlightShipLayout(large); }
    public Map<Position, String> blocks() { return blocks; }
    public List<Position> motors() { return motors; }
    public List<Position> cargo() { return cargo; }
    public Position seat() { return seat; }
    public Position drain() { return drain; }
    public int halfWidth() { return halfWidth; }
    public int halfLength() { return halfLength; }
    public int width() { return halfWidth * 2 + 1; }
    public int length() { return halfLength * 2 + 1; }
    public int height() { return height; }

    private static void put(Map<Position, String> blocks, int x, int y, int z, String id) {
        blocks.put(new Position(x, y, z), id);
    }
}
