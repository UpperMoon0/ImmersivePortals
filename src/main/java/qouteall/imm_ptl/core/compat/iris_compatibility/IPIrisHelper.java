package qouteall.imm_ptl.core.compat.iris_compatibility;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL43C;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT16;
import static org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL14C.GL_DEPTH_COMPONENT32;
import static org.lwjgl.opengl.GL30C.*;

/** Copies only like-for-like attachments. CopyImageSubData is not a format converter. */
public class IPIrisHelper {
    public static boolean isCopyImageSubDataSupported() {
        var capabilities = GL.getCapabilities();
        return FramebufferCopyPlan.supportsCopyImage(capabilities.OpenGL43, capabilities.GL_ARB_copy_image,
            capabilities.glCopyImageSubData != 0, Boolean.getBoolean("ip.iris.forceFramebufferBlit"));
    }

    public static boolean hasFloatingPointDepth(RenderTarget target) {
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, target.getDepthTextureId());
            return glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_DEPTH_TYPE) == GL_FLOAT;
        }
        finally {
            glBindTexture(GL_TEXTURE_2D, previous);
        }
    }

    public static Map<String, Object> describeCopyCapabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", glGetString(GL_VERSION));
        result.put("vendor", glGetString(GL_VENDOR));
        result.put("renderer", glGetString(GL_RENDERER));
        var capabilities = GL.getCapabilities();
        result.put("openGL43", capabilities.OpenGL43);
        result.put("arbCopyImage", capabilities.GL_ARB_copy_image);
        result.put("copyImageEntryPoint", capabilities.glCopyImageSubData != 0);
        result.put("copyImageAvailable", FramebufferCopyPlan.supportsCopyImage(capabilities.OpenGL43,
            capabilities.GL_ARB_copy_image, capabilities.glCopyImageSubData != 0, false));
        result.put("forcedBlit", Boolean.getBoolean("ip.iris.forceFramebufferBlit"));
        result.put("copyPath", isCopyImageSubDataSupported() ? "copy-image" : "framebuffer-blit");
        return result;
    }

    /** Match the scratch buffer to actual source storage, without vendor heuristics. */
    public static void matchDepthAttachment(RenderTarget source, RenderTarget scratch) {
        TextureInfo from = textureInfo(source.getDepthTextureId());
        TextureInfo to = textureInfo(scratch.getDepthTextureId());
        if (from.equals(to)) return;
        if (from.width != to.width || from.height != to.height) {
            throw unsupported("depth targets have different dimensions", from, to);
        }
        int pixelFormat;
        int pixelType;
        switch (from.format) {
            case GL_DEPTH_COMPONENT, GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT32 -> {
                pixelFormat = GL_DEPTH_COMPONENT;
                pixelType = GL_UNSIGNED_INT;
            }
            case GL_DEPTH_COMPONENT32F -> {
                pixelFormat = GL_DEPTH_COMPONENT;
                pixelType = GL_FLOAT;
            }
            case GL_DEPTH24_STENCIL8 -> {
                pixelFormat = GL_DEPTH_STENCIL;
                pixelType = GL_UNSIGNED_INT_24_8;
            }
            case GL_DEPTH32F_STENCIL8 -> {
                pixelFormat = GL_DEPTH_STENCIL;
                pixelType = GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
            }
            default -> throw unsupported("unknown depth storage format", from, to);
        }
        int texture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            validateFramebuffer(source.frameBufferId);
            validateFramebuffer(scratch.frameBufferId);
            glBindTexture(GL_TEXTURE_2D, scratch.getDepthTextureId());
            glTexImage2D(GL_TEXTURE_2D, 0, from.format, from.width, from.height,
                0, pixelFormat, pixelType, 0L);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, scratch.frameBufferId);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                GL_TEXTURE_2D, scratch.getDepthTextureId(), 0);
            glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_STENCIL_ATTACHMENT,
                GL_TEXTURE_2D, hasStencil(from.format) ? scratch.getDepthTextureId() : 0, 0);
            validateFramebuffer(scratch.frameBufferId);
        }
        finally {
            glBindTexture(GL_TEXTURE_2D, texture);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        }
    }

    public static void copyDepthStencil(RenderTarget from, RenderTarget to,
        boolean copyDepth, boolean copyStencil) {
        int mask = (copyDepth ? GL_DEPTH_BUFFER_BIT : 0) | (copyStencil ? GL_STENCIL_BUFFER_BIT : 0);
        if (mask == 0) throw new IllegalArgumentException("No framebuffer attachment selected for copy");
        // Partial packed depth/stencil copies must preserve the other component.
        copyAttachment(from, to, from.getDepthTextureId(), to.getDepthTextureId(), mask,
            copyDepth && copyStencil);
    }

    public static void newCopyDepthStencil(RenderTarget from, RenderTarget to) {
        TextureInfo info = textureInfo(from.getDepthTextureId());
        copyAttachment(from, to, from.getDepthTextureId(), to.getDepthTextureId(),
            GL_DEPTH_BUFFER_BIT | (hasStencil(info.format) ? GL_STENCIL_BUFFER_BIT : 0), true);
    }

    public static void copyColor(RenderTarget from, RenderTarget to) {
        copyAttachment(from, to, from.getColorTextureId(), to.getColorTextureId(), GL_COLOR_BUFFER_BIT, true);
    }

    private static void copyAttachment(RenderTarget from, RenderTarget to, int fromTexture, int toTexture,
        int mask, boolean wholeTexture) {
        TextureInfo source = textureInfo(fromTexture);
        TextureInfo destination = textureInfo(toTexture);
        if ((mask & GL_STENCIL_BUFFER_BIT) != 0 && !hasStencil(source.format)) {
            throw unsupported("source has no stencil component", source, destination);
        }
        FramebufferCopyPlan.requireMatchingStorage(source.format, destination.format,
            source.width, source.height, destination.width, destination.height);
        int read = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int draw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            validateFramebuffer(from.frameBufferId);
            validateFramebuffer(to.frameBufferId);
            if (FramebufferCopyPlan.useCopyImage(isCopyImageSubDataSupported(), wholeTexture)) {
                GL43C.glCopyImageSubData(fromTexture, GL_TEXTURE_2D, 0, 0, 0, 0,
                    toTexture, GL_TEXTURE_2D, 0, 0, 0, 0, source.width, source.height, 1);
            }
            else {
                if (GL.getCapabilities().glBlitFramebuffer == 0) {
                    throw unsupported("neither copy-image nor framebuffer blit is available", source, destination);
                }
                blitTextures(fromTexture, toTexture, source, mask);
            }
        }
        finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, read);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, draw);
        }
    }

    private static void blitTextures(int fromTexture, int toTexture, TextureInfo info, int mask) {
        // Private FBOs preserve the caller's read/draw-buffer selections as well as bindings.
        int readFbo = glGenFramebuffers();
        int drawFbo = glGenFramebuffers();
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean srgb = glIsEnabled(GL_FRAMEBUFFER_SRGB);
        try {
            boolean color = (mask & GL_COLOR_BUFFER_BIT) != 0;
            int attachment = color ? GL_COLOR_ATTACHMENT0
                : hasStencil(info.format) ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
            glBindFramebuffer(GL_FRAMEBUFFER, readFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, fromTexture, 0);
            glReadBuffer(color ? GL_COLOR_ATTACHMENT0 : GL_NONE);
            glDrawBuffer(color ? GL_COLOR_ATTACHMENT0 : GL_NONE);
            glBindFramebuffer(GL_FRAMEBUFFER, drawFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, attachment, GL_TEXTURE_2D, toTexture, 0);
            glReadBuffer(color ? GL_COLOR_ATTACHMENT0 : GL_NONE);
            glDrawBuffer(color ? GL_COLOR_ATTACHMENT0 : GL_NONE);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE
                || glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Immersive Portals: incomplete framebuffer-copy attachments");
            }
            glDisable(GL_SCISSOR_TEST);
            glDisable(GL_FRAMEBUFFER_SRGB);
            glBlitFramebuffer(0, 0, info.width, info.height, 0, 0, info.width, info.height, mask, GL_NEAREST);
        }
        finally {
            if (scissor) glEnable(GL_SCISSOR_TEST);
            if (srgb) glEnable(GL_FRAMEBUFFER_SRGB);
            glDeleteFramebuffers(readFbo);
            glDeleteFramebuffers(drawFbo);
        }
    }

    private static void validateFramebuffer(int framebuffer) {
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, framebuffer);
        if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Immersive Portals: copy target framebuffer " + framebuffer + " is incomplete");
        }
        if (glGetInteger(GL_SAMPLES) != 0) {
            throw new IllegalStateException("Immersive Portals: multisampled RenderTarget copies are unsupported");
        }
    }

    private static TextureInfo textureInfo(int textureId) {
        if (textureId <= 0) {
            throw new IllegalStateException("Immersive Portals: framebuffer copy requires a texture attachment");
        }
        int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
        try {
            glBindTexture(GL_TEXTURE_2D, textureId);
            return new TextureInfo(glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_INTERNAL_FORMAT),
                glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH),
                glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT));
        }
        finally {
            glBindTexture(GL_TEXTURE_2D, previous);
        }
    }

    private static boolean hasStencil(int format) {
        return format == GL_DEPTH24_STENCIL8 || format == GL_DEPTH32F_STENCIL8;
    }

    private static IllegalStateException unsupported(String reason, TextureInfo from, TextureInfo to) {
        return new IllegalStateException("Immersive Portals: cannot copy Iris framebuffer: " + reason
            + " (source=" + from + ", destination=" + to + ")");
    }

    private record TextureInfo(int format, int width, int height) {}
}
