package qouteall.imm_ptl.core.gametest.sablee2e;

import com.mojang.blaze3d.pipeline.TextureTarget;
import qouteall.imm_ptl.core.compat.IPPortingLibCompat;
import qouteall.imm_ptl.core.compat.iris_compatibility.IPIrisHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;

/** Live-driver regression, invoked on the graphical client's render thread only. Not shipped. */
public final class PortalSmokeFramebufferCopyTest {
    private PortalSmokeFramebufferCopyTest() {}

    public static Map<String, Object> run() {
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int stencilMask = glGetInteger(GL_STENCIL_WRITEMASK);
        int stencilBackMask = glGetInteger(GL_STENCIL_BACK_WRITEMASK);
        int[] colorMask = new int[4];
        glGetIntegerv(GL_COLOR_WRITEMASK, colorMask);
        String forced = System.getProperty("ip.iris.forceFramebufferBlit");
        TextureTarget source = null;
        TextureTarget destination = null;
        Map<String, Object> result = new LinkedHashMap<>(IPIrisHelper.describeCopyCapabilities());
        List<Map<String, Object>> cases = new ArrayList<>();
        try {
            // Clears honor write masks. Portal rendering may have left all of them disabled.
            // These temporary raw changes are restored below without changing Minecraft's cache.
            glDepthMask(true);
            glStencilMask(~0);
            glColorMask(true, true, true, true);
            source = new TextureTarget(8, 8, true, false);
            destination = new TextureTarget(8, 8, true, false);
            IPPortingLibCompat.setIsStencilEnabled(source, true);
            IPPortingLibCompat.setIsStencilEnabled(destination, true);
            for (int size : new int[]{8, 13}) {
                source.resize(size, size, false);
                destination.resize(size, size, false);
                for (boolean forceBlit : new boolean[]{false, true}) {
                    System.setProperty("ip.iris.forceFramebufferBlit", Boolean.toString(forceBlit));
                    cases.add(verifyCopy(source, destination, forceBlit));
                }
            }
            result.put("cases", cases);
            result.put("passed", true);
            return result;
        }
        finally {
            if (forced == null) System.clearProperty("ip.iris.forceFramebufferBlit");
            else System.setProperty("ip.iris.forceFramebufferBlit", forced);
            if (source != null) source.destroyBuffers();
            if (destination != null) destination.destroyBuffers();
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
            // RenderTarget destruction updates the cached texture binding; keep it in sync.
            com.mojang.blaze3d.platform.GlStateManager._bindTexture(texture);
            glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (scissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            if (srgb) glEnable(GL_FRAMEBUFFER_SRGB); else glDisable(GL_FRAMEBUFFER_SRGB);
            glDepthMask(depthMask);
            glStencilMaskSeparate(GL_FRONT, stencilMask);
            glStencilMaskSeparate(GL_BACK, stencilBackMask);
            glColorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
        }
    }

    private static Map<String, Object> verifyCopy(TextureTarget source, TextureTarget destination,
        boolean forcedBlit) {
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_FRAMEBUFFER_SRGB);
        glBindFramebuffer(GL_FRAMEBUFFER, source.frameBufferId);
        glClearBufferfv(GL_COLOR, 0, new float[]{0.25f, 0.75f, 0.5f, 1});
        glClearBufferfi(GL_DEPTH_STENCIL, 0, 0.375f, 77);
        glBindFramebuffer(GL_FRAMEBUFFER, destination.frameBufferId);
        glClearBufferfv(GL_COLOR, 0, new float[]{0, 0, 0, 0});
        glClearBufferfi(GL_DEPTH_STENCIL, 0, 1, 0);
        glViewport(1, 2, 3, 4);
        glEnable(GL_SCISSOR_TEST);
        int[] oldScissor = new int[4];
        glGetIntegerv(GL_SCISSOR_BOX, oldScissor);
        glScissor(0, 0, 0, 0); // A leaked scissor would prevent the fallback from copying anything.
        glEnable(GL_FRAMEBUFFER_SRGB);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, destination.frameBufferId);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, source.frameBufferId);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            IPIrisHelper.newCopyDepthStencil(source, destination);
            IPIrisHelper.copyColor(source, destination);
            require(glGetInteger(GL_READ_FRAMEBUFFER_BINDING) == destination.frameBufferId, "read FBO restored");
            require(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING) == source.frameBufferId, "draw FBO restored");
            require(glGetInteger(GL_TEXTURE_BINDING_2D) == previousTexture, "texture binding restored");
            require(glIsEnabled(GL_SCISSOR_TEST) && glIsEnabled(GL_FRAMEBUFFER_SRGB), "GL enables restored");
            int[] actualViewport = new int[4];
            glGetIntegerv(GL_VIEWPORT, actualViewport);
            require(java.util.Arrays.equals(actualViewport, new int[]{1, 2, 3, 4}), "viewport restored");
            glBindFramebuffer(GL_READ_FRAMEBUFFER, destination.frameBufferId);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            float[] color = new float[4];
            float[] depth = new float[1];
            int[] stencil = new int[1];
            glReadPixels(2, 2, 1, 1, GL_RGBA, GL_FLOAT, color);
            glReadPixels(2, 2, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
            glReadPixels(2, 2, 1, 1, GL_STENCIL_INDEX, GL_UNSIGNED_INT, stencil);
            require(Math.abs(color[0] - 0.25f) < 0.006f && Math.abs(color[1] - 0.75f) < 0.006f
                && Math.abs(color[2] - 0.5f) < 0.006f && color[3] > 0.99f, "color pixels copied");
            require(Math.abs(depth[0] - 0.375f) < 0.00001f, "depth copied");
            require(stencil[0] == 77, "stencil copied");
            verifyPartialCopy(source, destination, true);
            verifyPartialCopy(source, destination, false);
            require(glGetError() == GL_NO_ERROR, "no GL errors");
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("width", source.width);
            evidence.put("forcedBlit", forcedBlit);
            evidence.put("path", IPIrisHelper.isCopyImageSubDataSupported() ? "copy-image" : "framebuffer-blit");
            evidence.put("color", List.of(color[0], color[1], color[2], color[3]));
            evidence.put("depth", depth[0]);
            evidence.put("stencil", stencil[0]);
            evidence.put("stateRestored", true);
            evidence.put("depthOnlyPreservesStencil", true);
            evidence.put("stencilOnlyPreservesDepth", true);
            return evidence;
        }
        finally {
            glScissor(oldScissor[0], oldScissor[1], oldScissor[2], oldScissor[3]);
        }
    }

    private static void verifyPartialCopy(TextureTarget source, TextureTarget destination, boolean copyDepth) {
        glDisable(GL_SCISSOR_TEST);
        glBindFramebuffer(GL_FRAMEBUFFER, destination.frameBufferId);
        glClearBufferfi(GL_DEPTH_STENCIL, 0, 0.875f, 23);
        glEnable(GL_SCISSOR_TEST); // Retain the zero-area scissor to exercise the fallback's isolation.
        IPIrisHelper.copyDepthStencil(source, destination, copyDepth, !copyDepth);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, destination.frameBufferId);
        float[] depth = new float[1];
        int[] stencil = new int[1];
        glReadPixels(2, 2, 1, 1, GL_DEPTH_COMPONENT, GL_FLOAT, depth);
        glReadPixels(2, 2, 1, 1, GL_STENCIL_INDEX, GL_UNSIGNED_INT, stencil);
        require(Math.abs(depth[0] - (copyDepth ? 0.375f : 0.875f)) < 0.00001f,
            copyDepth ? "depth-only copy writes depth" : "stencil-only copy preserves depth");
        require(stencil[0] == (copyDepth ? 23 : 77),
            copyDepth ? "depth-only copy preserves stencil" : "stencil-only copy writes stencil");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Framebuffer copy regression: " + message);
    }
}
