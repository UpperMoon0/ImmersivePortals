package qouteall.imm_ptl.core.gametest.sablee2e;

import com.mojang.blaze3d.platform.NativeImage;
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
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Checks actual framebuffer pixels before and after resource/shader reload. */
@EventBusSubscriber(modid = qouteall.imm_ptl.core.platform_specific.IPModEntry.MODID, value = Dist.CLIENT)
public final class PortalSmokeClient {
    private static boolean connecting;
    private static boolean done;
    private static int frames;
    private static int greenFrames;
    private static boolean reloaded;
    private static CompletableFuture<Void> reload;
    private static long frameStart;
    private static final List<Double> timings = new ArrayList<>();

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!PortalSmokeSupport.enabled() || connecting || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null) return;
        connecting = true;
        String address = "127.0.0.1:" + System.getenv("IP_SABLE_E2E_PORT");
        ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
            new ServerData("Portal visual regression", address, ServerData.Type.OTHER), true, null);
    }

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (PortalSmokeSupport.enabled()) frameStart = System.nanoTime();
    }

    @SubscribeEvent
    public static void afterFrame(RenderFrameEvent.Post event) {
        if (!PortalSmokeSupport.enabled() || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay() != null) return;
        try {
            mc.options.hideGui = true;
            if (++frames < 120) return; // Let initial chunks, lighting and shaders settle.
            String renderer = System.getenv().getOrDefault("IP_SMOKE_RENDERER", "sodium");
            if (renderer.equals("vanilla") ? ModList.get().isLoaded("sodium") : !ModList.get().isLoaded(renderer.equals("sodium") ? "sodium" : renderer)) {
                throw new IllegalStateException("Requested renderer is not the loaded runtime: " + renderer);
            }
            if (reload != null) {
                if (!reload.isDone()) return;
                reload.join(); // A failed reload must fail the test.
                reload = null;
                reloaded = true;
                frames = 0;
                return;
            }
            if (!PortalSmokeSupport.exists("visual-pass.txt")) {
                if (frames % 10 != 0) return;
                try (NativeImage pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                    int green = 0;
                    int total = 0;
                    for (int x = pixels.getWidth() * 2 / 5; x < pixels.getWidth() * 3 / 5; x++) {
                        for (int y = pixels.getHeight() * 2 / 5; y < pixels.getHeight() * 3 / 5; y++) {
                            int color = pixels.getPixelRGBA(x, y);
                            int r = color & 255, g = (color >>> 8) & 255, b = (color >>> 16) & 255;
                            if (g > 35 && g > r * 1.35 && g > b * 1.35) green++;
                            total++;
                        }
                    }
                    boolean good = RenderStates.portalsRenderedThisFrame > 0 && green > total * 0.8;
                    greenFrames = good ? greenFrames + 1 : 0;
                    pixels.writeToFile(PortalSmokeSupport.directory().resolve(reloaded ? "portal-after-reload.png" : "portal-before-reload.png"));
                    if (frames > 1200) throw new IllegalStateException("Portal pixels never matched destination; green=" + green + "/" + total);
                    if (greenFrames < 3) return;
                    greenFrames = 0;
                    if (!reloaded) {
                        reload = mc.reloadResourcePacks();
                        return;
                    }
                    PortalSmokeSupport.write("visual-pass.txt", "Green destination visible through rendered portal before and after shader reload\n");
                }
            }
            if (timings.size() < PortalSmokeSupport.samples()) {
                timings.add((System.nanoTime() - frameStart) / 1_000_000.0);
            }
            if (timings.size() >= PortalSmokeSupport.samples() && PortalSmokeSupport.exists("server-pass.txt")) {
                PortalSmokeSupport.metrics("client", timings);
                PortalSmokeSupport.write("client-pass.txt", "Portal pixels, resource reload and live frame measurements passed\n");
                done = true;
                mc.stop();
            }
        } catch (Throwable e) {
            done = true;
            PortalSmokeSupport.write("client-fail.txt", e.toString());
            mc.stop();
        }
    }
}
