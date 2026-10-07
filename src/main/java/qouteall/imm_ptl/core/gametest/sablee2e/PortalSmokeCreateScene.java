package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

import java.util.Map;

/** Optional discovery-safe facade. Typed client code is never linked by a dedicated server. */
public final class PortalSmokeCreateScene {
    private PortalSmokeCreateScene() {}

    public static void setup(ServerLevel level, int x, int y, int z) {
        if (!ModList.get().isLoaded("create")) throw new IllegalStateException("Create scene requires Create");
        PortalSmokeCreateSceneServer.setup(level, x, y, z);
    }

    public static Map<String, Object> describeServerScene(ServerLevel level, int x, int y, int z) {
        if (!ModList.get().isLoaded("create")) return Map.of("present", false);
        return PortalSmokeCreateSceneServer.describe(level, x, y, z);
    }

    public static Map<String, Object> describeClientBackend() {
        if (!ModList.get().isLoaded("flywheel")) return Map.of("present", false, "actual", "absent");
        return PortalSmokeCreateSceneClient.describeBackend();
    }
}
