package qouteall.imm_ptl.core.compat.valkyrienskies;

import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * Restores the VS2 vanilla renderer's ship-section collector when IP cancels
 * vanilla setupRender for a remote portal view. In the 1.21.1 VS fork,
 * LevelRendererVanillaDuck.vs$addShipVisibleChunks(Frustum) normally runs
 * at the RETURN of setupRender, which is skipped by IP's HEAD cancellation.
 *
 * <p>Reflection keeps this hook optional: an installation without VS2 must
 * never resolve a class from the third-party mod.
 */
public final class VSVisibleShipSections {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Class<?> cachedClass;
    private static Method cachedCollector;
    private static boolean reportedMissing;

    private VSVisibleShipSections() {}

    public static void appendForRemoteView(LevelRenderer renderer, Frustum frustum) {
        if (!ModList.get().isLoaded("valkyrienskies")) return;
        try {
            if (cachedClass != renderer.getClass()) {
                cachedClass = renderer.getClass();
                cachedCollector = cachedClass.getMethod("vs$addShipVisibleChunks", Frustum.class);
                reportedMissing = false;
            }
            cachedCollector.invoke(renderer, frustum);
        } catch (ReflectiveOperationException | LinkageError exception) {
            if (!reportedMissing) {
                LOGGER.warn("VS2 vanilla ship section collector unavailable: "
                    + "rendering remote shipyard geometry is not certified", exception);
                reportedMissing = true;
            }
        }
    }

    /** Called only on the render thread after disposing the client world. */
    public static void reset() {
        cachedClass = null;
        cachedCollector = null;
        reportedMissing = false;
    }
}
