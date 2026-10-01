package qouteall.imm_ptl.core.render.optimization;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.ARBDirectStateAccess;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;

import java.util.function.Consumer;

public class GLResourceCache {
    public static Minecraft client = Minecraft.getInstance();
    
    private final Consumer<int[]> generator;
    private final IntList bufferIds = new IntArrayList();
    
    public static GLResourceCache bufferCache = new GLResourceCache(GLResourceCache::createBuffers);
    public static GLResourceCache vertexArrayCache = new GLResourceCache(GLResourceCache::createVertexArrays);

    private static void createBuffers(int[] ids) {
        // glGen only reserves names. Our HEAD injection bypasses Veil's
        // glCreate replacement, so cached names must already denote objects
        // before Veil labels them or uploads with glNamedBufferData.
        if (supportsDirectStateAccess()) {
            ARBDirectStateAccess.glCreateBuffers(ids);
        } else {
            GL15.glGenBuffers(ids);
        }
    }

    private static void createVertexArrays(int[] ids) {
        if (supportsDirectStateAccess()) {
            ARBDirectStateAccess.glCreateVertexArrays(ids);
        } else {
            GL30.glGenVertexArrays(ids);
        }
    }

    private static boolean supportsDirectStateAccess() {
        // Query only when reserving, with the caller's OpenGL context current.
        var capabilities = GL.getCapabilities();
        return capabilities.OpenGL45 || capabilities.GL_ARB_direct_state_access;
    }
    
    public GLResourceCache(Consumer<int[]> generator) {
        this.generator = generator;
    }
    
    public int getNewResourceId() {
        if (bufferIds.isEmpty()) {
            reserve(1000);
        }
        
        int taken = bufferIds.removeInt(bufferIds.size() - 1);
        return taken;
    }
    
    public static void init() {
    
    }
    
    private void reserve(int num) {
        int[] buf = new int[num];
        generator.accept(buf);
        bufferIds.addElements(bufferIds.size(), buf);
    }
    
    private int getExpectedBufferSize() {
        int viewDistance = client.options.getEffectiveRenderDistance();
        int diameter = viewDistance * 2 + 1;
        
        //every column has 16 sections, every section has 5 layers
        return diameter * diameter * 16 * 5 * 4;
    }
}
