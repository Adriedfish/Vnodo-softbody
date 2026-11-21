package com.bamng.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Runtime reader for Vettura's T15 exterior mesh. No asset is copied into BAMNG. */
public final class T15Mesh {
    public static final ResourceLocation MESH = new ResourceLocation("vettura", "meshes/pickup_t15.vxmesh.json");
    public static final ResourceLocation TEXTURE = new ResourceLocation("vettura", "textures/entity/vehicle/pickup_t15.png");

    private static T15Mesh cached;
    private static boolean attempted;

    public final List<Part> parts;

    private T15Mesh(List<Part> parts) {
        this.parts = parts;
    }

    public static T15Mesh get() {
        if (attempted) {
            return cached;
        }
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(MESH);
        if (resource.isEmpty()) {
            com.bamng.BamngMod.LOGGER.warn("BAMNG T15 mesh not found: {}", MESH);
            return null;
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray parts = root.getAsJsonArray("parts");
            List<Part> out = new ArrayList<>();
            for (JsonElement element : parts) {
                JsonObject part = element.getAsJsonObject();
                JsonArray vertices = part.getAsJsonArray("vertices");
                float[] positions = new float[vertices.size() * 3];
                float[] uvs = new float[vertices.size() * 2];
                for (int i = 0; i < vertices.size(); i++) {
                    JsonArray v = vertices.get(i).getAsJsonArray();
                    positions[i * 3] = v.get(0).getAsFloat();
                    positions[i * 3 + 1] = v.get(1).getAsFloat();
                    positions[i * 3 + 2] = v.get(2).getAsFloat();
                    uvs[i * 2] = v.get(3).getAsFloat();
                    uvs[i * 2 + 1] = v.get(4).getAsFloat();
                }
                JsonArray faces = part.getAsJsonArray("faces");
                List<Integer> indices = new ArrayList<>();
                for (JsonElement faceElement : faces) {
                    JsonArray face = faceElement.getAsJsonArray();
                    int[] f = new int[face.size()];
                    for (int i = 0; i < f.length; i++) {
                        f[i] = face.get(i).getAsInt();
                    }
                    for (int i = 1; i + 1 < f.length; i++) {
                        indices.add(f[0]);
                        indices.add(f[i]);
                        indices.add(f[i + 1]);
                    }
                }
                int[] idx = new int[indices.size()];
                for (int i = 0; i < idx.length; i++) {
                    idx[i] = indices.get(i);
                }
                out.add(new Part(part.get("name").getAsString(), positions, uvs, idx));
            }
            cached = new T15Mesh(out);
            attempted = true;
        } catch (Exception exception) {
            com.bamng.BamngMod.LOGGER.error("BAMNG T15 mesh parse failed", exception);
            return null;
        }
        return cached;
    }

    public record Part(String name, float[] positions, float[] uvs, int[] indices) {
    }
}
