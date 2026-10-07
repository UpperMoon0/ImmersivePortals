package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Actual framebuffer and live shader pipeline assertions; never packaged in releases. */
@EventBusSubscriber(modid = qouteall.imm_ptl.core.platform_specific.IPModEntry.MODID, value = Dist.CLIENT)
public final class PortalSmokeClient {
    private static boolean connecting, done, initialized, toggleDisabled, waitingToggle, crossing, crossed;
    private static int frames, stableFrames, epoch, sceneIndex;
    private static String request = "";
    private static CompletableFuture<Void> reload;
    private static long frameStart;
    private static long nestedContextBaseline;
    private static int[] previousCreatePixels;
    private static int[] previousSourcePixels;
    private static int[] crumblingBaseline;
    private static int createMotionPixels, sourceMotionPixels, crumblingChangedPixels;
    private static double crumblingDarkening;
    private static double previousCreateAngle = Double.NaN;
    private static boolean createServerMotion;
    private static Map<String, Object> createServerEvidence = Map.of();
    private static Object framebufferCopyEvidence = Map.of();
    private static final List<Double> timings = new ArrayList<>();
    private static final List<Map<String, Object>> checks = new ArrayList<>();
    private static final String[] PHASES = {"before-reload", "after-reload", "after-toggle"};

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!PortalSmokeSupport.enabled() || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (crossing && !crossed && mc.player != null && mc.level != null) {
            // Real client input exercises the portal handoff rather than a server teleport command.
            mc.options.keyUp.setDown(mc.level.dimension().equals(Level.OVERWORLD));
        }
        if (connecting || mc.screen == null) return;
        connecting = true;
        String address = "127.0.0.1:" + System.getenv("IP_SABLE_E2E_PORT");
        ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
            new ServerData("Portal visual regression", address, ServerData.Type.OTHER), true, null);
    }

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (!PortalSmokeSupport.enabled()) return;
        frameStart = System.nanoTime();
        IPGlobal.renderMode = IPGlobal.RenderMode.valueOf(System.getenv().getOrDefault("IP_SMOKE_RENDER_MODE", "normal"));
        if ("true".equals(System.getenv("IP_SMOKE_DISABLE_COPY_IMAGE"))) {
            System.setProperty("ip.iris.forceFramebufferBlit", "true");
        }
        if ("clipping-disabled".equals(System.getenv("IP_SMOKE_NEGATIVE_CONTROL"))) {
            IPGlobal.enableClippingMechanism = false;
            GL11.glDisable(GL11.GL_CLIP_PLANE0);
        }
    }

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        if (!PortalSmokeSupport.enabled() || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay() != null) return;
        try {
            mc.options.hideGui = true;
            if (!initialized) {
                GLResourceCacheRegression.verify();
                verifyMods();
                mc.options.fov().set(70);
                if (PortalSmokeSupport.activeShaders()) {
                    framebufferCopyEvidence = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeFramebufferCopyTest")
                        .getMethod("run").invoke(null);
                }
                initialized = true;
                requestScene();
                return;
            }
            if (reload != null) {
                if (!reload.isDone()) return;
                reload.join();
                reload = null;
                requestScene();
                return;
            }
            if (waitingToggle) {
                if (++frames < 120) return;
                require(!IrisInterface.invoker.isShaders(), "Shader toggle did not disable the pack");
                require(!IPCGlobal.renderer.getClass().getSimpleName().contains("Iris"), "Shader disable retained Iris portal renderer");
                toggleDisabled = true;
                toggleShaders(true);
                waitingToggle = false;
                requestScene();
                return;
            }
            if (crossed) {
                collectMetrics(mc);
                return;
            }
            if (!request.equals(PortalSmokeSupport.read("scene-ready.txt"))) return;
            if (++frames < 120 || frames % 10 != 0) return;
            verifyPipeline(); // Checked after every rebuild and scene, not just mod presence at startup.
            if (crossing) {
                if (!mc.level.dimension().equals(Level.NETHER) || !PortalSmokeSupport.exists("crossing-server-pass.txt")) {
                    if (frames > 1200) throw new IllegalStateException("CROSSING_TIMEOUT: player did not cross the portal");
                    return;
                }
                mc.options.keyUp.setDown(false);
                if (!crossed) {
                    capture("crossing", "after-crossing", false);
                    crossed = true;
                    PortalSmokeSupport.write("visual-pass.txt", "All positive controls, excluded-plane scenes, nested/mirror views, reload/toggle and crossing passed\n");
                    saveEvidence();
                }
                collectMetrics(mc);
                return;
            }
            String scene = PortalSmokeSupport.scenes().get(sceneIndex);
            if (!capture(scene, PHASES[epoch], true)) return;
            if (++sceneIndex < PortalSmokeSupport.scenes().size()) {
                requestScene();
            } else if (epoch == 0) {
                epoch = 1;
                sceneIndex = 0;
                // Resize along with reload to invalidate render targets in both copy paths.
                GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 640);
                reload = mc.reloadResourcePacks();
            } else if (epoch == 1 && PortalSmokeSupport.activeShaders()) {
                epoch = 2;
                sceneIndex = 0;
                toggleShaders(false);
                waitingToggle = true;
                frames = 0;
            } else {
                crossing = true;
                request = "after-crossing:crossing";
                frames = 0;
                PortalSmokeSupport.write("scene-request.txt", request);
                PortalSmokeSupport.write("crossing-request.txt", "Walk through the real portal");
            }
        } catch (Throwable e) {
            done = true;
            mc.options.keyUp.setDown(false);
            saveEvidence();
            PortalSmokeSupport.write("client-fail.txt", e.toString());
            mc.stop();
        }
    }

    private static void requestScene() throws ReflectiveOperationException {
        frames = stableFrames = 0;
        previousCreatePixels = null;
        previousSourcePixels = null;
        createMotionPixels = sourceMotionPixels = crumblingChangedPixels = 0;
        crumblingDarkening = 0;
        if (PortalSmokeSupport.scenes().get(sceneIndex).equals("create-crumbling-clean")) crumblingBaseline = null;
        previousCreateAngle = Double.NaN;
        createServerMotion = false;
        createServerEvidence = Map.of();
        if (PortalSmokeSupport.scenes().get(sceneIndex).equals("create-nested")) {
            Map<?, ?> flywheel = (Map<?, ?>) runtimeState().get("flywheel");
            nestedContextBaseline = ((Number) flywheel.get("nestedContextsRestored")).longValue();
        }
        request = PHASES[epoch] + ":" + PortalSmokeSupport.scenes().get(sceneIndex);
        PortalSmokeSupport.write("scene-request.txt", request);
    }

    private static boolean capture(String scene, String phase, boolean requirePortal) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int green = 0, red = 0, total = 0;
            for (int x = pixels.getWidth() * 2 / 5; x < pixels.getWidth() * 3 / 5; x++) {
                for (int y = pixels.getHeight() * 2 / 5; y < pixels.getHeight() * 3 / 5; y++) {
                    int color = pixels.getPixelRGBA(x, y);
                    int r = color & 255, g = (color >>> 8) & 255, b = (color >>> 16) & 255;
                    if (g >= 20 && g >= r + 4 && g >= b * 2) green++;
                    if (r >= 30 && r > g * 1.3 && r > b * 1.3) red++;
                    total++;
                }
            }
            boolean crumbling = scene.startsWith("create-crumbling-");
            if (scene.startsWith("create-")) {
                readCreateServerEvidence(crumbling);
                int[] sourceRegion = region(pixels, 0.82, 0.22, 0.98, 0.78);
                sourceMotionPixels += changes(previousSourcePixels, sourceRegion);
                previousSourcePixels = sourceRegion;
                int[] targetRegion = region(pixels, 0.38, 0.18, 0.64, 0.78);
                createMotionPixels += changes(previousCreatePixels, targetRegion);
                previousCreatePixels = targetRegion;
                if (scene.equals("create-crumbling-clean")) crumblingBaseline = targetRegion;
                if (scene.equals("create-crumbling-damaged") && crumblingBaseline != null) {
                    crumblingChangedPixels = changes(crumblingBaseline, targetRegion);
                    crumblingDarkening = meanBrightness(crumblingBaseline) - meanBrightness(targetRegion);
                }
            }
            boolean positive = scene.endsWith("-visible") || scene.equals("create-nested") || scene.equals("create-crumbling-clean") || scene.equals("create-crumbling-damaged");
            // Visible controls establish that each geometry path really draws in this camera.
            // Create uses its own textures; shader fixture programs for other paths output diagnostic red.
            boolean colors = positive ? (scene.startsWith("create-") ? green < total * 0.97 : red > total * 0.02)
                : green > total * 0.80 && red < total * 0.01;
            int layers = RenderStates.portalRenderInfos.stream().mapToInt(List::size).max().orElse(0);
            boolean geometry = (!requirePortal || RenderStates.portalsRenderedThisFrame > 0)
                && (!(scene.equals("nested") || scene.equals("create-nested")) || layers >= 2)
                && (!scene.startsWith("create-") || !requiresSourcePixels() || sourceMotionPixels > 20)
                && (!(scene.equals("create-visible") || scene.equals("create-nested")) || (createMotionPixels > 20 && createServerMotion))
                && (!scene.equals("create-crumbling-damaged") || (crumblingChangedPixels > 20 && crumblingDarkening > 0.5));
            String screenshot = phase + "-" + scene + ".png";
            pixels.writeToFile(PortalSmokeSupport.directory().resolve(screenshot));
            stableFrames = colors && geometry ? stableFrames + 1 : 0;
            if (frames > 720) throw new IllegalStateException("PORTAL_PIXELS_MISMATCH: " + phase + "/" + scene
                + " green=" + green + "/" + total + " red=" + red + " layers=" + layers
                + " targetMotion=" + createMotionPixels + " sourceMotion=" + sourceMotionPixels
                + " crumblingChanges=" + crumblingChangedPixels + " darkening=" + crumblingDarkening);
            if (stableFrames < 3 && requirePortal) return false;
            if (!colors) throw new IllegalStateException("PORTAL_PIXELS_MISMATCH after crossing");
            int glError = GL11.glGetError();
            require(glError == GL11.GL_NO_ERROR, "OpenGL error after portal scene: 0x" + Integer.toHexString(glError));
            Map<String, Object> check = runtimeState();
            check.putAll(Map.of("phase", phase, "scene", scene, "screenshot", screenshot,
                "green_pixels", green, "red_pixels", red, "sampled_pixels", total,
                "portal_layers", layers, "width", pixels.getWidth(), "height", pixels.getHeight()));
            check.put("create_motion_changed_pixels", createMotionPixels);
            check.put("source_motion_changed_pixels", sourceMotionPixels);
            check.put("source_visible_required", requiresSourcePixels());
            check.put("target_motion_region", List.of(0.38, 0.18, 0.64, 0.78));
            check.put("source_motion_region", List.of(0.82, 0.22, 0.98, 0.78));
            check.put("crumbling_changed_pixels", crumblingChangedPixels);
            check.put("crumbling_darkening", crumblingDarkening);
            if (scene.startsWith("create-")) {
                verifyFlywheel(check.get("flywheel"));
                if (scene.equals("create-nested")) {
                    Map<?, ?> flywheel = (Map<?, ?>) check.get("flywheel");
                    long restored = ((Number) flywheel.get("nestedContextsRestored")).longValue() - nestedContextBaseline;
                    require(restored > 0, "Same-dimension nested Flywheel context restoration never ran in this scene");
                    check.put("nested_contexts_restored_this_scene", restored);
                }
                check.put("create_server", createServerEvidence);
                check.put("create_server_motion", createServerMotion);
            }
            checks.add(check);
            saveEvidence();
            return true;
        }
    }

    private static boolean requiresSourcePixels() {
        // Debug mode intentionally replaces the whole display with the portal view.
        return !System.getenv().getOrDefault("IP_SMOKE_RENDER_MODE", "normal").equals("debug");
    }

    private static int[] region(NativeImage image, double left, double top, double right, double bottom) {
        int x0 = (int) (image.getWidth() * left), x1 = (int) (image.getWidth() * right);
        int y0 = (int) (image.getHeight() * top), y1 = (int) (image.getHeight() * bottom);
        int[] result = new int[(x1 - x0) * (y1 - y0)];
        int index = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) result[index++] = image.getPixelRGBA(x, y);
        return result;
    }

    private static int changes(int[] previous, int[] current) {
        if (previous == null || previous.length != current.length) return 0;
        int changed = 0;
        for (int i = 0; i < current.length; i++) {
            int delta = 0;
            for (int shift : new int[]{0, 8, 16}) delta += Math.abs(((previous[i] >>> shift) & 255) - ((current[i] >>> shift) & 255));
            if (delta > 30) changed++;
        }
        return changed;
    }

    private static double meanBrightness(int[] pixels) {
        double sum = 0;
        for (int color : pixels) sum += (color & 255) + ((color >>> 8) & 255) + ((color >>> 16) & 255);
        return sum / (pixels.length * 3.0);
    }

    private static void verifyMods() {
        String lane = System.getenv().getOrDefault("IP_SMOKE_RENDERER", "sodium");
        boolean embeddium = lane.startsWith("embeddium") || lane.startsWith("neoculus");
        boolean sodium = lane.equals("sodium") || lane.startsWith("iris") || lane.equals("veil");
        require(ModList.get().isLoaded("sodium") == sodium, "Wrong Sodium runtime for " + lane);
        require(ModList.get().isLoaded("embeddium") == embeddium, "Wrong Embeddium runtime for " + lane);
        require(ModList.get().isLoaded("oculus") == lane.startsWith("neoculus"), "Wrong NeOculus runtime for " + lane);
        if (lane.startsWith("iris")) require(ModList.get().isLoaded("iris"), "Official Iris is absent");
        if (lane.equals("veil")) require(ModList.get().isLoaded("veil"), "Veil is absent");
    }

    private static void verifyPipeline() throws ReflectiveOperationException {
        Map<String, Object> actual = runtimeState();
        if (PortalSmokeSupport.activeShaders()) {
            String expected = System.getenv().getOrDefault("IP_SMOKE_RENDER_MODE", "normal").equals("normal")
                ? "IrisPortalRenderer" : "IrisCompatibilityPortalRenderer";
            require(Boolean.TRUE.equals(actual.get("shaders_active"))
                && System.getenv().getOrDefault("IP_SMOKE_SHADERPACK_NAME", "ip-clipping-fixture-v1").equals(actual.get("pack"))
                && actual.get("pipeline").toString().contains("Iris")
                && !actual.get("pipeline").toString().contains("Vanilla")
                && expected.equals(actual.get("renderer")), "SHADER_FIXTURE_NOT_ACTIVE: " + actual);
        } else {
            require(!IrisInterface.invoker.isShaders(), "Shaders-off lane unexpectedly enabled a shaderpack");
        }
    }

    private static Map<String, Object> runtimeState() throws ReflectiveOperationException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("shaders_active", IrisInterface.invoker.isShaders());
        state.put("pack", Optional.ofNullable(IrisInterface.invoker.getShaderpackName()).orElse(""));
        state.put("renderer", IPCGlobal.renderer.getClass().getSimpleName());
        String pipeline = "";
        Map<String, String> shaderOptions = new LinkedHashMap<>();
        if (IrisInterface.invoker.isIrisPresent()) {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Optional<?> active = (Optional<?>) manager.getClass().getMethod("getPipeline").invoke(manager);
            pipeline = active.map(p -> p.getClass().getName()).orElse("");
            Optional<?> pack = (Optional<?>) iris.getMethod("getCurrentPack").invoke(null);
            Map<?, ?> fixture = new Gson().fromJson(PortalSmokeSupport.read("fixture.json"), Map.class);
            Map<?, ?> expectedOptions = fixture == null ? Map.of() : (Map<?, ?>) fixture.getOrDefault("options", null);
            if (pack.isPresent() && expectedOptions != null && !expectedOptions.isEmpty()) {
                Object packOptions = pack.get().getClass().getMethod("getShaderPackOptions").invoke(pack.get());
                Object values = packOptions.getClass().getMethod("getOptionValues").invoke(packOptions);
                Class<?> optionValues = Class.forName("net.irisshaders.iris.shaderpack.option.values.OptionValues");
                for (var entry : expectedOptions.entrySet()) {
                    String name = entry.getKey().toString(), expected = entry.getValue().toString();
                    String accessor = expected.equals("true") || expected.equals("false") ? "getBooleanValueOrDefault" : "getStringValueOrDefault";
                    String actual = String.valueOf(optionValues.getMethod(accessor, String.class).invoke(values, name));
                    require(expected.equals(actual), "Shader option was not applied: " + name + " expected=" + expected + " actual=" + actual);
                    shaderOptions.put(name, actual);
                }
            }
        }
        state.put("pipeline", pipeline);
        state.put("shader_options", shaderOptions);
        state.put("gl_version", GL11.glGetString(GL11.GL_VERSION));
        state.put("gl_vendor", GL11.glGetString(GL11.GL_VENDOR));
        state.put("gl_renderer", GL11.glGetString(GL11.GL_RENDERER));
        state.put("copy_image_available", GL.getCapabilities().OpenGL43 || GL.getCapabilities().GL_ARB_copy_image);
        state.put("forced_framebuffer_blit", Boolean.getBoolean("ip.iris.forceFramebufferBlit"));
        Class<?> helper = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeCreateScene");
        state.put("flywheel", helper.getMethod("describeClientBackend").invoke(null));
        return state;
    }

    @SuppressWarnings("unchecked")
    private static void readCreateServerEvidence(boolean crumbling) {
        Map<String, Object> result = new Gson().fromJson(PortalSmokeSupport.read("create-server.json"), Map.class);
        require(result != null && request.equals(result.get("scene")), "Missing current Create server evidence");
        for (String side : List.of("source", "target")) {
            Map<String, Object> assembly = (Map<String, Object>) result.get(side);
            if (crumbling && side.equals("target")) {
                require(((Number) assembly.get("shaftSpeed")).doubleValue() == 0,
                    "Crumbling comparison requires a stationary target cog: " + assembly);
                continue;
            }
            require(((Number) assembly.get("shaftSpeed")).doubleValue() != 0
                && Boolean.TRUE.equals(assembly.get("bearingRunning"))
                && ((Number) assembly.get("contraptionCount")).intValue() > 0,
                "Create fixture was not running on " + side + ": " + assembly);
        }
        Map<String, Object> target = (Map<String, Object>) result.get("target");
        double angle = ((Number) target.get("bearingAngle")).doubleValue();
        if (Double.isFinite(previousCreateAngle) && Math.abs(angle - previousCreateAngle) > 0.01) createServerMotion = true;
        previousCreateAngle = angle;
        createServerEvidence = result;
    }

    private static void verifyFlywheel(Object evidence) {
        Map<?, ?> backend = (Map<?, ?>) evidence;
        require(Boolean.TRUE.equals(backend.get("contextAccessorsInstalled")), "Live Flywheel context accessors were not installed");
        require(Boolean.TRUE.equals(backend.get("contextClearedAfterFrame")), "Flywheel render context leaked after frame");
        String requested = System.getenv().getOrDefault("IP_SMOKE_FLYWHEEL_BACKEND", "default");
        if (!requested.equals("default")) {
            require(("flywheel:" + requested).equals(backend.get("actual")), "Requested Flywheel backend was not active: " + backend);
        }
        if (Boolean.TRUE.equals(backend.get("backendOn"))) {
            require(backend.get("mainViewEngine") != null && !"none".equals(backend.get("mainViewEngine")),
                "Flywheel active backend did not create a main-view engine: " + backend);
            require(((Number) backend.get("portalFallbackQueries")).longValue() > 0, "Flywheel portal fallback never ran: " + backend);
        }
        require(Boolean.FALSE.equals(backend.get("fallbackActiveAfterFrame")), "Flywheel portal context leaked into main view: " + backend);
    }

    private static void toggleShaders(boolean enabled) throws ReflectiveOperationException {
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        Object config = iris.getMethod("getIrisConfig").invoke(null);
        config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
        iris.getMethod("reload").invoke(null);
    }

    private static void saveEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("checks", checks);
        evidence.put("framebuffer_copy", framebufferCopyEvidence);
        evidence.put("render_mode", System.getenv().getOrDefault("IP_SMOKE_RENDER_MODE", "normal"));
        evidence.put("required_scenes", PortalSmokeSupport.scenes());
        evidence.put("toggle_disabled_verified", toggleDisabled);
        evidence.put("mods", ModList.get().getMods().stream().map(mod -> Map.of(
            "id", mod.getModId(), "version", mod.getVersion().toString())).toList());
        PortalSmokeSupport.write("runtime-evidence.json", new Gson().toJson(evidence));
    }

    private static void collectMetrics(Minecraft mc) {
        timings.add((System.nanoTime() - frameStart) / 1_000_000.0);
        if (timings.size() >= PortalSmokeSupport.samples() && PortalSmokeSupport.exists("server-pass.txt")) {
            PortalSmokeSupport.metrics("client", timings);
            PortalSmokeSupport.write("client-pass.txt", "Framebuffer, active pipeline, reload/toggle, crossing and live frame measurements passed\n");
            done = true;
            mc.stop();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
