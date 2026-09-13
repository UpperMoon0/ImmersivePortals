package qouteall.imm_ptl.core.compat.sable;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SablePlotSectionTransferTest {
    private static CompoundTag payload(String... indices) {
        CompoundTag sections = new CompoundTag();
        for (String index : indices) {
            CompoundTag section = new CompoundTag();
            section.putString("sentinel", index);
            sections.put(index, section);
        }
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.putLongArray("MOTION_BLOCKING", new long[]{1L, 2L, 3L});

        CompoundTag chunk = new CompoundTag();
        chunk.put("sections", sections);
        chunk.put("heightmaps", heightmaps);
        CompoundTag chunks = new CompoundTag();
        chunks.put("0", chunk);
        CompoundTag plot = new CompoundTag();
        plot.put("chunks", chunks);
        CompoundTag tag = new CompoundTag();
        tag.put("plot", plot);
        return tag;
    }

    @Test
    void roundTripPreservesAbsoluteSectionHeightButRebuildsRelativeHeightmaps() {
        CompoundTag tag = payload("4", "12", "19");
        assertTrue(SableDimensionStackCompat.rebasePlotSections(tag, -4, 0, 16));

        CompoundTag chunk = tag.getCompound("plot").getCompound("chunks").getCompound("0");
        CompoundTag sections = chunk.getCompound("sections");
        assertEquals("12", sections.getCompound("8").getString("sentinel"));
        assertTrue(chunk.getCompound("heightmaps").isEmpty(),
            "heightmaps are relative to min build height and must be rebuilt in destination");

        assertTrue(SableDimensionStackCompat.rebasePlotSections(tag, 0, -4, 24));
        sections = chunk.getCompound("sections");
        assertEquals("4", sections.getCompound("4").getString("sentinel"));
        assertEquals("12", sections.getCompound("12").getString("sentinel"));
        assertEquals("19", sections.getCompound("19").getString("sentinel"));
        assertTrue(chunk.getCompound("heightmaps").isEmpty());
    }

    @Test
    void sameVerticalOriginKeepsExistingHeightmapData() {
        CompoundTag tag = payload("1", "5");
        long[] before = tag.getCompound("plot").getCompound("chunks").getCompound("0")
            .getCompound("heightmaps").getLongArray("MOTION_BLOCKING");

        assertTrue(SableDimensionStackCompat.rebasePlotSections(tag, 0, 0, 16));
        long[] after = tag.getCompound("plot").getCompound("chunks").getCompound("0")
            .getCompound("heightmaps").getLongArray("MOTION_BLOCKING");
        assertArrayEquals(before, after);
    }

    @Test
    void rejectsOutOfBoundsWithoutPartiallyChangingPayload() {
        for (String invalid : new String[]{"0", "20"}) {
            CompoundTag tag = payload("12", invalid);
            CompoundTag original = tag.copy();
            assertFalse(SableDimensionStackCompat.rebasePlotSections(tag, -4, 0, 16));
            assertEquals(original, tag);
        }
    }
}
