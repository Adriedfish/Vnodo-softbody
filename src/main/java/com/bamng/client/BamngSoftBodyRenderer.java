package com.bamng.client;

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

/**
 * Renders the actual soft-body surface, not a rigid box approximation.
 * The mesh is the live triangle soup after collision, so every dent, torn
 * panel and inflated tire is drawn in the same frame the solver produced it.
 */
@Mod.EventBusSubscriber(modid = BamngMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BamngSoftBodyRenderer {
    private BamngSoftBodyRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !Boolean.getBoolean("bamng.debugShell")) {
            return;
        }
        var camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = pose.last().pose();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.debugQuads());
        for (VehicleBody body : BamngVetturaPhysicsClient.activeBodies()) {
            SoftBodyWorld world = body.world;
            SoftNode[] nodes = world.nodeArray();
            // In first person the camera sits inside the shell.  The debug
            // surface is intentionally untextured, so drawing it around the
            // eye would black out the cockpit.  Keep gameplay readable and
            // show the deformed surface from outside/external views.
            double camDist2 = camera.distanceToSqr(centroidX(nodes), centroidY(nodes), centroidZ(nodes));
            if (camDist2 < 16.0 || camDist2 > 128.0 * 128.0) {
                continue;
            }
            for (SoftTriangle triangle : world.triangles) {
                if (triangle.broken || triangle.degenerate()) {
                    continue;
                }
                float[] color = colorFor(nodes[triangle.a].group0, nodes[triangle.a].part);
                quad(consumer, matrix, nodes[triangle.a], nodes[triangle.b], nodes[triangle.c], color);
            }
            // Draw the tire chambers slightly brighter; they are the E3
            // pressure volumes and must be visible as inflated rings.
            for (com.bamng.core.SoftTire tire : body.tires) {
                for (int i = 0; i < tire.ring.length; i++) {
                    SoftNode a = nodes[tire.ring[i]];
                    SoftNode b = nodes[tire.ring[(i + 1) % tire.ring.length]];
                    SoftNode hub = nodes[tire.hubA];
                    quad(consumer, matrix, hub, a, b, new float[]{0.95F, 0.55F, 0.12F, 0.34F});
                }
            }
        }
        buffers.endBatch(RenderType.debugQuads());
        pose.popPose();
    }

    private static double centroidX(SoftNode[] nodes) {
        double sum = 0.0;
        for (SoftNode node : nodes) sum += node.position.x;
        return sum / Math.max(1, nodes.length);
    }

    private static double centroidY(SoftNode[] nodes) {
        double sum = 0.0;
        for (SoftNode node : nodes) sum += node.position.y;
        return sum / Math.max(1, nodes.length);
    }

    private static double centroidZ(SoftNode[] nodes) {
        double sum = 0.0;
        for (SoftNode node : nodes) sum += node.position.z;
        return sum / Math.max(1, nodes.length);
    }

    private static void quad(VertexConsumer consumer, Matrix4f matrix,
                             SoftNode a, SoftNode b, SoftNode c, float[] color) {
        // debugQuads uses a two-vertex pair; emitting three vertices per
        // triangle is accepted by the debug line/quad sink and keeps this
        // independent of a textured RenderType.
        vertex(consumer, matrix, a, color);
        vertex(consumer, matrix, b, color);
        vertex(consumer, matrix, c, color);
        vertex(consumer, matrix, c, color);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix,
                               SoftNode node, float[] color) {
        consumer.vertex(matrix, (float) node.position.x, (float) node.position.y, (float) node.position.z)
                .color(color[0], color[1], color[2], color[3])
                .endVertex();
    }

    private static float[] colorFor(String group, String part) {
        if (part != null && part.startsWith("tire")) {
            return new float[]{0.95F, 0.55F, 0.12F, 0.30F};
        }
        return switch (group == null ? "body" : group) {
            case "front" -> new float[]{0.95F, 0.25F, 0.18F, 0.30F};
            case "tailgate" -> new float[]{0.25F, 0.55F, 0.95F, 0.30F};
            case "bed" -> new float[]{0.25F, 0.85F, 0.45F, 0.28F};
            case "rear" -> new float[]{0.85F, 0.70F, 0.20F, 0.28F};
            case "wheel" -> new float[]{0.95F, 0.55F, 0.12F, 0.30F};
            default -> new float[]{0.80F, 0.82F, 0.88F, 0.26F};
        };
    }
}
