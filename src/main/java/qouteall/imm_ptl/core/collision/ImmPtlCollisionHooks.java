package qouteall.imm_ptl.core.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
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
        if (!IPGlobal.enableServerCollision) {
            if (!entity.level().isClientSide()) {
                if (entity instanceof Player) {
                    return attemptedMove;
                }
                else {
                    return Vec3.ZERO;
                }
            }
        }

        // Factorio Reactive Sleeping Pattern: stationary entities bypass cross-portal collision checks
        if (attemptedMove.lengthSqr() < 1e-6) {
            return vanillaCollision.apply(attemptedMove);
        }
        
        if (attemptedMove.lengthSqr() > 60 * 60) {
            // avoid loading too many chunks in collision calculation and lag the server
            if (LOG_COUNTER.tryDecrement()) {
                logger.error(
                    "[ImmPtl] Skipping collision calculation because entity moves too fast {} {} {}",
                    entity, attemptedMove, entity.level().getGameTime(),
                    new Throwable()
                );
            }
            
            return Vec3.ZERO;
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
}
