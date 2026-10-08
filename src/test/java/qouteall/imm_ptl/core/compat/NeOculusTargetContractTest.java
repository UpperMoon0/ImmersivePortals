package qouteall.imm_ptl.core.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Read NeOculus separately: putting both Iris implementations on the test classpath hides ABI errors. */
class NeOculusTargetContractTest {
    private static final String IRIS = "net/irisshaders/iris/";

    @Test
    void terrainUsesItsIndependentTransformerRatherThanTheSharedIrisPatcher() throws Exception {
        try (var jar = openJar()) {
            String transformer = IRIS + "compat/embeddium/impl/monocle/ShaderTransformer";
            String descriptor = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
                + "Ljava/lang/String;Ljava/lang/String;L" + IRIS + "gl/blending/AlphaTest;"
                + "Lorg/embeddedt/embeddium/impl/render/chunk/vertex/format/ChunkVertexType;"
                + "Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/util/Map;";
            method(read(jar, transformer), "transform", descriptor);
            var programs = read(jar, IRIS + "compat/embeddium/impl/oculus/EmbeddiumPrograms");
            var method = programs.methods.stream().filter(m -> m.name.equals("transformShaders")).findFirst().orElseThrow();
            assertTrue(java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(transformer)
                    && call.name.equals("transform") && call.desc.equals(descriptor)));
            assertFalse(java.util.stream.StreamSupport.stream(method.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(IRIS + "pipeline/transform/TransformPatcher")),
                "The shared Iris hook must not be mistaken for NeOculus terrain integration");
        }
    }

    @Test
    void embeddiumShaderHasTheExactConstructorAndNoSodiumShaderAlias() throws Exception {
        try (var jar = openJar()) {
            var shader = read(jar, IRIS + "compat/embeddium/impl/oculus/EmbeddiumShader");
            assertEquals("org/embeddedt/embeddium/impl/render/chunk/shader/ChunkShaderInterface", shader.superName);
            method(shader, "<init>", "(L" + IRIS + "pipeline/IrisRenderingPipeline;L" + IRIS
                + "compat/embeddium/impl/oculus/EmbeddiumPrograms$Pass;"
                + "Lorg/embeddedt/embeddium/impl/render/chunk/shader/ShaderBindingContext;IL" + IRIS
                + "gl/blending/BlendModeOverride;Ljava/util/List;L" + IRIS
                + "uniforms/custom/CustomUniforms;Ljava/util/function/Supplier;FZ)V");
            method(shader, "setupState", "()V");
            assertNull(jar.getEntry(IRIS + "pipeline/programs/SodiumShader.class"));
        }
    }

    @Test
    void diagnosticCapturedMatricesUseTheConcreteNeOculusReturnDescriptor() throws Exception {
        try (var jar = openJar()) {
            var state = read(jar, IRIS + "uniforms/CapturedRenderingState");
            field(state, "INSTANCE", "L" + IRIS + "uniforms/CapturedRenderingState;");
            method(state, "getGbufferModelView", "()Lorg/joml/Matrix4f;");
            method(state, "getGbufferProjection", "()Lorg/joml/Matrix4f;");
            assertFalse(state.methods.stream().anyMatch(method ->
                method.name.startsWith("getGbuffer") && method.desc.equals("()Lorg/joml/Matrix4fc;")),
                "Do not link test diagnostics against Iris's interface-return ABI on NeOculus");
        }
    }

    @Test
    void sharedPipelineHooksAndInterfaceCallsMatchNeOculus() throws Exception {
        try (var jar = openJar()) {
            var pipeline = read(jar, IRIS + "pipeline/IrisRenderingPipeline");
            field(pipeline, "isRenderingWorld", "Z");
            field(pipeline, "isBeforeTranslucent", "Z");
            method(pipeline, "finalizeLevelRendering", "()V");
            method(pipeline, "beginTranslucents", "()V");
            var translucents = pipeline.methods.stream().filter(m -> m.name.equals("beginTranslucents")).findFirst().orElseThrow();
            assertTrue(java.util.stream.StreamSupport.stream(translucents.instructions.spliterator(), false)
                .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(IRIS + "pipeline/CompositeRenderer")
                    && call.name.equals("renderAll") && call.desc.equals("()V")));
            method(read(jar, IRIS + "targets/ClearPass"), "execute", "(Lorg/joml/Vector4f;)V");
            method(read(jar, IRIS + "pipeline/FinalPassRenderer"), "renderFinalPass", "()V");
            read(jar, IRIS + "shadows/ShadowRenderTargets");
            field(read(jar, IRIS + "shadows/ShadowRenderer"), "ACTIVE", "Z");
            method(read(jar, IRIS + "shadows/ShadowRenderer"), "renderShadows",
                "(L" + IRIS + "mixin/LevelRendererAccessor;Lnet/minecraft/client/Camera;)V");
            method(read(jar, IRIS + "pathways/FullScreenQuadRenderer"), "renderQuad", "()V");
            method(read(jar, IRIS + "gl/GLDebug"), "nameObject", "(IILjava/lang/String;)V");
            var iris = read(jar, IRIS + "Iris");
            field(iris, "lastDimension", "L" + IRIS + "shaderpack/materialmap/NamespacedId;");
            method(iris, "getCurrentDimension", "()L" + IRIS + "shaderpack/materialmap/NamespacedId;");
            field(read(jar, IRIS + "shaderpack/DimensionId"), "OVERWORLD", "L" + IRIS + "shaderpack/materialmap/NamespacedId;");
            method(iris, "getCurrentPack", "()Ljava/util/Optional;");
            method(iris, "getCurrentPackName", "()Ljava/lang/String;");
            method(iris, "getPipelineManager", "()L" + IRIS + "pipeline/PipelineManager;");
            var manager = read(jar, IRIS + "pipeline/PipelineManager");
            method(manager, "destroyPipeline", "()V");
            method(manager, "getPipeline", "()Ljava/util/Optional;");
            field(read(jar, IRIS + "mixin/MixinLevelRenderer"), "pipeline", "L" + IRIS + "pipeline/WorldRenderingPipeline;");
            method(read(jar, IRIS + "pipeline/transform/TransformPatcher"), "transformInternal",
                "(Ljava/lang/String;Ljava/util/Map;L" + IRIS + "pipeline/transform/parameter/Parameters;)Ljava/util/Map;");
        }
    }

    @Test
    void reusedTextureNamesCannotRetainLegacyColorOrDepthMetadata() throws Exception {
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
            java.nio.file.Path.of(System.getProperty("ip.neoculusJar")).toUri().toURL()
        }, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                String mixin = "qouteall.imm_ptl.core.compat.mixin.neoculus.MixinNeOculusTextureInfoCache";
                String accessor = "net.irisshaders.iris.mixin.GlStateManagerAccessor";
                if (name.equals("com.mojang.blaze3d.platform.GlStateManager$TextureState")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        byte[] bytes = textureBindingState();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                if (!name.equals(mixin) && !name.equals(accessor)) return super.loadClass(name, resolve);
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (name.equals(accessor)) {
                        byte[] bytes = textureBindingAccessor();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } else {
                        try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                            byte[] bytes = input.readAllBytes();
                            loaded = defineClass(name, bytes, 0, bytes.length);
                        } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }) {
            Class<?> legacyType = loader.loadClass("net.irisshaders.iris.pbr.TextureInfoCache");
            Class<?> activeType = loader.loadClass("net.irisshaders.iris.texture.TextureInfoCache");
            Object legacy = legacyType.getField("INSTANCE").get(null);
            Object active = activeType.getField("INSTANCE").get(null);
            var getInfo = legacyType.getMethod("getInfo", int.class);
            Class<?> mixinType = loader.loadClass("qouteall.imm_ptl.core.compat.mixin.neoculus.MixinNeOculusTextureInfoCache");
            Object mixin = mixinType.getConstructor().newInstance();
            var callback = mixinType.getDeclaredMethod("ip_deleteLegacyTextureInfo", int.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
            callback.setAccessible(true);
            // Supply the binding accessor and its state carrier normally made accessible
            // by runtime transformation (the raw Minecraft carrier is private).
            // Allocation metadata bookkeeping itself does not call OpenGL.
            Class<?> textureState = loader.loadClass("com.mojang.blaze3d.platform.GlStateManager$TextureState");
            var constructor = textureState.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object state = constructor.newInstance();
            var binding = textureState.getDeclaredField("binding");
            binding.setAccessible(true);
            binding.setInt(state, 37);
            Object textures = loader.loadClass("net.irisshaders.iris.mixin.GlStateManagerAccessor").getField("textures").get(null);
            java.lang.reflect.Array.set(textures, 0, state);
            var allocation = mixinType.getDeclaredMethod("ip_updateLegacyTextureInfo", int.class, int.class,
                int.class, int.class, int.class, int.class, int.class, int.class, java.nio.IntBuffer.class,
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
            allocation.setAccessible(true);
            for (int oldFormat : new int[]{0x8058, 0x81A6, 0x88F0, 0x8CAD}) {
                int reusedName = 37;
                Object stale = getInfo.invoke(legacy, reusedName);
                var format = stale.getClass().getDeclaredField("internalFormat");
                format.setAccessible(true);
                format.setInt(stale, oldFormat);
                // This is the cache invalidation actually wired into NeOculus's GL hook.
                activeType.getMethod("onDeleteTexture", int.class).invoke(active, reusedName);
                assertSame(stale, getInfo.invoke(legacy, reusedName), "Reproduce the stale legacy entry");
                assertSame(stale, getInfo.invoke(legacy, reusedName), "Repeated lookups must preserve caching");
                callback.invoke(mixin, reusedName, null);
                Object refreshed = getInfo.invoke(legacy, reusedName);
                assertNotSame(stale, refreshed, "The reused name must query its current GL storage");
                assertEquals(-1, format.getInt(refreshed), "No old internal format may survive");
                assertSame(refreshed, getInfo.invoke(legacy, reusedName), "Refreshed metadata stays cached until mutation");
                allocation.invoke(mixin, 0x0DE1, 0, oldFormat, 854, 480, 0, 0x1902, 0x1405, null, null);
                assertSame(refreshed, getInfo.invoke(legacy, reusedName), "Allocation updates the cached object");
                assertEquals(oldFormat, refreshed.getClass().getMethod("getInternalFormat").invoke(refreshed));
                assertEquals(854, refreshed.getClass().getMethod("getWidth").invoke(refreshed));
                assertEquals(480, refreshed.getClass().getMethod("getHeight").invoke(refreshed));
                allocation.invoke(mixin, 0x0DE1, 0, 0x88F0, 1280, 720, 0, 0x84F9, 0x84FA, null, null);
                assertEquals(0x88F0, refreshed.getClass().getMethod("getInternalFormat").invoke(refreshed));
                assertEquals(1280, refreshed.getClass().getMethod("getWidth").invoke(refreshed));
                assertEquals(720, refreshed.getClass().getMethod("getHeight").invoke(refreshed));
                allocation.invoke(mixin, 0x0DE1, 1, 0x8058, 640, 360, 0, 0x1908, 0x1401, null, null);
                assertEquals(1280, refreshed.getClass().getMethod("getWidth").invoke(refreshed),
                    "Mip allocation must not replace base-level metadata");
            }
        }
        try (var jar = openJar()) {
            var pipeline = read(jar, IRIS + "pipeline/IrisRenderingPipeline");
            assertTrue(pipeline.methods.stream().flatMap(m ->
                java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(IRIS + "pbr/TextureInfoCache")
                    && call.name.equals("getInfo")), "The pipeline still uses the legacy cache");
            var lifecycle = read(jar, IRIS + "mixin/texture/MixinGlStateManager");
            assertTrue(lifecycle.methods.stream().flatMap(m ->
                java.util.stream.StreamSupport.stream(m.instructions.spliterator(), false))
                .anyMatch(i -> i instanceof MethodInsnNode call && call.owner.equals(IRIS + "texture/TextureInfoCache")
                    && call.name.equals("onDeleteTexture")), "Lifecycle hooks invalidate the other cache");
            var legacy = read(jar, IRIS + "pbr/TextureInfoCache");
            String allocation = "(IIIIIIIILjava/nio/IntBuffer;)V";
            method(legacy, "onTexImage2D", allocation);
            method(legacy, "onDeleteTexture", "(I)V");
            var activeCache = read(jar, IRIS + "texture/TextureInfoCache");
            method(activeCache, "onTexImage2D", allocation);
            method(activeCache, "onDeleteTexture", "(I)V");
            method(legacy, "getInfo", "(I)L" + IRIS + "pbr/TextureInfoCache$TextureInfo;");
        }
    }

    private static byte[] textureBindingState() {
        var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC,
            "com/mojang/blaze3d/platform/GlStateManager$TextureState", null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "binding", "I", null, null).visitEnd();
        var init = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] textureBindingAccessor() {
        String name = IRIS + "mixin/GlStateManagerAccessor";
        String textures = "[Lcom/mojang/blaze3d/platform/GlStateManager$TextureState;";
        var writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
        writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC
            | org.objectweb.asm.Opcodes.ACC_INTERFACE | org.objectweb.asm.Opcodes.ACC_ABSTRACT,
            name, null, "java/lang/Object", null);
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC | org.objectweb.asm.Opcodes.ACC_FINAL,
            "textures", textures, null, null).visitEnd();
        var init = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        init.visitCode();
        init.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
        init.visitTypeInsn(org.objectweb.asm.Opcodes.ANEWARRAY, "com/mojang/blaze3d/platform/GlStateManager$TextureState");
        init.visitFieldInsn(org.objectweb.asm.Opcodes.PUTSTATIC, name, "textures", textures);
        init.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        var getter = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
            "getTEXTURES", "()" + textures, null, null);
        getter.visitCode();
        getter.visitFieldInsn(org.objectweb.asm.Opcodes.GETSTATIC, name, "textures", textures);
        getter.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        getter.visitMaxs(0, 0);
        getter.visitEnd();
        var active = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
            "getActiveTexture", "()I", null, null);
        active.visitCode();
        active.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
        active.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
        active.visitMaxs(0, 0);
        active.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
    private static ZipFile openJar() throws Exception {
        String path = System.getProperty("ip.neoculusJar");
        assertNotNull(path, "The test task must resolve the pinned NeOculus contract artifact separately from Iris");
        return new ZipFile(path);
    }

    private static ClassNode read(ZipFile jar, String name) throws Exception {
        var entry = jar.getEntry(name + ".class");
        assertNotNull(entry, "Missing NeOculus target " + name);
        try (InputStream input = jar.getInputStream(entry)) {
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static void field(ClassNode node, String name, String descriptor) {
        assertTrue(node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor)), node.name + "." + name + ":" + descriptor);
    }

    private static void method(ClassNode node, String name, String descriptor) {
        assertTrue(node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor)), node.name + "." + name + descriptor);
    }
}
