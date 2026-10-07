package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import qouteall.imm_ptl.core.compat.IPFlywheelCompat;

import java.util.LinkedHashMap;
import java.util.Map;

final class PortalSmokeCreateSceneClient {
    private PortalSmokeCreateSceneClient() {}

    static Map<String, Object> describeBackend() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("present", true);
        result.put("requested", System.getenv().getOrDefault("IP_SMOKE_FLYWHEEL_BACKEND", "default"));
        result.put("configured", String.valueOf(Backend.REGISTRY.getId(FlwConfig.INSTANCE.backend())));
        result.put("actual", String.valueOf(Backend.REGISTRY.getId(BackendManager.currentBackend())));
        result.put("backendOn", BackendManager.isBackendOn());
        result.put("portalMode", "vanilla");
        result.put("portalFallbackQueries", IPFlywheelCompat.portalFallbackQueries());
        result.put("nestedContextsRestored", IPFlywheelCompat.nestedContextsRestored());
        result.put("viewContextWitness", PortalSmokeRenderContextWitness.LIVE.snapshot());
        result.put("fallbackActiveAfterFrame", IPFlywheelCompat.useVanillaRenderer());
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        var engine = manager == null ? null : manager.getEngineImpl();
        try {
            var renderer = Minecraft.getInstance().levelRenderer;
            var get = renderer.getClass().getMethod("ip_getFlywheelRenderContext");
            renderer.getClass().getMethod("ip_setFlywheelRenderContext", RenderContextImpl.class);
            Object context = get.invoke(renderer);
            result.put("contextAccessorsInstalled", true);
            result.put("contextClearedAfterFrame", context == null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Flywheel context accessors were not applied to the live LevelRenderer", error);
        }
        result.put("mainViewEngine", engine == null ? "none" : engine.drawManager().getClass().getSimpleName());
        return result;
    }
}
