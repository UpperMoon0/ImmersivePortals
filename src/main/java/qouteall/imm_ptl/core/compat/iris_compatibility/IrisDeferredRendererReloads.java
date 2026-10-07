package qouteall.imm_ptl.core.compat.iris_compatibility;

import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/** Preserve the providers' one-shot reload requests without destroying a live portal render context. */
public final class IrisDeferredRendererReloads {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrisDeferredRendererReloads.class);
    private static final DeferredShaderReloadQueue QUEUE = new DeferredShaderReloadQueue();

    private IrisDeferredRendererReloads() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(IPGlobal.PreGameRenderEvent.class,
            event -> QUEUE.drain(WorldRenderInfo.isRendering()));
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> clear());
    }

    public static void clear() {
        QUEUE.clear();
    }

    /** Called only at audited provider allChanged call sites, never for render-distance updates. */
    public static boolean defer(LevelRenderer renderer, PipelineManager manager, WorldRenderingPipeline pipeline) {
        if (!WorldRenderInfo.isRendering() || ClientWorldLoader.getIsCreatingClientWorld()) return false;
        Minecraft client = Minecraft.getInstance();
        ClientLevel world = client.level;
        if (world == null || client.levelRenderer != renderer || pipeline == null) {
            throw new IllegalStateException("Shader reload request has no owning world/renderer/pipeline");
        }
        int generation = manager.getVersionCounterForSodiumShaderReload();
        ShaderRenderingSettingsSnapshot settings = ShaderRenderingSettingsSnapshot.capture(WorldRenderingSettings.INSTANCE);
        QUEUE.request(renderer, () -> {
            // A shader toggle, disconnect, or world replacement invalidates the old request.
            if (Iris.getPipelineManager() != manager
                || manager.getVersionCounterForSodiumShaderReload() != generation
                || !ClientWorldLoader.getIsInitialized()
                || ClientWorldLoader.WORLD_RENDERER_MAP.get(world.dimension()) != renderer
                || ClientWorldLoader.getClientWorlds().stream().noneMatch(loaded -> loaded == world)) return;
            replay(world, renderer, manager, pipeline, settings);
        });
        LOGGER.info("Deferring shader world renderer reload {}", world.dimension().location());
        return true;
    }

    private static void replay(ClientLevel world, LevelRenderer renderer, PipelineManager manager,
        WorldRenderingPipeline pipeline, ShaderRenderingSettingsSnapshot settings) {
        ShaderRenderingSettingsSnapshot previousSettings = ShaderRenderingSettingsSnapshot.capture(WorldRenderingSettings.INSTANCE);
        WorldRenderingPipeline previousPipeline = manager.getPipelineNullable();
        Object previousRendererPipeline = IrisInterface.invoker.getPipeline(renderer);
        try {
            settings.restore();
            ((IEIrisPipelineManager) manager).ip_setPipeline(pipeline);
            IrisInterface.invoker.setPipeline(renderer, pipeline);
            // A provider request belongs to one renderer. Global propagation here would
            // rebuild other dimensions against this pipeline's material/vertex settings.
            ClientWorldLoader.reloadWorldRenderer(world);
            LOGGER.info("Replayed shader world renderer reload {}", world.dimension().location());
        }
        finally {
            IrisInterface.invoker.setPipeline(renderer, previousRendererPipeline);
            ((IEIrisPipelineManager) manager).ip_setPipeline(previousPipeline);
            previousSettings.restore();
        }
    }
}
