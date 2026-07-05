package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.collision.ImmPtlCollisionHooks;

@Mixin(value = Entity.class, priority = 1200)
public abstract class MixinEntity_SableCollisionCompat {
    @Shadow
    @Final
    private static Logger LOGGER;
    
    @WrapOperation(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        ),
        require = 0
    )
    private Vec3 immersivePortals$wrapCollisionForSable(
        Entity entity, Vec3 attemptedMove, Operation<Vec3> original
    ) {
        return ImmPtlCollisionHooks.handlePortalCollision(
            entity,
            attemptedMove,
            move -> original.call(entity, move),
            LOGGER
        );
    }
}
