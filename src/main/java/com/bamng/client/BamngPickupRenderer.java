package com.bamng.client;

import com.antaurora.vettura.client.vehicle.PickupTruckRenderer;
import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.bamng.core.VehicleBody;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * Delegates every original Vettura draw (doors, seats, cab, gauges, wheels)
 * and applies the BAMNG lattice-derived deformation matrix around the whole
 * vehicle.  This keeps the Vettura renderer as the source of all geometry.
 */
public final class BamngPickupRenderer extends EntityRenderer<PickupTruckEntity> {
    private static boolean rendererLogged;
    private static boolean deformLogged;
    private static long renderCalls;
    private static String lastTruck = "-";
    private static double lastDelta = -1.0;
    private static int lastRigid;
    private static int lastSize;
    private static double lastNearAvg = -1.0;
    private static double lastNearMax = -1.0;
    private static long lastDeformStateLog;
    private static long lastLoggedVertexCalls;
    /** Beyond this range the intact Vettura mesh is visually indistinguishable and much cheaper. */
    private static final double SKIN_DISTANCE = skinDistance();
    private static final double SKIN_DISTANCE_SQ = SKIN_DISTANCE * SKIN_DISTANCE;
    private final PickupTruckRenderer delegate;

    public BamngPickupRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 1.2F;
        this.shadowStrength = 0.7F;
        this.delegate = new PickupTruckRenderer(context);
    }

    @Override
    public void render(PickupTruckEntity truck, float entityYaw, float partialTick,
                       PoseStack pose, MultiBufferSource buffers, int light) {
        renderCalls++;
        // HUD/debug only.  Do not allocate a UUID string every frame; the
        // identifier is stable for the lifetime of the entity.
        if ((renderCalls & 31L) == 0L) {
            lastTruck = truck.getStringUUID();
        }
        pose.pushPose();
        BamngRenderState.Skin skin = BamngRenderState.skinFor(truck);
        lastDelta = skin == null ? -1.0 : skin.delta();
        lastRigid = skin == null ? -1 : skin.rigidCount();
        lastSize = skin == null ? -1 : skin.nodeCount();
        VehicleBody body = BamngVetturaPhysicsClient.skinBodyFor(truck);
        if (body == null) {
            // Total switch off: the Vettura renderer must draw its own truck,
            // not the last Vnodo skin captured before the runtime was released.
            skin = null;
        }
        // The solver lattice is a collision proxy, not the vehicle's authored
        // exterior mesh.  Displaying it as the regular truck turns arbitrary
        // vehicle packs into a low-poly box, so it is diagnostic-only.
        boolean forcedShell = body != null && skin != null && skin.active()
                && Boolean.getBoolean("bamng.forceShell");
        if (forcedShell) {
            BamngForcedShellRenderer.claim(truck, body);
            pose.popPose();
            return;
        }
        boolean nearEnough = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()
                .distanceToSqr(truck.position()) <= SKIN_DISTANCE_SQ;
        boolean deform = skin != null && skin.active() && nearEnough
                && (skin.frontDepth() > 0.004 || skin.rearDepth() > 0.004
                    || skin.bodyDepth() > 0.004
                    || skin.hasFrontField())
                && !Boolean.getBoolean("bamng.noskin");
        if (deform && !deformLogged) {
            deformLogged = true;
            com.bamng.BamngMod.LOGGER.info(
                    "BAMNG skin active front={} rear={} near={}",
                    skin.frontDepth(), skin.rearDepth(), nearEnough);
        }
        long now = System.currentTimeMillis();
        if (now - lastDeformStateLog >= 5000L) {
            lastDeformStateLog = now;
            long vertexCalls = BamngSkinBuffer.vertexCalls();
            long vertexDelta = Math.max(0L, vertexCalls - lastLoggedVertexCalls);
            lastLoggedVertexCalls = vertexCalls;
            com.bamng.BamngMod.LOGGER.info(
                    "BAMNG deform state deform={} maxDelta={} appliedVerts={} skinnedVerts={} front={} rear={} near={}",
                    deform, skin == null ? -1.0 : skin.delta(),
                    BamngRenderState.appliedVertices(), vertexDelta,
                    skin == null ? -1.0 : skin.frontDepth(),
                    skin == null ? -1.0 : skin.rearDepth(), nearEnough);
        }
        // The CPU bone path exists only so the skin buffer can see every
        // vertex.  A resting truck must keep Vextris' GPU cache, otherwise
        // every vehicle pays the full CPU transform cost even with dent=0.
        boolean bypass = deform;
        if (bypass) {
            BamngGpuBypass.push();
        }
        try {
            if (!rendererLogged) {
                rendererLogged = true;
                com.bamng.BamngMod.LOGGER.info("BAMNG custom renderer active deform={} maxDelta={}",
                        deform, skin == null ? -1.0 : skin.delta());
            }
            MultiBufferSource source = deform ? new BamngSkinBuffer(buffers, skin, truck, pose) : buffers;
            // The main pickup_t15 mesh carries the bed plus the front fenders
            // and the seam behind the bumper.  Skinning it only for rear hits
            // left the front shell visibly detached from the body ("没蒙上").
            // The skin buffer only moves vertices in the active crush zone;
            // the rest of the body remains untouched.
            BamngRenderHooks.mainSkin(skin != null
                    && (skin.rearDepth() > 0.004 || skin.frontDepth() > 0.004
                        || skin.bodyDepth() > 0.004));
            BamngRenderHooks.frontShellRearOnly(skin != null
                    && skin.rearDepth() > 0.004 && skin.frontDepth() <= 0.004);
            long start = BamngRenderStats.enabled() ? System.nanoTime() : 0L;
            this.delegate.render(truck, entityYaw, partialTick, pose, source, light);
            // The skin buffer fills these during delegate.render; the HUD is
            // drawn after this and should see this frame's query distance.
            lastNearAvg = skin == null ? -1.0 : skin.nearAvg();
            lastNearMax = skin == null ? -1.0 : skin.nearMax();
            if (BamngRenderStats.enabled()) {
                BamngRenderStats.frame(deform, start, skin == null ? 0.0 : skin.delta());
            }
        } finally {
            BamngRenderHooks.mainSkin(false);
            BamngRenderHooks.frontShellRearOnly(false);
            if (bypass) {
                BamngGpuBypass.pop();
            }
            pose.popPose();
        }
    }

    public static long renderCalls() { return renderCalls; }
    public static String lastTruck() { return lastTruck; }
    public static double lastDelta() { return lastDelta; }
    public static int lastRigid() { return lastRigid; }
    public static int lastSize() { return lastSize; }
    public static double lastNearAvg() { return lastNearAvg; }
    public static double lastNearMax() { return lastNearMax; }

    private static double skinDistance() {
        String configured = System.getProperty("bamng.skinDistance");
        if (configured == null || configured.isBlank()) {
            return 64.0;
        }
        try {
            return Math.max(4.0, Double.parseDouble(configured));
        } catch (NumberFormatException ignored) {
            return 64.0;
        }
    }

    @Override
    public ResourceLocation getTextureLocation(PickupTruckEntity truck) {
        return this.delegate.getTextureLocation(truck);
    }
}
