package qouteall.imm_ptl.core.compat.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.CollisionInfo;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.collision.ImmPtlCollisionHooks;

@Mixin(value = Entity.class, priority = 1200)
public abstract class MixinEntity_SableCollisionCompat {
    @Shadow @Final private static Logger LOGGER;
    @Shadow protected abstract boolean isHorizontalCollisionMinor(Vec3 move);

    // Accessors are generated after all mixins' fields have been merged. A
    // high-priority @Shadow would look for Sable's field before it exists.
    @Accessor(value = "sable$collisionInfo", remap = false)
    protected abstract void immersivePortals$setSableCollisionInfo(CollisionInfo info);

    @Accessor(value = "sable$trackingSubLevel", remap = false)
    protected abstract void immersivePortals$setSableTrackingSubLevel(SubLevel subLevel);

    @Unique private boolean immersivePortals$composingSableCollision;
    @Unique private CollisionInfo immersivePortals$previousSableCollision;

    @WrapOperation(
        method = "Lnet/minecraft/world/entity/Entity;move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"
        ),
        require = 1
    )
    private Vec3 immersivePortals$wrapCollisionForSable(
        Entity entity, Vec3 attemptedMove, Operation<Vec3> original
    ) {
        Vec3 override = ImmPtlCollisionHooks.getMovementOverride(entity, attemptedMove, LOGGER);
        if (override != null) {
            immersivePortals$recordBypassedCollision(entity, attemptedMove, override);
            if (immersivePortals$composingSableCollision) {
                immersivePortals$previousSableCollision = ((EntityMovementExtension) entity).sable$getCollisionInfo();
            }
            return override;
        }

        // Run Sable's redirect, including native collision, tracking and flag
        // updates. IP replaces only its subsequent terrain solve, never the
        // entire redirect: skipping it leaves first-move state null, or reuses
        // another move's flags and inherited ship motion.
        boolean previousScope = immersivePortals$composingSableCollision;
        CollisionInfo previousInfo = immersivePortals$previousSableCollision;
        immersivePortals$composingSableCollision = true;
        immersivePortals$previousSableCollision =
            ((EntityMovementExtension) entity).sable$getCollisionInfo();
        try {
            return original.call(entity, attemptedMove);
        } finally {
            immersivePortals$composingSableCollision = previousScope;
            // A nested move can publish its own result while the outer native
            // solve is still running. Exclude that publication too: only the
            // outer redirect's subsequent new result may arm its terrain hook.
            immersivePortals$previousSableCollision = previousScope
                ? ((EntityMovementExtension) entity).sable$getCollisionInfo() : previousInfo;
        }
    }

    @WrapMethod(method = "collide")
    private Vec3 immersivePortals$collideSableTerrain(Vec3 attemptedMove, Operation<Vec3> original) {
        Entity entity = (Entity) (Object) this;
        CollisionInfo info = ((EntityMovementExtension) entity).sable$getCollisionInfo();
        if (!immersivePortals$composingSableCollision || info == null
            || info == immersivePortals$previousSableCollision || info.motion != attemptedMove) {
            return original.call(attemptedMove);
        }
        // Sable has published this move's native result. Do not apply the hook
        // to a pre-publication query, recursive terrain solve, or other callers
        // of collide (including Sable's later inherited-motion processing).
        // Consume this move's hook exactly once. The outer operation owns
        // restoration, including exceptional exits and nested same-entity moves.
        immersivePortals$composingSableCollision = false;
        SubLevel tracking = ((EntityMovementExtension) entity).sable$getTrackingSubLevel();
        try {
            return ImmPtlCollisionHooks.handlePortalCollision(
                entity, attemptedMove, move -> original.call(move), LOGGER
            );
        } finally {
            // A same-entity move from a terrain callback can replace Sable's
            // field. The outer redirect still updates/consumes its own result
            // after this call, so restore that result even on an exception.
            if (((EntityMovementExtension) entity).sable$getCollisionInfo() != info) {
                immersivePortals$setSableCollisionInfo(info);
                // The public tracking setter also changes lastTrackingSubLevelID;
                // restore just the same field that the native redirect owns.
                immersivePortals$setSableTrackingSubLevel(tracking);
            }
        }
    }

    @Unique
    private void immersivePortals$recordBypassedCollision(Entity entity, Vec3 attemptedMove, Vec3 result) {
        EntityMovementExtension movement = (EntityMovementExtension) entity;
        CollisionInfo info = new CollisionInfo();
        info.preTrackingSubLevel = movement.sable$getTrackingSubLevel();
        info.preDeltaMovement = entity.getDeltaMovement();
        info.motion = result;
        info.horizontalCollision = !Mth.equal(attemptedMove.x, result.x) || !Mth.equal(attemptedMove.z, result.z);
        info.verticalCollision = attemptedMove.y != result.y;
        info.verticalCollisionBelow = info.verticalCollision && attemptedMove.y < 0;
        info.minorHorizontalCollision = info.horizontalCollision && isHorizontalCollisionMinor(result);
        // No solver ran: preserve the association, but never manufacture a
        // sublevel contact or replay stale inherited motion / first collisions.
        immersivePortals$setSableCollisionInfo(info);
    }
}
