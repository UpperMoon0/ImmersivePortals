package qouteall.imm_ptl.core.mixin.common.entity_sync;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.ducks.IEServerEntityManager;

@Mixin(PersistentEntitySectionManager.class)
public class MixinPersistentEntitySectionManager implements IEServerEntityManager {
    @Shadow
    @Final
    private EntitySectionStorage<Entity> sectionStorage;

    @Override
    public EntitySectionStorage<Entity> ip_getSectionStorage() {
        return sectionStorage;
    }
}
