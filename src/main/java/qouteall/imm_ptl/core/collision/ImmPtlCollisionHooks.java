package qouteall.imm_ptl.core.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEEntity;
import qouteall.q_misc_util.my_util.CountDownInt;

import java.util.function.Function;

public final class ImmPtlCollisionHooks {
    private static final CountDownInt LOG_COUNTER = new CountDownInt(20);
    
    private ImmPtlCollisionHooks() {}
    
    public static Vec3 handlePortalCollision(
        Entity entity,
        Vec3 attemptedMove,
        Function<Vec3, Vec3> vanillaCollision,
        Logger logger
    ) {
        Vec3 override = getMovementOverride(entity, attemptedMove, logger);
        if (override != null) return override;

        // Stationary entities bypass cross-portal collision checks.
        if (attemptedMove.lengthSqr() < 1e-6) {
            return vanillaCollision.apply(attemptedMove);
        }

        PortalCollisionHandler portalCollisionHandler =
            ((IEEntity) entity).ip_getPortalCollisionHandler();
        
        if (!IPGlobal.crossPortalCollision
            || portalCollisionHandler == null
            || !portalCollisionHandler.hasCollisionEntry()
        ) {
            return vanillaCollision.apply(attemptedMove);
        }
        
        Vec3 result = portalCollisionHandler.handleCollision(entity, attemptedMove);
        
        if (result.lengthSqr() > 20 * 20) {
            if (LOG_COUNTER.tryDecrement()) {
                logger.error(
                    "[ImmPtl] cross portal collision result too large {} {} {}",
                    entity, attemptedMove, result
                );
            }
            return Vec3.ZERO;
        }
        
        return result;
    }

    /**
     * Movement policies which deliberately skip every collision solver. Sable's
     * native solver must not run before these guards (especially for huge moves).
     * Null means that normal collision composition should proceed.
     */
    public static @Nullable Vec3 getMovementOverride(Entity entity, Vec3 attemptedMove, Logger logger) {
        if (!IPGlobal.enableServerCollision && !entity.level().isClientSide()) {
            return entity instanceof Player ? attemptedMove : Vec3.ZERO;
        }
        if (attemptedMove.lengthSqr() > 60 * 60) {
            if (LOG_COUNTER.tryDecrement()) {
                logger.error(
                    "[ImmPtl] Skipping collision calculation because entity moves too fast {} {} {}",
                    entity, attemptedMove, entity.level().getGameTime(), new Throwable()
                );
            }
            return Vec3.ZERO;
        }
        return null;
    }
}
