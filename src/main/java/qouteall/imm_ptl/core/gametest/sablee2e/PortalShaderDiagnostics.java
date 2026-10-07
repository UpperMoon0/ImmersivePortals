package qouteall.imm_ptl.core.gametest.sablee2e;

import net.minecraft.client.Minecraft;
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
                "iris_ModelViewMatrix", "gbufferModelView", "gbufferModelViewInverse", "iportal_ClippingEquation"}) {
                int location = GL20.glGetUniformLocation(program, name);
                if (location >= 0) {
                    float[] value = new float[name.equals("iportal_ClippingEquation") ? 4 : 16];
                    GL20.glGetUniformfv(program, location, value);
                    uniforms.put(name, value);
                }
                else {
                    uniforms.put(name, "inactive");
                }
            }
            state.put("uniforms", uniforms);
        }
        PortalClippingTestControl.recordTerrainState(key, state);
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
        PortalClippingTestControl.recordInnerDepth(dimension + ":" + layer, state);
    }

    private static Object viewDistance(float depth, Matrix4fc inverseProjection) {
        Vector4f position = new Vector4f(0, 0, depth * 2 - 1, 1).mul(inverseProjection);
        float distance = -position.z / position.w;
        return Float.isFinite(distance) ? distance : "nonfinite";
    }

}
