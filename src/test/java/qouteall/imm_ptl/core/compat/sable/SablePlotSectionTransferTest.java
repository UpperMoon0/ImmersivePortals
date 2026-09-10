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
        CompoundTag chunk = new CompoundTag();
        chunk.put("sections", sections);
        CompoundTag chunks = new CompoundTag();
        chunks.put("0", chunk);
        CompoundTag plot = new CompoundTag();
        plot.put("chunks", chunks);
        CompoundTag tag = new CompoundTag();
        tag.put("plot", plot);
        return tag;
    }

    @Test
    void roundTripPreservesAbsoluteHeightAndPayload() {
        CompoundTag tag = payload("4", "12", "19");
        CompoundTag original = tag.copy();
        assertTrue(SableDimensionStackCompat.rebasePlotSections(tag, -4, 0, 16));
        CompoundTag sections = tag.getCompound("plot").getCompound("chunks").getCompound("0").getCompound("sections");
        assertEquals("12", sections.getCompound("8").getString("sentinel"));
        assertTrue(SableDimensionStackCompat.rebasePlotSections(tag, 0, -4, 24));
        assertEquals(original, tag);
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
