package com.bamng.client;

import com.antaurora.vettura.vehicle.PickupTruckEntity;
import com.bamng.BamngMod;
import com.bamng.core.SoftBodyWorld;
import com.bamng.core.SoftNode;
import com.bamng.core.SoftTriangle;
import com.bamng.core.VehicleBody;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Emergency-but-correct renderer for damaged Vettura trucks.
 *
 * <p>It deliberately draws BAMNG's solved triangle surface in world space
 * instead of trying to mutate Vextris' GPU-cached vertex stream.  That makes
 * this path independent of mesh/bone ordering and guarantees that the shape
 * visible after a crash is the shape used by the collision solver.  It is
 * also compact: it emits only the vehicle pack's own soft-body triangles,
 * rather than the roughly 100k vertices observed in the intercepted Vextris
 * path.</p>
 */
@Mod.EventBusSubscriber(modid = BamngMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BamngForcedShellRenderer {
    private static final Map<PickupTruckEntity, VehicleBody> CLAIMED = new WeakHashMap<>();

    private BamngForcedShellRenderer() {
    }

    /** Called by the entity renderer to hide Vettura's rigid body this frame. */
    static void claim(PickupTruckEntity truck, VehicleBody body) {
        CLAIMED.put(truck, body);
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || CLAIMED.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        var camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.debugQuads());

        Iterator<Map.Entry<PickupTruckEntity, VehicleBody>> iterator = CLAIMED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<PickupTruckEntity, VehicleBody> entry = iterator.next();
            PickupTruckEntity truck = entry.getKey();
            VehicleBody body = entry.getValue();
            if (truck == null || truck.isRemoved() || body == null) {
                iterator.remove();
                continue;
            }
            SoftBodyWorld world = body.world;
            SoftNode[] nodes = world.nodeArray();
            for (SoftTriangle triangle : world.triangles) {
                if (triangle.broken || triangle.degenerate()) {
                    continue;
                }
                float[] color = colorFor(nodes[triangle.a]);
                triangle(consumer, matrix, nodes[triangle.a], nodes[triangle.b], nodes[triangle.c], color);
            }
            // Tires are separate pressure volumes rather than exterior-shell
            // triangles.  Render their radial faces independently so they
            // stay with the physical hubs and cannot inherit body weights.
            for (com.bamng.core.SoftTire tire : body.tires) {
                SoftNode hub = nodes[tire.hubA];
                for (int i = 0; i < tire.ring.length; i++) {
                    SoftNode a = nodes[tire.ring[i]];
                    SoftNode b = nodes[tire.ring[(i + 1) % tire.ring.length]];
                    triangle(consumer, matrix, hub, a, b, new float[]{0.10F, 0.10F, 0.12F, 1.0F});
                }
            }
        }
        buffers.endBatch(RenderType.debugQuads());
        pose.popPose();
    }

    private static void triangle(VertexConsumer consumer, Matrix4f matrix,
                                 SoftNode a, SoftNode b, SoftNode c, float[] color) {
        // debugQuads consumes four vertices.  Repeating the final corner makes
        // a degenerate fourth edge while preserving the actual soft triangle.
        vertex(consumer, matrix, a, color);
        vertex(consumer, matrix, b, color);
        vertex(consumer, matrix, c, color);
        vertex(consumer, matrix, c, color);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, SoftNode node, float[] color) {
        consumer.vertex(matrix, (float) node.position.x, (float) node.position.y, (float) node.position.z)
                .color(color[0], color[1], color[2], color[3])
                .endVertex();
    }

    private static float[] colorFor(SoftNode node) {
        String group = node.group0 == null ? "body" : node.group0;
        return switch (group) {
            case "front" -> new float[]{0.86F, 0.10F, 0.08F, 1.0F};
            case "tailgate" -> new float[]{0.18F, 0.36F, 0.82F, 1.0F};
            case "bed" -> new float[]{0.20F, 0.62F, 0.28F, 1.0F};
            case "rear" -> new float[]{0.72F, 0.52F, 0.10F, 1.0F};
            default -> new float[]{0.74F, 0.76F, 0.82F, 1.0F};
        };
    }
}
