package qouteall.imm_ptl.core.gametest.sablee2e;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.KHRDebug;
import qouteall.imm_ptl.core.IPGlobal;

/** Runs with the real client context and transformed GlStateManager. */
final class GLResourceCacheRegression {
    static void verify() {
        var capabilities = GL.getCapabilities();
        if (!capabilities.OpenGL45 && !capabilities.GL_ARB_direct_state_access) {
            PortalSmokeSupport.write("gl-resource-pass.txt", "Legacy OpenGL allocation; DSA unavailable\n");
            return;
        }

        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean previousCache = IPGlobal.cacheGlBuffer;
        int[] buffers = new int[1001];
        int[] arrays = new int[1001];
        try {
            IPGlobal.cacheGlBuffer = true;
            // Exceed a cache batch so both cached entries and refill are covered.
            for (int i = 0; i < buffers.length; i++) {
                buffers[i] = GlStateManager._glGenBuffers();
                arrays[i] = GlStateManager._glGenVertexArrays();
                if (!GL15.glIsBuffer(buffers[i]) || !GL30.glIsVertexArray(arrays[i])) {
                    throw new IllegalStateException("Cached GL name is not an initialized object at " + i);
                }
            }
            if (GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) != previousBuffer
                || GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING) != previousArray) {
                throw new IllegalStateException("Cached GL allocation changed render bindings");
            }

            if (GL11.glGetError() != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("OpenGL error before immediate cached-object operations");
            }
            // Exercise the immediate, unbound operations from the reported log.
            if (capabilities.OpenGL43 || capabilities.GL_KHR_debug) {
                KHRDebug.glObjectLabel(KHRDebug.GL_BUFFER, buffers[0], "IP cache regression buffer");
                KHRDebug.glObjectLabel(GL11.GL_VERTEX_ARRAY, arrays[0], "IP cache regression array");
            }
            ARBDirectStateAccess.glNamedBufferData(buffers[0], 16L, GL15.GL_STATIC_DRAW);
            int size = ARBDirectStateAccess.glGetNamedBufferParameteri(buffers[0], GL15.GL_BUFFER_SIZE);
            if (size != 16) {
                throw new IllegalStateException("Immediate DSA upload to cached buffer failed: " + size);
            }
            int error = GL11.glGetError();
            if (error != GL11.GL_NO_ERROR) {
                throw new IllegalStateException("Immediate cached-object operation generated GL error " + error);
            }
            PortalSmokeSupport.write("gl-resource-pass.txt", "1001 cached buffers and arrays initialized; immediate labels and DSA upload passed\n");
        } finally {
            IPGlobal.cacheGlBuffer = previousCache;
            GL15.glDeleteBuffers(buffers);
            GL30.glDeleteVertexArrays(arrays);
        }
    }
}
