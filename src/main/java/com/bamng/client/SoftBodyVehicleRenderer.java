package com.bamng.client;

import com.bamng.core.SoftNode;
import com.bamng.entity.SoftBodyVehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class SoftBodyVehicleRenderer extends EntityRenderer<SoftBodyVehicleEntity> {
    private final Map<SoftBodyVehicleEntity, Skin> skins = new IdentityHashMap<>();

    public SoftBodyVehicleRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 1.2F;
        this.shadowStrength = 0.7F;
    }

    @Override
    public void render(SoftBodyVehicleEntity entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        T15Mesh mesh = T15Mesh.get();
        if (mesh == null) {
            return;
        }

        Skin skin = this.skins.computeIfAbsent(entity, e -> this.buildSkin(e, mesh));

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(90.0F));

        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(T15Mesh.TEXTURE));
        int partBase = 0;
        for (T15Mesh.Part part : mesh.parts) {
            int[] indices = part.indices();
            float[] positions = part.positions();
            float[] uvs = part.uvs();
            for (int i = 0; i + 2 < indices.length; i += 3) {
                int a = indices[i];
                int b = indices[i + 1];
                int c = indices[i + 2];
                Vector3f pa = this.vertex(entity, skin, partBase + a, positions, uvs);
                Vector3f pb = this.vertex(entity, skin, partBase + b, positions, uvs);
                Vector3f pc = this.vertex(entity, skin, partBase + c, positions, uvs);
                Vector3f normal = new Vector3f(pb).sub(pa).cross(new Vector3f(pc).sub(pa)).normalize();
                if (!Float.isFinite(normal.x)) {
                    normal.set(0.0F, 1.0F, 0.0F);
                }
                Matrix3f normalMatrix = pose.last().normal();
                this.put(buffer, pose, pa, uvs[a * 2], uvs[a * 2 + 1], packedLight, normal, normalMatrix);
                this.put(buffer, pose, pb, uvs[b * 2], uvs[b * 2 + 1], packedLight, normal, normalMatrix);
                this.put(buffer, pose, pc, uvs[c * 2], uvs[c * 2 + 1], packedLight, normal, normalMatrix);
            }
            partBase += positions.length / 3;
        }
        pose.popPose();
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
    }

    private void put(VertexConsumer buffer, PoseStack pose, Vector3f p, float u, float v,
                     int light, Vector3f n, Matrix3f normalMatrix) {
        Vector3f out = new Vector3f(p);
        pose.last().pose().transformPosition(out);
        buffer.vertex(out.x, out.y, out.z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normalMatrix, n.x, n.y, n.z)
                .endVertex();
    }

    private Vector3f vertex(SoftBodyVehicleEntity entity, Skin skin, int index, float[] positions, float[] uvs) {
        int base = index * 4;
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        List<SoftNode> nodes = entity.body().world.nodes;
        for (int k = 0; k < 4; k++) {
            int nodeIndex = skin.nodes[base + k];
            float weight = skin.weights[base + k];
            if (weight == 0.0F) {
                continue;
            }
            SoftNode node = nodes.get(nodeIndex);
            x += (node.position.z - entity.getZ()) * weight;
            y += (node.position.y - entity.getY()) * weight;
            z += (node.position.x - entity.getX()) * weight;
        }
        return new Vector3f((float) x, (float) y, (float) z);
    }

    private Skin buildSkin(SoftBodyVehicleEntity entity, T15Mesh mesh) {
        int count = 0;
        for (T15Mesh.Part part : mesh.parts) {
            count += part.positions().length / 3;
        }
        int[] nodes = new int[count * 4];
        float[] weights = new float[count * 4];
        List<SoftNode> bodyNodes = entity.body().world.nodes;
        int vertex = 0;
        for (T15Mesh.Part part : mesh.parts) {
            float[] positions = part.positions();
            for (int i = 0; i < positions.length; i += 3) {
                // Mesh axes: x=lateral, y=up, z=longitudinal.
                // Node axes: x=forward, y=up, z=lateral.
                double nx = positions[i + 2];
                double ny = positions[i + 1];
                double nz = positions[i];
                int[] best = new int[4];
                double[] bestD = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
                for (SoftNode node : bodyNodes) {
                    double dx = (node.position.x - entity.getX()) - nx;
                    double dy = (node.position.y - entity.getY()) - ny;
                    double dz = (node.position.z - entity.getZ()) - nz;
                    double d = dx * dx + dy * dy + dz * dz;
                    int slot = -1;
                    for (int k = 0; k < 4; k++) {
                        if (d < bestD[k]) {
                            slot = k;
                            break;
                        }
                    }
                    if (slot >= 0) {
                        for (int k = 3; k > slot; k--) {
                            best[k] = best[k - 1];
                            bestD[k] = bestD[k - 1];
                        }
                        best[slot] = node.index;
                        bestD[slot] = d;
                    }
                }
                double total = 0.0;
                for (int k = 0; k < 4; k++) {
                    nodes[vertex * 4 + k] = best[k];
                    double w = 1.0 / (0.02 + Math.sqrt(Math.max(0.0, bestD[k])));
                    weights[vertex * 4 + k] = (float) w;
                    total += w;
                }
                for (int k = 0; k < 4; k++) {
                    weights[vertex * 4 + k] /= (float) total;
                }
                vertex++;
            }
        }
        return new Skin(nodes, weights);
    }

    @Override
    public ResourceLocation getTextureLocation(SoftBodyVehicleEntity entity) {
        return T15Mesh.TEXTURE;
    }

    private static final class Skin {
        final int[] nodes;
        final float[] weights;

        Skin(int[] nodes, float[] weights) {
            this.nodes = nodes;
            this.weights = weights;
        }
    }
}
