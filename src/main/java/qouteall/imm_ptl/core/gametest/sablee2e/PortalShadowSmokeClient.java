package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/** Bounded owned-pack shadow acceptance, independent of ordinary smoke scene ownership. */
@EventBusSubscriber(modid = qouteall.imm_ptl.core.platform_specific.IPModEntry.MODID, value = Dist.CLIENT)
public final class PortalShadowSmokeClient {
    private static final String[] PHASES = {"before-reload", "after-reload"};
    private static final String[] SCENES = {"lit", "caster", "restored"};
    private static boolean connecting, initialized, done, visualDone;
    private static int phase, scene, frames, stable;
    private static long frameStart;
    private static String request = "", observing = "";
    private static CompletableFuture<Void> reload;
    private static final List<Map<String, Object>> checks = new ArrayList<>();
    private static final List<Double> timings = new ArrayList<>();
    private static Map<String, Object> lastProbe = Map.of();

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!PortalShadowTestControl.enabled() || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (connecting || mc.screen == null) return;
        connecting = true;
        PortalClippingTestControl.install("none");
        String address = "127.0.0.1:" + System.getenv("IP_SABLE_E2E_PORT");
        ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
            new ServerData("Portal shadow acceptance", address, ServerData.Type.OTHER), true, null);
    }
    @SubscribeEvent public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (!PortalShadowTestControl.enabled()) return;
        frameStart = System.nanoTime();
        IPGlobal.renderMode = IPGlobal.RenderMode.normal;
    }
    @SubscribeEvent public static void afterFrame(RenderFrameEvent.Post event) {
        if (!PortalShadowTestControl.enabled() || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay() != null) return;
        try {
            mc.options.hideGui = true;
            mc.options.fov().set(70);
            if (visualDone) {
                timings.add((System.nanoTime() - frameStart) / 1_000_000.0);
                if (timings.size() >= PortalSmokeSupport.samples() && PortalSmokeSupport.exists("server-pass.txt")) {
                    PortalSmokeSupport.metrics("client", timings);
                    PortalSmokeSupport.write("client-pass.txt", "Actual shadow-map depth, off-plane caster receiver pixels and reload passed");
                    done = true;
                    mc.stop();
                }
                return;
            }
            if (!initialized) {
                initialized = true;
                requestScene();
                return;
            }
            if (reload != null) {
                if (!reload.isDone()) return;
                reload.join(); reload = null;
                requestScene(); return;
            }
            if (!request.equals(PortalSmokeSupport.read("shadow-ready.txt"))) return;
            if (!observing.equals(request)) {
                observing = request;
                frames = stable = 0;
                PortalShadowTestControl.begin(request);
                PortalClippingTestControl.beginObservation(request);
                return;
            }
            if (++frames < 120 || frames % 10 != 0) return;
            verifyRuntime();
            if (!capture(mc)) return;
            if (++scene < SCENES.length) requestScene();
            else if (phase == 0) {
                phase = 1; scene = 0;
                GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 960, 640);
                reload = mc.reloadResourcePacks();
            } else {
                visualDone = true;
                PortalSmokeSupport.write("visual-pass.txt", "Shadow receiver remained lit/occluded/restored across resource reload");
                save();
            }
        } catch (Throwable error) {
            done = true;
            save();
            PortalSmokeSupport.write("client-fail.txt", error.toString());
            mc.stop();
        }
    }
    private static void requestScene() {
        request = PHASES[phase] + ":" + SCENES[scene];
        observing = "";
        PortalSmokeSupport.write("shadow-request.txt", request);
    }
    private static void verifyRuntime() throws ReflectiveOperationException {
        String lane = System.getenv("IP_SMOKE_RENDERER");
        boolean neoculus = lane.startsWith("neoculus");
        require(ModList.get().isLoaded("sable") == Boolean.parseBoolean(System.getenv().getOrDefault("IP_SMOKE_SABLE", "true")),
            "Wrong Sable runtime for shadow lane");
        require(ModList.get().isLoaded("embeddium") == neoculus && ModList.get().isLoaded("sodium") != neoculus
            && ModList.get().isLoaded("oculus") == neoculus, "Wrong backend for shadow lane " + lane);
        require(IrisInterface.invoker.isShaders() && "ip-shadow-fixture-v1".equals(IrisInterface.invoker.getShaderpackName()),
            "SHADOW_FIXTURE_NOT_ACTIVE");
        Object manager = Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
        Object pipeline = ((java.util.Optional<?>) manager.getClass().getMethod("getPipeline").invoke(manager)).orElse(null);
        require(pipeline != null && pipeline.getClass().getSimpleName().equals("IrisRenderingPipeline"), "Missing actual shader pipeline");
        require(IPCGlobal.renderer.getClass().getSimpleName().equals("IrisPortalRenderer"), "Shadow test used fallback renderer");
    }
    private static boolean capture(Minecraft mc) throws Exception {
        Map<String, Object> shadow = PortalShadowTestControl.evidence();
        Map<?, ?> depths = (Map<?, ?>) PortalClippingTestControl.evidence().get("innerWorldDepthStates");
        Map<?, ?> receiver = (Map<?, ?>) depths.get("minecraft:the_nether:1");
        require(request.equals(shadow.get("observation")) && ((Number) shadow.getOrDefault("observations", 0)).longValue() > 0,
            "No actual shadow pass captured for " + request);
        require(((Number) shadow.get("sample_count")).intValue() == 25 && ((Number) shadow.get("resolution")).intValue() == 256,
            "Wrong shadow-map storage/sample witness");
        require(receiver != null, "Missing retained receiver depth");
        double distance = receiver.get("viewDistance") instanceof Number value ? value.doubleValue() : Double.NaN;
        try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            double[] center = colors(pixels, 0.45, 0.45, 0.55, 0.55);
            double[] side = colors(pixels, 0.70, 0.48, 0.74, 0.52);
            boolean caster = SCENES[scene].equals("caster");
            double median = ((Number) shadow.get("median")).doubleValue();
            boolean accepted = PortalShadowOracle.accepts(caster, center[0], center[1], center[2], side[0], median,
                distance, Boolean.TRUE.equals(shadow.get("inherited_clipping_restored")))
                && RenderStates.portalsRenderedThisFrame > 0;
            String screenshot = PHASES[phase] + "-shadow-" + SCENES[scene] + ".png";
            pixels.writeToFile(PortalSmokeSupport.directory().resolve(screenshot));
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("phase", PHASES[phase]); check.put("scene", SCENES[scene]); check.put("screenshot", screenshot);
            check.put("width", pixels.getWidth()); check.put("height", pixels.getHeight());
            check.put("center_green", center[0]); check.put("center_blue", center[1]); check.put("center_red", center[2]);
            check.put("side_green", side[0]); check.put("receiver_distance", distance);
            check.put("shadow", shadow); check.put("accepted", accepted);
            lastProbe = check;
            stable = accepted ? stable + 1 : 0;
            if (frames > 720) throw new IllegalStateException("SHADOW_PIXELS_MISMATCH: " + PHASES[phase] + "/" + SCENES[scene] + " " + check);
            if (stable < 3) return false;
            require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error after shadow acceptance");
            checks.add(check); save(); return true;
        }
    }
    private static double[] colors(NativeImage pixels, double x0, double y0, double x1, double y1) {
        int green = 0, blue = 0, red = 0, count = 0;
        for (int y = (int) (pixels.getHeight() * y0); y < pixels.getHeight() * y1; y++) {
            for (int x = (int) (pixels.getWidth() * x0); x < pixels.getWidth() * x1; x++) {
                int color = pixels.getPixelRGBA(x, y);
                int r = color & 255, g = (color >>> 8) & 255, b = (color >>> 16) & 255;
                if (g > 80 && g > r * 2 && g > b * 2) green++;
                if (b > 80 && b > r * 2 && b > g * 2) blue++;
                if (r > 80 && r > g * 2 && r > b * 2) red++;
                count++;
            }
        }
        return new double[]{green / (double) count, blue / (double) count, red / (double) count};
    }
    private static void save() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checks", checks); report.put("last_probe", lastProbe);
        report.put("shader_control", PortalClippingTestControl.evidence());
        report.put("renderer", System.getenv("IP_SMOKE_RENDERER"));
        report.put("pack", IrisInterface.invoker.getShaderpackName());
        report.put("gl_version", GL11.glGetString(GL11.GL_VERSION));
        report.put("gl_renderer", GL11.glGetString(GL11.GL_RENDERER));
        report.put("mods", ModList.get().getMods().stream().map(mod -> Map.of("id", mod.getModId(), "version", mod.getVersion().toString())).toList());
        PortalSmokeSupport.write("shadow-evidence.json", new Gson().toJson(report));
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
