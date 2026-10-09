package qouteall.imm_ptl.core.gametest.sablee2e;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.server.level.BlockDestructionProgress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Actual framebuffer and live shader pipeline assertions; never packaged in releases. */
@EventBusSubscriber(modid = qouteall.imm_ptl.core.platform_specific.IPModEntry.MODID, value = Dist.CLIENT)
public final class PortalSmokeClient {
    private static boolean testControlInstalled;
    private static Map<String, Object> firstDestinationPose = Map.of();
    private static Map<String, Object> crossingCapturePose = Map.of();
    private static Map<String, Object> crossingSourcePose = Map.of();
    private static int destinationStops;
    private static boolean connecting, done, initialized, toggleDisabled, waitingToggle, crossing, crossed;
    private static int frames, stableFrames, epoch, sceneIndex;
    private static String request = "";
    private static String observingRequest = "";
    private static CompletableFuture<Void> reload;
    private static long frameStart, renderFrame;
    private static long sceneReadyNanos, sceneReadyMillis, firstCheckNanos;
    private static int firstCheckFrame, assertionAttempts;
    private static long nestedContextBaseline;
    private static Map<String, Long> nestedViewBaseline = Map.of();
    private static final List<String> NESTED_VIEW_COUNTERS = List.of(
        "sameRendererViewsVerified", "nullContextsVerified", "nonNullContextsVerified", "fallbackScopesVerified");
    private static int[] previousCreatePixels;
    private static int[] previousSourcePixels;
    private static int[] crumblingBaseline;
    private static int[] crumblingBackgroundBaseline;
    private static long crumblingGeneration;
    private static double[] crumblingCamera;
    private static PortalSmokeCrumblingOracle.Measurement crumblingMeasurement;
    private static Map<String, Object> crumblingEvidence = Map.of();
    private static int createMotionPixels, sourceMotionPixels, crumblingChangedPixels;
    private static double crumblingDarkening;
    private static double closestGeometryDistance = Double.POSITIVE_INFINITY;
    private static double previousCreateAngle = Double.NaN;
    private static boolean createServerMotion;
    private static Map<String, Object> createServerEvidence = Map.of();
    private static Object framebufferCopyEvidence = Map.of();
    private static final List<Particle> smokeParticles = new ArrayList<>();
    private static int particleTicks;
    private static int[] previousReference;
    private static final Map<String, int[]> backgroundReferences = new LinkedHashMap<>();
    private static final Map<String, int[]> visibleReferences = new LinkedHashMap<>();
    private static final Map<String, Double> backgroundDepths = new LinkedHashMap<>();
    private static final List<Long> consecutiveDepthFrames = new ArrayList<>();
    private static final List<Double> timings = new ArrayList<>();
    private static final List<Map<String, Object>> checks = new ArrayList<>();
    private static final String[] PHASES = {"before-reload", "after-reload", "after-toggle"};

    @SubscribeEvent
    public static void beforeTick(ClientTickEvent.Pre event) {
        if (PortalSmokeSupport.enabled() && !done) stopDestinationMotion(Minecraft.getInstance(), "tick-pre");
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!PortalSmokeSupport.enabled() || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (!testControlInstalled) {
            PortalClippingTestControl.install(System.getenv().getOrDefault("IP_SMOKE_NEGATIVE_CONTROL", "none"));
            testControlInstalled = true;
        }
        if (crossing && !crossed && mc.player != null && mc.level != null) {
            // Real client input exercises the portal handoff rather than a server teleport command.
            boolean ready = request.equals(PortalSmokeSupport.read("scene-ready.txt"));
            boolean source = mc.level.dimension().equals(Level.OVERWORLD) && mc.player.level().dimension().equals(Level.OVERWORLD);
            mc.options.keyUp.setDown(ready && source && firstDestinationPose.isEmpty());
            if (ready && source && crossingSourcePose.isEmpty()) {
                crossingSourcePose = crossingPose(mc, "source-input-start");
                PortalSmokeSupport.write("crossing-source.json", new Gson().toJson(crossingSourcePose));
            }
            stopDestinationMotion(mc, "tick-post");
        }
        if (initialized && mc.level != null && request.contains(":particle-")
            && request.equals(PortalSmokeSupport.read("scene-ready.txt"))) {
            try {
                tickParticleWitness(mc);
            } catch (Throwable error) {
                done = true;
                PortalSmokeSupport.write("client-fail.txt", "Particle witness setup failed: " + error);
                mc.stop();
                return;
            }
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
        stopDestinationMotion(Minecraft.getInstance(), "frame-pre");
        // Arm before inner-world rendering, consume in this frame's post event.
        // Keep the 120-frame warm-up and ten-frame assertion spacing unchanged.
        PortalClippingTestControl.beginFrame(++renderFrame, !done && initialized && !crossed
            && reload == null && !waitingToggle && observingRequest.equals(request)
            && PortalClippingTestControl.assertionFrame(frames + 1));
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
            // Teleportation may happen in rendering, after the last client-tick event.
            stopDestinationMotion(mc, "frame-post");
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
            if (!observingRequest.equals(request)) {
                PortalClippingTestControl.beginObservation(request);
                observingRequest = request;
                frames = stableFrames = 0;
                sceneReadyNanos = System.nanoTime();
                sceneReadyMillis = System.currentTimeMillis();
                firstCheckNanos = 0;
                firstCheckFrame = assertionAttempts = 0;
                com.mojang.logging.LogUtils.getLogger().info("Portal smoke ready: {} frame={}", request, renderFrame);
                if (request.endsWith(":create-nested")) {
                    Map<?, ?> flywheel = (Map<?, ?>) runtimeState().get("flywheel");
                    nestedContextBaseline = ((Number) flywheel.get("nestedContextsRestored")).longValue();
                    nestedViewBaseline = viewContextCounters(flywheel);
                }
                return;
            }
            if (!PortalClippingTestControl.assertionFrame(++frames)) return;
            if (firstCheckNanos == 0) {
                firstCheckNanos = System.nanoTime();
                firstCheckFrame = frames;
                com.mojang.logging.LogUtils.getLogger().info("Portal smoke first check: {} sceneFrame={} readyMs={}",
                    request, frames, (firstCheckNanos - sceneReadyNanos) / 1_000_000);
            }
            assertionAttempts++;
            verifyPipeline(); // Checked after every rebuild and scene, not just mod presence at startup.
            if (crossing) {
                if (!mc.level.dimension().equals(Level.NETHER) || !PortalSmokeSupport.exists("crossing-server-pass.txt")) {
                    if (frames > 1200) throw new IllegalStateException("CROSSING_TIMEOUT: player did not cross the portal");
                    return;
                }
                mc.options.keyUp.setDown(false);
                if (!crossed) {
                    verifyCrossingEndpoint(mc);
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
                firstDestinationPose = crossingCapturePose = crossingSourcePose = Map.of();
                destinationStops = 0;
                request = "after-crossing:crossing";
                frames = 0;
                consecutiveDepthFrames.clear();
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
        consecutiveDepthFrames.clear();
        smokeParticles.forEach(Particle::remove);
        smokeParticles.clear();
        particleTicks = 0;
        previousCreatePixels = null;
        previousSourcePixels = null;
        previousReference = null;
        closestGeometryDistance = Double.POSITIVE_INFINITY;
        createMotionPixels = sourceMotionPixels = crumblingChangedPixels = 0;
        crumblingDarkening = 0;
        crumblingMeasurement = null;
        crumblingEvidence = Map.of();
        if (PortalSmokeSupport.scenes().get(sceneIndex).equals("create-crumbling-clean")) crumblingBaseline = null;
        previousCreateAngle = Double.NaN;
        createServerMotion = false;
        createServerEvidence = Map.of();
        request = PHASES[epoch] + ":" + PortalSmokeSupport.scenes().get(sceneIndex);
        observingRequest = "";
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
                readCreateServerEvidence(crumbling, scene.endsWith("-background"));
                int[] sourceRegion = region(pixels, 0.82, 0.22, 0.98, 0.78);
                sourceMotionPixels += changes(previousSourcePixels, sourceRegion);
                previousSourcePixels = sourceRegion;
                int[] targetRegion = region(pixels, 0.38, 0.18, 0.64, 0.78);
                createMotionPixels += changes(previousCreatePixels, targetRegion);
                previousCreatePixels = targetRegion;
                if (crumbling) observeCrumbling(pixels, scene);
            }
            boolean positive = scene.endsWith("-visible") || scene.equals("create-nested") || scene.equals("create-crumbling-clean") || scene.equals("create-crumbling-damaged") || scene.equals("create-crumbling-restored");
            // Visible controls establish that each geometry path really draws in this camera.
            // Create uses its own textures; shader fixture programs for other paths output diagnostic red.
            boolean colors = positive ? (scene.startsWith("create-") ? green < total * 0.97 : red > total * 0.02)
                : green > total * 0.80 && red < total * 0.01;
            Map<String, Object> referenceWitness = Map.of();
            if (PortalSmokeSupport.activeShaders() && !PortalSmokeSupport.diagnosticFixture()) {
                String referenceScene = PortalSmokePixelRegions.referenceScene(scene);
                if (referenceScene != null) {
                    int[] region = region(pixels, 0.4, 0.4, 0.6, 0.6);
                    String key = phase + ":" + referenceScene;
                    String referenceScreenshot = phase + "-" + referenceScene + ".png";
                    if (scene.equals("solid-visible")) visibleReferences.put(phase, region);
                    if (scene.endsWith("-background")) {
                        double stability = previousReference == null ? Double.POSITIVE_INFINITY
                            : PortalSmokePixelRegions.difference(previousReference, region).meanAbsoluteError();
                        colors = stability <= 1.0 && meanBrightness(region) > 2.0;
                        previousReference = region;
                        backgroundReferences.put(key, region);
                        referenceWitness = Map.of("is_reference", true, "reference_scene", referenceScene,
                            "reference_screenshot", referenceScreenshot, "stability_mean_error", stability,
                            "mean_brightness", meanBrightness(region));
                    } else {
                        int[] background = backgroundReferences.get(key);
                        require(background != null, "Missing same-pack background control for " + scene);
                        PortalSmokePixelRegions.Difference difference = PortalSmokePixelRegions.difference(background, region);
                        boolean expectedMatch = PortalSmokePixelRegions.expectsBackground(scene);
                        colors = expectedMatch ? difference.matchesBackground() : difference.visiblyDifferent();
                        referenceWitness = Map.of("is_reference", false, "reference_scene", referenceScene,
                            "reference_screenshot", referenceScreenshot, "expected_match", expectedMatch,
                            "mean_absolute_error", difference.meanAbsoluteError(), "changed_fraction", difference.changedFraction(),
                            "sampled_pixels", difference.sampledPixels(), "actual_brightness", difference.actualBrightness());
                    }
                }
                if (referenceScene != null) {
                    PortalSmokePixelRegions.DepthTarget target = PortalSmokePixelRegions.depthTarget(referenceScene);
                    Map<?, ?> depth = depthState(target.key());
                    double viewDistance = depthNumber(depth, "medianViewDistance");
                    double minimumDistance = depthNumber(depth, "minimumViewDistance");
                    closestGeometryDistance = Math.min(closestGeometryDistance, minimumDistance);
                    String depthKey = phase + ":" + referenceScene;
                    if (scene.endsWith("-background")) backgroundDepths.put(depthKey, viewDistance);
                    double backgroundDistance = backgroundDepths.getOrDefault(depthKey, Double.NaN);
                    boolean validDepth = Double.isFinite(backgroundDistance)
                        && Math.abs(backgroundDistance - target.backdropDistance()) <= 0.5;
                    String expectation = PortalSmokePixelRegions.depthExpectation(scene);
                    validDepth &= switch (expectation) {
                        case "same" -> Math.abs(viewDistance - backgroundDistance) <= Math.max(0.1, backgroundDistance * 0.02);
                        case "moving-nearer" -> closestGeometryDistance < backgroundDistance - 0.5;
                        default -> viewDistance < backgroundDistance - 1.0;
                    };
                    colors &= validDepth;
                    referenceWitness = new LinkedHashMap<>(referenceWitness);
                    referenceWitness.put("depth_target", target.key());
                    referenceWitness.put("depth_expectation", expectation);
                    referenceWitness.put("view_distance", viewDistance);
                    referenceWitness.put("closest_view_distance", closestGeometryDistance);
                    referenceWitness.put("background_view_distance", backgroundDistance);
                    referenceWitness.put("depth_samples", depthSampleCount(depth));
                    referenceWitness.put("depth_observations", depth.get("observationCount"));
                    referenceWitness.put("depth_frame", depth.get("frame"));
                    referenceWitness.put("depth_observation", depth.get("observation"));
                }
                if (scene.equals("crossing")) {
                    int[] backdrop = backgroundReferences.get(PHASES[epoch] + ":solid-background");
                    int[] visible = visibleReferences.get(PHASES[epoch]);
                    require(backdrop != null && visible != null, "Missing destination palette references before crossing");
                    double[] backgroundColor = PortalSmokePixelRegions.meanColor(backdrop);
                    double[] visibleColor = PortalSmokePixelRegions.meanColor(visible);
                    double[] actualColor = PortalSmokePixelRegions.meanColor(region(pixels, 0.4, 0.4, 0.6, 0.6));
                    colors = PortalSmokePixelRegions.matchesCrossingPalette(backgroundColor, visibleColor, actualColor);
                    Map<?, ?> depth = depthState("minecraft:the_nether:0");
                    double nativeDistance = depthNumber(depth, "medianViewDistance");
                    Object camera = depth.get("camera");
                    double cameraZ = ((Number) java.lang.reflect.Array.get(camera, 2)).doubleValue();
                    double expectedDistance = cameraZ + 3.0; // Positive-Z face of the destination wall at block z=-4.
                    colors &= expectedDistance > 0 && Math.abs(nativeDistance - expectedDistance) <= Math.max(0.1, expectedDistance * 0.02);
                    referenceWitness = new LinkedHashMap<>(Map.of("crossing_palette", true,
                        "background_color", backgroundColor, "visible_color", visibleColor, "actual_color", actualColor,
                        "background_distance", PortalSmokePixelRegions.chromaticDistance(actualColor, backgroundColor),
                        "visible_distance", PortalSmokePixelRegions.chromaticDistance(actualColor, visibleColor)));
                    referenceWitness.put("native_view_distance", nativeDistance);
                    referenceWitness.put("expected_native_distance", expectedDistance);
                    referenceWitness.put("native_camera", camera);
                    referenceWitness.put("depth_samples", depthSampleCount(depth));
                    referenceWitness.put("depth_observations", depth.get("observationCount"));
                    referenceWitness.put("depth_frame", depth.get("frame"));
                    referenceWitness.put("depth_observation", depth.get("observation"));
                }
            }
            Map<String, Object> fragmentWitness = Map.of();
            if (scene.startsWith("entity-") || scene.startsWith("particle-")) {
                boolean clipped = scene.endsWith("-clipped");
                double[] excluded = PortalSmokePixelRegions.controlRegion(scene);
                double[] retained = PortalSmokePixelRegions.retainedRegion();
                Map<String, Object> excludedSample = sampleColors(pixels, excluded);
                Map<String, Object> retainedSample = sampleColors(pixels, retained);
                colors = clipped ? ((double) excludedSample.get("green_fraction") > 0.80
                    && (double) excludedSample.get("red_fraction") < 0.01
                    && (double) retainedSample.get("red_fraction") > 0.10)
                    : (double) excludedSample.get("red_fraction") > 0.10;
                String targetPath = scene.startsWith("entity-") ? "entity" : "particle";
                boolean expectClipping = !(targetPath + "-clipping-disabled").equals(System.getenv("IP_SMOKE_NEGATIVE_CONTROL"));
                // Require a completed portal draw of the resolved shader before accepting
                // either the positive pixels or the expected targeted-negative failure.
                PortalClippingTestControl.assertTargetDrawn(targetPath, expectClipping);
                Map<String, Object> cpuWitness = verifyCpuWitness(scene);
                fragmentWitness = Map.of("camera", List.of(4, 82, 4), "yaw", 135,
                    "origin_z", clipped ? (scene.startsWith("particle-") ? -0.75 : -0.25) : -6.0,
                    "cpu_origin_retained", cpuWitness.get("accepted"), "cpu", cpuWitness,
                    "excluded", excludedSample, "retained", retainedSample,
                    "active_particles", smokeParticles.stream().filter(Particle::isAlive).count());
            }
            int layers = RenderStates.portalRenderInfos.stream().mapToInt(List::size).max().orElse(0);
            boolean geometry = (!requirePortal || RenderStates.portalsRenderedThisFrame > 0)
                && (!(scene.startsWith("nested") || scene.startsWith("create-nested")) || layers >= 2)
                && (!scene.startsWith("create-")
                    || Boolean.TRUE.equals(((Map<?, ?>) createServerEvidence.get("clientSpawnData")).get("ready")))
                && (!scene.startsWith("create-") || !requiresSourcePixels() || sourceMotionPixels > 20)
                && (!(scene.equals("create-visible") || scene.equals("create-nested")) || (createMotionPixels > 20 && createServerMotion))
                && (!scene.equals("create-crumbling-damaged") || crumblingMeasurement != null && crumblingMeasurement.showsDamage())
                && (!scene.equals("create-crumbling-restored") || crumblingMeasurement != null && crumblingMeasurement.restored());
            String screenshot = phase + "-" + scene + ".png";
            pixels.writeToFile(PortalSmokeSupport.directory().resolve(screenshot));
            stableFrames = colors && geometry ? stableFrames + 1 : 0;
            if (stableFrames == 0) consecutiveDepthFrames.clear();
            else if (PortalSmokeSupport.activeShaders() && !PortalSmokeSupport.diagnosticFixture()) {
                consecutiveDepthFrames.add(renderFrame);
            }
            if (frames > 720) throw new IllegalStateException("PORTAL_PIXELS_MISMATCH: " + phase + "/" + scene
                + " green=" + green + "/" + total + " red=" + red + " layers=" + layers
                + " targetMotion=" + createMotionPixels + " sourceMotion=" + sourceMotionPixels
                + " spawnData=" + createServerEvidence.get("clientSpawnData")
                + " crumblingChanges=" + crumblingChangedPixels + " darkening=" + crumblingDarkening
                + " straddling=" + fragmentWitness + " reference=" + referenceWitness + " crumbling=" + crumblingEvidence);
            if (stableFrames < 3 && requirePortal) return false;
            if (!colors) throw new IllegalStateException("PORTAL_PIXELS_MISMATCH after crossing");
            int glError = GL11.glGetError();
            require(glError == GL11.GL_NO_ERROR, "OpenGL error after portal scene: 0x" + Integer.toHexString(glError));
            Map<String, Object> check = runtimeState();
            check.putAll(Map.of("phase", phase, "scene", scene, "screenshot", screenshot,
                "green_pixels", green, "red_pixels", red, "sampled_pixels", total,
                "portal_layers", layers, "width", pixels.getWidth(), "height", pixels.getHeight()));
            check.put("render_frame", renderFrame);
            check.put("consecutive_depth_frames", List.copyOf(consecutiveDepthFrames));
            check.put("scene_timing", Map.of("ready_unix_ms", sceneReadyMillis,
                "first_check_ms", (firstCheckNanos - sceneReadyNanos) / 1_000_000.0,
                "pass_ms", (System.nanoTime() - sceneReadyNanos) / 1_000_000.0,
                "first_check_frame", firstCheckFrame, "pass_frame", frames,
                "assertion_attempts", assertionAttempts, "consecutive_passes", stableFrames));
            check.put("reference_witness", referenceWitness);
            if (scene.equals("crossing")) check.put("crossing_motion", Map.of(
                "source", crossingSourcePose, "first_destination", firstDestinationPose,
                "capture", crossingCapturePose, "velocity_stops", destinationStops));
            check.put("straddling_witness", fragmentWitness);
            check.put("shader_path", PortalClippingTestControl.evidence());
            if (scene.startsWith("mirror")) check.put("mirror_observer", verifyMirrorObserverIsolation(mc));
            check.put("create_motion_changed_pixels", createMotionPixels);
            check.put("source_motion_changed_pixels", sourceMotionPixels);
            check.put("source_visible_required", requiresSourcePixels());
            check.put("target_motion_region", List.of(0.38, 0.18, 0.64, 0.78));
            check.put("source_motion_region", List.of(0.82, 0.22, 0.98, 0.78));
            check.put("crumbling_changed_pixels", crumblingChangedPixels);
            check.put("crumbling_darkening", crumblingDarkening);
            check.put("crumbling_oracle", crumblingEvidence);
            if (scene.startsWith("create-")) {
                verifyFlywheel(check.get("flywheel"));
                if (scene.equals("create-nested")) {
                    Map<?, ?> flywheel = (Map<?, ?>) check.get("flywheel");
                    long restored = ((Number) flywheel.get("nestedContextsRestored")).longValue() - nestedContextBaseline;
                    check.put("nested_contexts_restored_this_scene", restored);
                    boolean deferred = check.get("renderer").equals("IrisPortalRenderer")
                        || check.get("renderer").equals("IrisCompatibilityPortalRenderer");
                    check.put("nested_context_verification", deferred ? "deferred-null-view" : "overlapping-live-context");
                    if (deferred) {
                        Map<String, Long> observed = new LinkedHashMap<>(viewContextCounters(flywheel));
                        observed.replaceAll((key, value) -> value - nestedViewBaseline.get(key));
                        long views = observed.get("sameRendererViewsVerified");
                        require(views > 0 && observed.get("nullContextsVerified") == views
                            && observed.get("nonNullContextsVerified") == 0
                            && observed.get("fallbackScopesVerified") == views,
                            "Deferred same-renderer Flywheel context/scope restoration was not verified in this scene: " + observed);
                        check.put("nested_view_contexts_this_scene", observed);
                    } else {
                        require(restored > 0, "Same-dimension nested Flywheel context restoration never ran in this scene");
                    }
                }
                check.put("create_server", createServerEvidence);
                check.put("create_server_motion", createServerMotion);
            }
            com.mojang.logging.LogUtils.getLogger().info("Portal smoke passed: {} sceneFrame={} readyMs={} attempts={}",
                request, frames, (System.nanoTime() - sceneReadyNanos) / 1_000_000, assertionAttempts);
            checks.add(check);
            saveEvidence();
            return true;
        }
    }

    @SuppressWarnings("unchecked")
    private static void observeCrumbling(NativeImage image, String scene) throws ReflectiveOperationException {
        Minecraft mc = Minecraft.getInstance();
        Map<String, Object> control = (Map<String, Object>) createServerEvidence.get("crumbling_control");
        int expectedStage = scene.equals("create-crumbling-damaged") || scene.equals("create-crumbling-clipped") ? 9 : -1;
        int z = scene.endsWith("-clipped") ? 3 : -1;
        require(control != null && ((Number) control.get("stage")).intValue() == expectedStage
            && Boolean.TRUE.equals(control.get("packet_sent"))
            && mc.player.getUUID().toString().equals(control.get("observer"))
            && "create:large_cogwheel".equals(control.get("block")), "Wrong server damage control: " + control);
        List<Number> position = (List<Number>) control.get("position");
        require(position.get(0).intValue() == 0 && position.get(1).intValue() == 82 && position.get(2).intValue() == z,
            "Damage packet targeted the wrong cog: " + position);
        var field = LevelRenderer.class.getDeclaredField("destroyingBlocks");
        field.setAccessible(true);
        Map<?, ?> damage = (Map<?, ?>) field.get(ClientWorldLoader.getWorldRenderer(Level.NETHER));
        Object progress = damage.get(78231);
        int actualStage = progress instanceof BlockDestructionProgress block ? block.getProgress() : -1;
        require(actualStage == expectedStage && (actualStage == -1
            || ((BlockDestructionProgress) progress).getPos().equals(new BlockPos(0, 82, z))),
            "Client did not observe the expected cog damage stage: " + actualStage);
        long generation = ((Number) control.get("fixture_generation")).longValue();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double[] pose = {camera.x, camera.y, camera.z, mc.gameRenderer.getMainCamera().getYRot(), mc.gameRenderer.getMainCamera().getXRot()};
        int[] face = cogMask(image, false), background = cogMask(image, true);
        if (scene.equals("create-crumbling-clean")) {
            crumblingBaseline = face;
            crumblingBackgroundBaseline = background;
            crumblingGeneration = generation;
            crumblingCamera = pose;
        }
        boolean followsBaseline = scene.equals("create-crumbling-damaged") || scene.equals("create-crumbling-restored");
        boolean sameCamera = crumblingCamera != null;
        if (sameCamera) for (int i = 0; i < pose.length; i++) sameCamera &= Math.abs(pose[i] - crumblingCamera[i]) < 0.0001;
        if (followsBaseline) {
            require(generation == crumblingGeneration && sameCamera, "Crumbling base fixture or camera changed");
            require(crumblingBaseline != null && crumblingBackgroundBaseline != null, "Missing clean cog control");
            crumblingMeasurement = PortalSmokeCrumblingOracle.compare(crumblingBaseline, face, crumblingBackgroundBaseline, background);
            crumblingChangedPixels = crumblingMeasurement.darkenedPixels() + crumblingMeasurement.brightenedPixels();
            crumblingDarkening = crumblingMeasurement.meanDarkening();
        }
        crumblingEvidence = new LinkedHashMap<>(control);
        crumblingEvidence.put("client_stage", actualStage);
        crumblingEvidence.put("fixture_unchanged", generation == crumblingGeneration);
        crumblingEvidence.put("camera_unchanged", sameCamera);
        crumblingEvidence.put("camera", pose);
        crumblingEvidence.put("face_samples", face.length);
        crumblingEvidence.put("background_samples", background.length);
        crumblingEvidence.put("mask", "cog-face-r0.13h-background-annulus-r0.18h-0.23h");
        if (crumblingMeasurement != null) crumblingEvidence.put("measurement", crumblingMeasurement);
    }

    private static int[] cogMask(NativeImage image, boolean background) {
        int width = image.getWidth(), height = image.getHeight();
        int[] result = new int[height * height / 4 + height * 2];
        int count = 0;
        int x0 = Math.max(0, (int) (width * 0.5 - height * 0.16));
        int x1 = Math.min(width, (int) Math.ceil(width * 0.5 + height * 0.32));
        int y0 = Math.max(0, (int) (height * 0.18)), y1 = Math.min(height, (int) Math.ceil(height * 0.66));
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            boolean selected = background ? PortalSmokeCrumblingOracle.backgroundPixel(x, y, width, height)
                : PortalSmokeCrumblingOracle.facePixel(x, y, width, height);
            if (selected) result[count++] = image.getPixelRGBA(x, y);
        }
        return Arrays.copyOf(result, count);
    }

    private static void stopDestinationMotion(Minecraft mc, String hook) {
        if (!crossing || mc.player == null || mc.level == null
            || !mc.level.dimension().equals(Level.NETHER) || !mc.player.level().dimension().equals(Level.NETHER)) return;
        if (firstDestinationPose.isEmpty()) {
            // Observe the real handoff before changing velocity. Never set position,
            // replace the player, or use a server teleport to satisfy this test.
            firstDestinationPose = crossingPose(mc, hook);
            PortalSmokeSupport.write("crossing-arrival.json", new Gson().toJson(firstDestinationPose));
            frames = stableFrames = 0; // Settle in the destination, not during the approach.
        }
        mc.options.keyUp.setDown(false);
        mc.player.setDeltaMovement(Vec3.ZERO);
        destinationStops++;
    }

    private static Map<String, Object> crossingPose(Minecraft mc, String hook) {
        Vec3 eye = mc.player.getEyePosition(), camera = mc.gameRenderer.getMainCamera().getPosition();
        Vec3 velocity = mc.player.getDeltaMovement();
        BlockPos wall = new BlockPos(0, 82, -4);
        return Map.ofEntries(Map.entry("hook", hook), Map.entry("player_uuid", mc.player.getUUID().toString()),
            Map.entry("dimension", mc.level.dimension().location().toString()),
            Map.entry("player_dimension", mc.player.level().dimension().location().toString()),
            Map.entry("position", List.of(mc.player.getX(), mc.player.getY(), mc.player.getZ())),
            Map.entry("eye", List.of(eye.x, eye.y, eye.z)), Map.entry("camera", List.of(camera.x, camera.y, camera.z)),
            Map.entry("velocity", List.of(velocity.x, velocity.y, velocity.z)), Map.entry("yaw", mc.player.getYRot()),
            Map.entry("pitch", mc.player.getXRot()), Map.entry("flying", mc.player.getAbilities().flying),
            Map.entry("forward_input", mc.options.keyUp.isDown()),
            Map.entry("tick", mc.player.tickCount), Map.entry("wall_chunk_loaded", mc.level.hasChunkAt(wall)),
            Map.entry("wall_block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(wall).getBlock()).toString()));
    }

    @SuppressWarnings("unchecked")
    private static void verifyCrossingEndpoint(Minecraft mc) {
        crossingCapturePose = crossingPose(mc, "framebuffer-capture");
        Map<String, Object> server = new Gson().fromJson(PortalSmokeSupport.read("crossing-server-evidence.json"), Map.class);
        Map<String, Object> complete = new LinkedHashMap<>(crossingCapturePose);
        complete.put("server", server == null ? Map.of() : server);
        crossingCapturePose = complete;
        // Write before asserting so an endpoint or native-chunk failure remains diagnosable.
        PortalSmokeSupport.write("crossing-capture.json", new Gson().toJson(crossingCapturePose));
        require(!firstDestinationPose.isEmpty() && !crossingSourcePose.isEmpty(), "Crossing ownership transition was not observed");
        List<Number> firstEye = (List<Number>) firstDestinationPose.get("eye");
        require(PortalSmokeCrossingBounds.contains(firstEye.get(0).doubleValue(), firstEye.get(1).doubleValue(), firstEye.get(2).doubleValue()),
            "CROSSING_INITIAL_DESTINATION_OUT_OF_BOUNDS: " + firstDestinationPose);
        Vec3 eye = mc.player.getEyePosition(), camera = mc.gameRenderer.getMainCamera().getPosition();
        require(PortalSmokeCrossingBounds.contains(eye.x, eye.y, eye.z)
            && PortalSmokeCrossingBounds.contains(camera.x, camera.y, camera.z),
            "CROSSING_ENDPOINT_OUT_OF_BOUNDS: " + crossingCapturePose);
        require(mc.player.getDeltaMovement().lengthSqr() < 1.0e-12,
            "Crossing observer still had spectator momentum: " + crossingCapturePose);
        require(mc.level.hasChunkAt(new BlockPos(0, 82, -4))
            && mc.level.getBlockState(new BlockPos(0, 82, -4)).is(Blocks.LIME_CONCRETE),
            "CROSSING_NATIVE_WALL_MISSING: " + crossingCapturePose);
        require(server != null && server.get("current") instanceof Map<?, ?>, "Missing authoritative crossing pose");
        Map<?, ?> current = (Map<?, ?>) server.get("current");
        List<Number> serverEye = (List<Number>) current.get("eye");
        require(mc.player.getUUID().toString().equals(current.get("player_uuid"))
            && "minecraft:the_nether".equals(current.get("dimension"))
            && PortalSmokeCrossingBounds.contains(serverEye.get(0).doubleValue(), serverEye.get(1).doubleValue(), serverEye.get(2).doubleValue()),
            "Server crossing endpoint disagreed with client: " + server);
    }

    private static Map<?, ?> depthState(String key) {
        Map<?, ?> state = PortalClippingTestControl.requireCurrentDepth(key);
        require(state != null && depthSampleCount(state) == 81
            && state.get("observationCount") instanceof Number observations && observations.intValue() > 0,
            "Missing fresh 9x9 depth witness for " + key);
        return state;
    }

    private static int depthSampleCount(Map<?, ?> state) {
        Object samples = state.get("depthSamples");
        return samples instanceof List<?> list ? list.size()
            : samples != null && samples.getClass().isArray() ? java.lang.reflect.Array.getLength(samples) : 0;
    }

    private static double depthNumber(Map<?, ?> state, String name) {
        Object value = state.get(name);
        require(value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() > 0,
            "Invalid live depth field " + name + ": " + value);
        return ((Number) value).doubleValue();
    }

    private static Map<String, Object> verifyCpuWitness(String scene) {
        Portal portal = null;
        for (Entity entity : Minecraft.getInstance().level.entitiesForRendering()) {
            if (entity instanceof Portal candidate && candidate.getDestDim().equals(Level.NETHER)) {
                portal = candidate;
                break;
            }
        }
        require(portal != null, "No portal found for CPU-culling witness");
        List<List<Double>> centers = new ArrayList<>();
        boolean accepted = true;
        if (scene.startsWith("entity-")) {
            for (Entity entity : ClientWorldLoader.getWorld(Level.NETHER).entitiesForRendering()) {
                if (!entity.getUUID().toString().equals(PortalSmokeSupport.read("entity-panel-id.txt"))) continue;
                Vec3 center = CrossPortalEntityRenderer.getRenderingCameraPos(entity);
                centers.add(List.of(center.x, center.y, center.z));
                accepted &= portal.isOnDestinationSide(center, -0.01);
            }
        } else {
            for (Particle particle : smokeParticles) {
                if (!particle.isAlive()) continue;
                Vec3 center = particle.getBoundingBox().getCenter();
                centers.add(List.of(center.x, center.y, center.z));
                accepted &= portal.isOnDestinationSide(center, 0.5)
                    && ((IEParticle) particle).portal_getWorld().dimension().equals(Level.NETHER);
            }
        }
        require(!centers.isEmpty() && accepted, "CPU gate rejected the shader clipping witness: " + centers);
        return Map.of("accepted", accepted, "centers", centers,
            "plane_tolerance", scene.startsWith("entity-") ? -0.01 : 0.5);
    }

    private static void tickParticleWitness(Minecraft mc) {
        double z = request.endsWith("-visible") ? -6.0 : -0.75;
        smokeParticles.removeIf(particle -> !particle.isAlive());
        for (Particle particle : smokeParticles) {
            particle.setParticleSpeed(0, 0, 0);
            particle.setPos(0, 82, z);
        }
        if (particleTicks++ % 20 != 0) return;
        ClientWorldLoader.withSwitchedWorld(ClientWorldLoader.getWorld(Level.NETHER), () -> {
            for (int i = 0; i < 16; i++) {
                Particle particle = mc.particleEngine.createParticle(new DustParticleOptions(new Vector3f(1, 0, 0), 4),
                    0, 82, z, 0, 0, 0);
                require(particle != null, "Dust particle factory returned no witness");
                particle.scale(10);
                particle.setParticleSpeed(0, 0, 0);
                particle.setLifetime(60);
                smokeParticles.add(particle);
            }
        });
    }

    private static Map<String, Object> sampleColors(NativeImage image, double[] bounds) {
        int[] pixels = region(image, bounds[0], bounds[1], bounds[2], bounds[3]);
        int green = 0, red = 0;
        for (int color : pixels) {
            int r = color & 255, g = (color >>> 8) & 255, b = (color >>> 16) & 255;
            if (g >= 20 && g >= r + 4 && g >= b * 2) green++;
            if (r >= 30 && r > g * 1.3 && r > b * 1.3) red++;
        }
        return Map.of("region", List.of(bounds[0], bounds[1], bounds[2], bounds[3]),
            "green_fraction", green / (double) pixels.length, "red_fraction", red / (double) pixels.length,
            "sampled_pixels", pixels.length);
    }

    private static Map<String, Object> verifyMirrorObserverIsolation(Minecraft mc) {
        int mirrorCount = 0;
        boolean rendersPlayers = false;
        String mirrorId = "";
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Mirror mirror && !mirror.isRemoved()) {
                mirrorCount++;
                rendersPlayers |= mirror.getDoRenderPlayer();
                mirrorId = mirror.getUUID().toString();
            }
        }
        int playerCount = mc.level.players().size();
        PortalSmokeMirrorGeometry.requireIsolatedObserver(
            mc.player.isSpectator(), playerCount, mirrorCount, rendersPlayers
        );
        return Map.of("spectator", mc.player.isSpectator(), "player_count", playerCount,
            "observer_uuid", mc.player.getUUID().toString(), "mirror_count", mirrorCount,
            "mirror_uuid", mirrorId, "mirror_renders_players", rendersPlayers,
            "global_render_yourself", IPGlobal.renderYourselfInPortal,
            "scope", "disposable-mirror-only");
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
        if (System.getenv().getOrDefault("IP_SMOKE_GL_CONTEXT", "default").equals("no-copy-image")) {
            require(Boolean.FALSE.equals(actual.get("copy_image_available"))
                && actual.get("gl_version").toString().startsWith("3.3")
                && !Boolean.TRUE.equals(actual.get("forced_framebuffer_blit")),
                "GL3.3_CAPABILITY_OVERRIDE_NOT_APPLIED: " + actual);
        }
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
        state.put("requested_gl_context", System.getenv().getOrDefault("IP_SMOKE_GL_CONTEXT", "default"));
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
    private static void readCreateServerEvidence(boolean crumbling, boolean background) throws ReflectiveOperationException {
        Map<String, Object> result = new Gson().fromJson(PortalSmokeSupport.read("create-server.json"), Map.class);
        require(result != null && request.equals(result.get("scene")), "Missing current Create server evidence");
        for (String side : List.of("source", "target")) {
            Map<String, Object> assembly = (Map<String, Object>) result.get(side);
            if (background && side.equals("target")) {
                require(((Number) assembly.get("shaftSpeed")).doubleValue() == 0
                    && ((Number) assembly.get("contraptionCount")).intValue() == 0,
                    "Background control unexpectedly contained Create geometry: " + assembly);
                continue;
            }
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
        Class<?> helper = Class.forName("qouteall.imm_ptl.core.gametest.sablee2e.PortalSmokeCreateScene");
        result.put("clientSpawnData", helper.getMethod("describeClientContraptions", Map.class).invoke(null, result));
        createServerEvidence = result;
    }

    private static void verifyFlywheel(Object evidence) {
        Map<?, ?> backend = (Map<?, ?>) evidence;
        require(Boolean.TRUE.equals(backend.get("contextAccessorsInstalled")), "Live Flywheel context accessors were not installed");
        require(Boolean.TRUE.equals(backend.get("contextClearedAfterFrame")), "Flywheel render context leaked after frame");
        Map<?, ?> witness = (Map<?, ?>) backend.get("viewContextWitness");
        require(((Number) witness.get("openViews")).intValue() == 0, "Flywheel view witness stack leaked after frame");
        if (PortalSmokeSupport.activeShaders()) {
            require(((Number) witness.get("observedViews")).longValue() > 0, "Live Flywheel view witness was not installed");
        }
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

    private static Map<String, Long> viewContextCounters(Map<?, ?> flywheel) {
        Map<?, ?> witness = (Map<?, ?>) flywheel.get("viewContextWitness");
        Map<String, Long> counters = new LinkedHashMap<>();
        for (String key : NESTED_VIEW_COUNTERS) counters.put(key, ((Number) witness.get(key)).longValue());
        return counters;
    }

    private static void toggleShaders(boolean enabled) throws ReflectiveOperationException {
        Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
        // The upstream action saves the setting before reload() re-reads it from disk.
        // Changing the in-memory config alone would silently restore the old setting.
        iris.getMethod("toggleShaders", Minecraft.class, boolean.class).invoke(null, Minecraft.getInstance(), enabled);
    }

    private static void saveEvidence() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("checks", checks);
        evidence.put("crossing_motion", Map.of("source", crossingSourcePose, "first_destination", firstDestinationPose,
            "capture", crossingCapturePose, "velocity_stops", destinationStops));
        evidence.put("shader_control", PortalClippingTestControl.evidence());
        evidence.put("framebuffer_copy", framebufferCopyEvidence);
        evidence.put("flywheel_view_context_witness", PortalSmokeRenderContextWitness.LIVE.snapshot());
        evidence.put("render_mode", System.getenv().getOrDefault("IP_SMOKE_RENDER_MODE", "normal"));
        evidence.put("required_scenes", PortalSmokeSupport.scenes());
        evidence.put("toggle_disabled_verified", toggleDisabled);
        evidence.put("scene_state", PortalSmokeSceneWitness.capture(request));
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
