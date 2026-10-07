package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.*;

/** Read-only runtime diagnostics. Never changes the pixel oracle or forces a rebuild. Not shipped. */
public final class PortalSmokeSceneWitness {
    private PortalSmokeSceneWitness() {}

    public static Map<String, Object> capture(String request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("request", request);
        result.put("serverAck", PortalSmokeSupport.read("scene-ready.txt"));
        String server = PortalSmokeSupport.read("scene-world-witness.json");
        try {
            result.put("server", server.isEmpty() ? "unavailable" : new Gson().fromJson(server, Map.class));
            String currentServer = PortalSmokeSupport.read("scene-live-world-witness.json");
            if (!currentServer.isEmpty()) result.put("serverCurrent", new Gson().fromJson(currentServer, Map.class));
            Map<String, Object> worlds = new LinkedHashMap<>();
            for (var dimension : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
                var world = ClientWorldLoader.getOptionalWorld(dimension);
                if (world == null) continue;
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("gameTime", world.getGameTime());
                Map<String, String> blocks = new LinkedHashMap<>();
                int[] blockXs = request.contains("create-nested") && dimension == Level.NETHER
                    ? new int[]{-1, 0, 28, 31, 32, 33, 40} : new int[]{-1, 0};
                for (int x : blockXs) for (int z : new int[]{-10, -4, -1, 1}) {
                    var chunk = world.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
                    blocks.put(x + ",82," + z, chunk == null ? "chunk absent"
                        : chunk.getBlockState(new BlockPos(x, 82, z)).toString());
                }
                state.put("blocks", blocks);
                if (request.contains("create-nested") && dimension == Level.NETHER) {
                    var entities = new java.util.ArrayList<Map<String, Object>>();
                    for (var entity : world.entitiesForRendering()) {
                        if (entity.getX() >= 24 && entity.getX() <= 40 && entity.getY() >= 76 && entity.getY() <= 90) {
                            entities.add(Map.of("id", entity.getId(), "type", entity.getClass().getName(),
                                "position", List.of(entity.getX(), entity.getY(), entity.getZ()), "removed", entity.isRemoved()));
                        }
                    }
                    state.put("nestedFixtureEntities", entities);
                }
                try {
                    var renderer = ClientWorldLoader.getWorldRenderer(dimension);
                    Object backend = renderer.getClass().getMethod("sodium$getWorldRenderer").invoke(renderer);
                    Object manager = field(backend.getClass(), "renderSectionManager").get(backend);
                    Map<?, ?> sections = (Map<?, ?>) field(manager.getClass(), "sectionByPosition").get(manager);
                    state.put("backend", backend.getClass().getName());
                    state.put("sectionCount", sections.size());
                    if (request.contains("create-nested") && dimension == Level.NETHER) {
                        try {
                            Object tracker = world.getClass().getMethod("sodium$getTracker").invoke(world);
                            Map<?, ?> statuses = (Map<?, ?>) field(tracker.getClass(), "chunkStatus").get(tracker);
                            var ready = (java.util.Collection<?>) field(tracker.getClass(), "chunkReady").get(tracker);
                            var pendingLoad = (java.util.Collection<?>) field(tracker.getClass(), "loadQueue").get(tracker);
                            var pendingUnload = (java.util.Collection<?>) field(tracker.getClass(), "unloadQueue").get(tracker);
                            Map<String, Object> chunkState = new LinkedHashMap<>();
                            for (int x = 0; x <= 3; x++) for (int z = -2; z <= 1; z++) {
                                long key = net.minecraft.world.level.ChunkPos.asLong(x, z);
                                Object flagsValue = statuses.get(key);
                                int flags = flagsValue instanceof Number value ? value.intValue() : 0;
                                chunkState.put(x + "," + z, Map.of("flags", flags, "blockData", (flags & 1) != 0,
                                    "lightData", (flags & 2) != 0, "ready", ready.contains(key),
                                    "pendingLoad", pendingLoad.contains(key), "pendingUnload", pendingUnload.contains(key)));
                            }
                            state.put("chunkTracker", chunkState);
                        } catch (ReflectiveOperationException absent) { state.put("chunkTrackerDiagnostic", absent.toString()); }
                    }
                    Map<String, Object> meshes = new LinkedHashMap<>();
                    int[] sectionXs = request.contains("create-nested") && dimension == Level.NETHER
                        ? new int[]{-1, 0, 1, 2, 3} : new int[]{-1, 0};
                    for (int x : sectionXs) for (int z : new int[]{-1, 0}) {
                        Object section = sections.get(SectionPos.asLong(x, 5, z));
                        Map<String, Object> mesh = new LinkedHashMap<>();
                        mesh.put("present", section != null);
                        if (section != null) {
                            for (String method : List.of("isBuilt", "getFlags", "getPendingUpdate", "getLastSubmittedFrame", "getLastUploadFrame", "getLastVisibleFrame")) {
                                try { mesh.put(method, String.valueOf(section.getClass().getMethod(method).invoke(section))); }
                                catch (NoSuchMethodException absent) { mesh.put(method, "unavailable"); }
                            }
                        }
                        meshes.put(x + ",5," + z, mesh);
                    }
                    state.put("meshes", meshes);
                    Map<String, Object> managerState = new LinkedHashMap<>();
                    for (String name : List.of("frame", "lastUpdatedFrame", "needsGraphUpdate", "cameraPosition")) {
                        try { managerState.put(name, String.valueOf(field(manager.getClass(), name).get(manager))); }
                        catch (NoSuchFieldException absent) { managerState.put(name, "unavailable"); }
                    }
                    state.put("managerState", managerState);
                } catch (ReflectiveOperationException optionalBackend) {
                    state.put("backendDiagnostic", optionalBackend.toString());
                }
                worlds.put(dimension.location().toString(), state);
            }
            result.put("clientWorlds", worlds);
            result.put("mainFramebuffer", framebuffer(Minecraft.getInstance().getMainRenderTarget()));
            try {
                Object secondary = field(IPCGlobal.renderer.getClass(), "deferredBuffer").get(IPCGlobal.renderer);
                RenderTarget target = (RenderTarget) field(secondary.getClass(), "fb").get(secondary);
                if (target != null) result.put("deferredFramebuffer", framebuffer(target));
            } catch (ReflectiveOperationException absent) { /* Other portal renderer, no single deferred buffer. */ }
            Map<String, Object> cache = new LinkedHashMap<>();
            for (String name : List.of("iris$readFramebuffer", "iris$drawFramebuffer")) {
                try { cache.put(name, field(GlStateManager.class, name).getInt(null)); }
                catch (ReflectiveOperationException absent) { cache.put(name, "unavailable"); }
            }
            result.put("irisBindingCache", cache);
        } catch (Throwable error) {
            result.put("diagnosticError", error.toString());
        }
        return result;
    }

    private static Map<String, Object> framebuffer(RenderTarget target) {
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING), draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.frameBufferId);
        result.put("readBinding", read);
        result.put("drawBinding", draw);
        result.put("colorTexture", target.getColorTextureId());
        result.put("depthTexture", target.getDepthTextureId());
        try {
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.frameBufferId);
            result.put("status", glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER));
            for (int attachment : new int[]{GL_COLOR_ATTACHMENT0, GL_DEPTH_ATTACHMENT, GL_STENCIL_ATTACHMENT}) {
                result.put("attachment_" + Integer.toHexString(attachment),
                    glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER, attachment, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME));
            }
            for (int id : new int[]{target.getColorTextureId(), target.getDepthTextureId()}) {
                glBindTexture(GL_TEXTURE_2D, id);
                result.put("texture_" + id, Map.of("format", glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT),
                    "width", glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH),
                    "height", glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT)));
            }
        } finally {
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        }
        return result;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                var field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) { /* Search inherited target state. */ }
        }
        throw new NoSuchFieldException(name);
    }
}
