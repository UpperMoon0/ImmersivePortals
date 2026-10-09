package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import qouteall.imm_ptl.core.compat.iris_compatibility.IEIrisClippingShader;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.ducks.IEShader;
import org.joml.Matrix4fc;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import java.util.Arrays;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.KHRDebug;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded real GL state snapshots, captured after terrain setup and both matrix uploads. */
public final class PortalShaderDiagnostics {
    private PortalShaderDiagnostics() {}

    public static void captureTerrain(String backend, Matrix4fc drawModelView) {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        String dimension = Minecraft.getInstance().level == null ? "none"
            : Minecraft.getInstance().level.dimension().location().toString();
        boolean glClip = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
        String key = backend + ":" + program + ":" + dimension + ":" + PortalRendering.getPortalLayer()
            + ":" + FrontClipping.isClippingEnabled + ":" + glClip;
        if (!PortalClippingTestControl.needsTerrainState(key)) return;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("backend", backend);
        state.put("program", program);
        state.put("dimension", dimension);
        state.put("portalLayer", PortalRendering.getPortalLayer());
        state.put("logicalClippingEnabled", FrontClipping.isClippingEnabled);
        state.put("glClipDistance0Enabled", glClip);
        state.put("shadowPass", net.irisshaders.iris.shadows.ShadowRenderer.ACTIVE);
        state.put("drawFramebuffer", GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        state.put("depthWrite", GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK));
        state.put("depthTest", GL11.glIsEnabled(GL11.GL_DEPTH_TEST));
        state.put("depthFunc", GL11.glGetInteger(GL11.GL_DEPTH_FUNC));
        state.put("stencilTest", GL11.glIsEnabled(GL11.GL_STENCIL_TEST));
        state.put("scissorTest", GL11.glIsEnabled(GL11.GL_SCISSOR_TEST));
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        state.put("viewport", viewport);
        state.put("planeBeforeModelView", FrontClipping.getActiveClipPlaneEquationBeforeModelView());
        state.put("planeAfterModelView", FrontClipping.getActiveClipPlaneEquationAfterModelView());
        state.put("drawModelView", drawModelView.get(new float[16]));
        state.put("capturedGbufferModelView", PortalCapturedMatrices.modelView());
        state.put("capturedGbufferProjection", PortalCapturedMatrices.projection());
        var camera = CHelper.getCurrentCameraPos();
        state.put("camera", new double[]{camera.x, camera.y, camera.z});
        if (program > 0) {
            if (GL.getCapabilities().OpenGL43 || GL.getCapabilities().GL_KHR_debug) {
                state.put("programLabel", KHRDebug.glGetObjectLabel(KHRDebug.GL_PROGRAM, program, 256));
            }
            Map<String, Object> uniforms = new LinkedHashMap<>();
            for (String name : new String[]{"iris_ProjectionMatrix", "gbufferProjection", "gbufferProjectionInverse",
                "iris_ModelViewMatrix", "gbufferModelView", "gbufferModelViewInverse", "iportal_ClippingEquation", "cameraPosition", "u_RegionOffset"}) {
                int location = GL20.glGetUniformLocation(program, name);
                if (location >= 0) {
                    float[] value = new float[name.equals("iportal_ClippingEquation") ? 4 : (name.equals("cameraPosition") || name.equals("u_RegionOffset")) ? 3 : 16];
                    GL20.glGetUniformfv(program, location, value);
                    uniforms.put(name, value);
                }
                else {
                    uniforms.put(name, "inactive");
                }
            }
            state.put("uniforms", uniforms);
            if (PortalShadowTestControl.enabled()) {
                int[] count = new int[1], shaders = new int[8];
                GL20.glGetAttachedShaders(program, count, shaders);
                for (int i = 0; i < count[0]; i++) {
                    int type = GL20.glGetShaderi(shaders[i], GL20.GL_SHADER_TYPE);
                    PortalSmokeSupport.write("shadow-program-" + program + "-" + type + ".glsl", GL20.glGetShaderSource(shaders[i]));
                }
            }
        }
        PortalClippingTestControl.recordTerrainState(key, state);
    }

    public static void captureBufferedDraw(ShaderInstance shader, Matrix4fc modelView, Matrix4fc projection) {
        String name = shader.getName();
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        String dimension = Minecraft.getInstance().level == null ? "none"
            : Minecraft.getInstance().level.dimension().location().toString();
        boolean glClipping = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);
        String key = shader.getClass().getName() + ":" + name + ":" + program + ":" + dimension
            + ":" + PortalRendering.getPortalLayer() + ":" + FrontClipping.isClippingEnabled + ":" + glClipping;
        if (!PortalClippingTestControl.needsBufferedDrawState(key)) return;
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("shaderClass", shader.getClass().getName());
        state.put("shaderName", name);
        state.put("shaderProgram", shader.getId());
        state.put("boundProgram", program);
        state.put("dimension", dimension);
        state.put("portalLayer", PortalRendering.getPortalLayer());
        state.put("activePack", IrisInterface.invoker.isShaders());
        state.put("irisOwned", shader instanceof IEIrisClippingShader);
        state.put("vanillaUniformObject", shader instanceof IEShader vanilla && vanilla.ip_getClippingEquationUniform() != null);
        state.put("logicalClippingEnabled", FrontClipping.isClippingEnabled);
        state.put("glClipDistance0Enabled", glClipping);
        state.put("planeBeforeModelView", FrontClipping.getActiveClipPlaneEquationBeforeModelView());
        state.put("planeAfterModelView", FrontClipping.getActiveClipPlaneEquationAfterModelView());
        state.put("drawModelView", modelView.get(new float[16]));
        state.put("drawProjection", projection.get(new float[16]));
        if (program > 0) {
            int location = GL20.glGetUniformLocation(program, "iportal_ClippingEquation");
            state.put("clippingUniformLocation", location);
            if (location >= 0) {
                float[] actual = new float[4];
                GL20.glGetUniformfv(program, location, actual);
                state.put("actualClippingEquation", actual);
            }
            int chunkOffsetLocation = GL20.glGetUniformLocation(program, "ChunkOffset");
            if (chunkOffsetLocation >= 0) {
                float[] offset = new float[3];
                GL20.glGetUniformfv(program, chunkOffsetLocation, offset);
                state.put("actualChunkOffset", offset);
            }
        }
        if (program > 0) {
            int attachedCount = GL20.glGetProgrami(program, GL20.GL_ATTACHED_SHADERS);
            int[] attached = new int[attachedCount];
            GL20.glGetAttachedShaders(program, (int[]) null, attached);
            state.put("attachedShaderCount", attachedCount);
            for (int stage : attached) {
                if (GL20.glGetShaderi(stage, GL20.GL_SHADER_TYPE) == GL20.GL_VERTEX_SHADER) {
                    state.put("vertexWritesClipDistance", GL20.glGetShaderSource(stage).contains("gl_ClipDistance"));
                }
            }
        }
        PortalClippingTestControl.recordBufferedDrawState(key, state);
    }

    public static void captureInnerDepth() {
        if (!PortalClippingTestControl.observesInnerDepth()
            || (!PortalRendering.isRendering() && !PortalClippingTestControl.observesNativeDepth())) return;
        var client = Minecraft.getInstance();
        var framebuffer = client.getMainRenderTarget();
        if (client.level == null || framebuffer.viewWidth < 9 || framebuffer.viewHeight < 9) return;
        int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int rowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS);
        int swapBytes = GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES);
        float[] depth = new float[81];
        long readbackStart = System.nanoTime();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer.frameBufferId);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, GL11.GL_FALSE);
            GL11.glReadPixels(framebuffer.viewWidth / 2 - 4, framebuffer.viewHeight / 2 - 4,
                9, 9, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
        }
        finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, swapBytes);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
        }
        float center = depth[40];
        float[] sorted = depth.clone();
        Arrays.sort(sorted);
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        Map<String, Object> state = new LinkedHashMap<>();
        String dimension = client.level.dimension().location().toString();
        int layer = PortalRendering.getPortalLayer();
        state.put("stage", layer == 0 ? "native-world-before-hand" : "inner-world-before-portal-composition");
        state.put("dimension", dimension);
        state.put("portalLayer", layer);
        var cameraPosition = client.gameRenderer.getMainCamera().getPosition();
        state.put("camera", new double[]{cameraPosition.x, cameraPosition.y, cameraPosition.z});
        state.put("readFramebuffer", framebuffer.frameBufferId);
        state.put("width", framebuffer.viewWidth);
        state.put("height", framebuffer.viewHeight);
        state.put("centerDepth", center);
        state.put("minimumDepth", sorted[0]);
        state.put("medianDepth", sorted[40]);
        state.put("maximumDepth", sorted[80]);
        state.put("viewDistance", viewDistance(center, inverseProjection));
        state.put("minimumViewDistance", viewDistance(sorted[0], inverseProjection));
        state.put("medianViewDistance", viewDistance(sorted[40], inverseProjection));
        state.put("maximumViewDistance", viewDistance(sorted[80], inverseProjection));
        state.put("projection", projection.get(new float[16]));
        state.put("depthSamples", depth);
        state.put("sampleCount", depth.length);
        state.put("readbackNanos", System.nanoTime() - readbackStart);
        PortalClippingTestControl.recordInnerDepth(dimension + ":" + layer, state);
    }

    private static Object viewDistance(float depth, Matrix4fc inverseProjection) {
        Vector4f position = new Vector4f(0, 0, depth * 2 - 1, 1).mul(inverseProjection);
        float distance = -position.z / position.w;
        return Float.isFinite(distance) ? distance : "nonfinite";
    }

}
