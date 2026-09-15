package qouteall.imm_ptl.core.ducks;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;

public interface IEServerEntityManager {
    EntitySectionStorage<Entity> ip_getSectionStorage();
}
