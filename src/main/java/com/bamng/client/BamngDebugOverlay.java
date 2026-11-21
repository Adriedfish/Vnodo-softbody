package com.bamng.client;

import com.bamng.BamngMod;
import com.bamng.core.VehicleBody;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/** F2: live text funnel, kept off the hot path until a body exists. */
@Mod.EventBusSubscriber(modid = BamngMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BamngDebugOverlay {
    private static final List<Component> LINES = new ArrayList<>();
    private static long lastRebuild = Long.MIN_VALUE;
    private static int cachedY = 64;

    private BamngDebugOverlay() {
    }

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        if (!com.bamng.core.VnodoConfig.debugHud()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        long gameTime = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        if (LINES.isEmpty() || gameTime - lastRebuild >= 5L) {
            lastRebuild = gameTime;
            rebuild(minecraft);
        }
        int y = cachedY;
        for (Component line : LINES) {
            event.getGuiGraphics().drawString(minecraft.font, line, 6, y, 0xFFE8E8F0, true);
            y += 11;
        }
    }

    private static String modVersion() {
        try {
            return net.minecraftforge.fml.ModList.get()
                    .getModContainerById(BamngMod.MODID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("dev");
        } catch (RuntimeException error) {
            return "dev";
        }
    }

    private static void rebuild(Minecraft minecraft) {
        LINES.clear();
        var bodies = BamngVetturaPhysicsClient.activeBodies();
        if (bodies.isEmpty()) {
            return;
        }
        LINES.add(Component.literal("Vnodo " + modVersion() + "  soft-surface"));
        for (VehicleBody body : bodies) {
            var world = body.world;
            String line = String.format(java.util.Locale.ROOT,
                    "nodes %d  tri %d  cont %d  triC %d  pen %.3f  dent %.3f  step %.1fms  iso %s",
                    world.nodes.size(), world.triangles.size(), world.lastContacts,
                    world.lastTriangleContacts, world.maxPenetration, world.plasticDepth(),
                    world.lastStepNanos / 1_000_000.0, world.isolated);
            LINES.add(Component.literal(line));
            String tire = String.format(java.util.Locale.ROOT,
                    "tires %d  p %.0f kPa  vol %.3f  closed %s",
                    body.tires.size(), body.tires.isEmpty() ? 0.0 : body.tires.get(0).chamber.gaugePressure / 1000.0,
                    body.tires.stream().mapToDouble(t -> t.chamber.volume).sum(),
                    body.tires.stream().allMatch(t -> t.chamber.closed));
            LINES.add(Component.literal(tire));
            int dentNodes = 0;
            for (var node : world.nodes) {
                if (node.plasticX * node.plasticX + node.plasticY * node.plasticY
                        + node.plasticZ * node.plasticZ > 0.0004) {
                    dentNodes++;
                }
            }
            String deformation = String.format(java.util.Locale.ROOT,
                    "dentNodes %d  brokenBeams %d  brokenTris %d",
                    dentNodes, world.damage.brokenBeamCount(), world.damage.brokenTriangleCount());
            LINES.add(Component.literal(deformation));
            String crush = String.format(java.util.Locale.ROOT,
                    "crush f %.3f  r %.3f  lim %.3f/%.3f",
                    BamngRenderState.lastFrontDepth(), BamngRenderState.lastRearDepth(),
                    world.frontPlasticLimit, world.rearPlasticLimit);
            LINES.add(Component.literal(crush));
            String skin = String.format(java.util.Locale.ROOT,
                    "skinV %d  skinMax %.3f  near %.2f/%.2f  rN %d/%d",
                    BamngRenderState.appliedVertices(), BamngRenderState.appliedMax(),
                    BamngPickupRenderer.lastNearAvg(), BamngPickupRenderer.lastNearMax(),
                    BamngPickupRenderer.lastRigid(), BamngPickupRenderer.lastSize());
            LINES.add(Component.literal(skin));
        }
        BamngRenderState.resetAppliedStats();
        BamngSkinBuffer.resetCounters();
    }
}

