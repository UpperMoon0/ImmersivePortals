package qouteall.imm_ptl.core.gametest.sablee2e;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.impl.FlwConfig;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.client.Minecraft;
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
        result.put("fallbackActiveAfterFrame", IPFlywheelCompat.useVanillaRenderer());
        var manager = VisualizationManagerImpl.get(Minecraft.getInstance().level);
        var engine = manager == null ? null : manager.getEngineImpl();
        result.put("mainViewEngine", engine == null ? "none" : engine.drawManager().getClass().getSimpleName());
        return result;
    }
}
