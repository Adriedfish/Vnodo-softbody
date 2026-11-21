package com.bamng.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * F12: deterministic binary snapshot of a soft body.  Positions, previous
 * positions, velocities and damage flags are included; targets and beams are
 * topology and stay in the definition.  Callers must restore onto the same
 * topology (node/beam/triangle counts are checked).
 */
public final class SoftBodySnapshot {
    private static final int MAGIC = 0x42414d47; // "BAMG"
    private static final int VERSION = 2;

    private SoftBodySnapshot() {
    }

    public static byte[] save(SoftBodyWorld world) throws IOException {
        return save(world, true);
    }

    /**
     * Save a vehicle shell state without the runtime body id.  A new
     * VehicleBody gets a fresh {@link SoftBodyWorld#bodyId} after world
     * reload, so the normal snapshot's body-id guard cannot be used for
     * persistence.  Node/beam/triangle counts still validate the topology.
     */
    public static byte[] saveVehicleState(SoftBodyWorld world) throws IOException {
        return save(world, false);
    }

    private static byte[] save(SoftBodyWorld world, boolean includeBodyId) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(includeBodyId ? world.bodyId : -1);
        out.writeInt(world.nodes.size());
        for (SoftNode node : world.nodes) {
            writeVec(out, node.position);
            writeVec(out, node.previous);
            writeVec(out, node.velocity);
            writeVec(out, new Vec3(node.plasticX, node.plasticY, node.plasticZ));
            out.writeBoolean(node.wallDented);
            out.writeDouble(node.wallImpactSpeed);
        }
        List<SoftBeam> beams = world.damage.beams();
        out.writeInt(beams.size());
        for (SoftBeam beam : beams) {
            out.writeBoolean(beam.broken);
            out.writeDouble(beam.restLength);
            out.writeDouble(beam.plasticStrain);
            out.writeBoolean(beam.deformed);
            out.writeInt(beam.yieldCount);
        }
        List<SoftTriangle> triangles = world.damage.triangles();
        out.writeInt(triangles.size());
        for (SoftTriangle triangle : triangles) {
            out.writeBoolean(triangle.broken);
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static void load(SoftBodyWorld world, byte[] data) throws IOException {
        load(world, data, true);
    }

    /** Load a {@link #saveVehicleState(SoftBodyWorld)} payload, ignoring body id. */
    public static void loadVehicleState(SoftBodyWorld world, byte[] data) throws IOException {
        load(world, data, false);
    }

    private static void load(SoftBodyWorld world, byte[] data, boolean checkBodyId) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("not a BAMNG snapshot");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported snapshot version " + version);
        }
        int bodyId = in.readInt();
        if (checkBodyId && bodyId != world.bodyId) {
            throw new IOException("snapshot belongs to body " + bodyId);
        }
        int nodeCount = in.readInt();
        if (nodeCount != world.nodes.size()) {
            throw new IOException("node count mismatch " + nodeCount + " != " + world.nodes.size());
        }
        for (SoftNode node : world.nodes) {
            readVec(in, node.position);
            readVec(in, node.previous);
            readVec(in, node.velocity);
            Vec3 plastic = new Vec3();
            readVec(in, plastic);
            node.plasticX = plastic.x;
            node.plasticY = plastic.y;
            node.plasticZ = plastic.z;
            node.wallDented = in.readBoolean();
            node.wallImpactSpeed = in.readDouble();
        }
        List<SoftBeam> beams = world.damage.beams();
        int beamCount = in.readInt();
        if (beamCount != beams.size()) {
            throw new IOException("beam count mismatch");
        }
        for (SoftBeam beam : beams) {
            beam.resetDamageState();
            beam.broken = in.readBoolean();
            beam.restLength = in.readDouble();
            beam.plasticStrain = in.readDouble();
            beam.deformed = in.readBoolean();
            beam.yieldCount = in.readInt();
        }
        List<SoftTriangle> triangles = world.damage.triangles();
        int triCount = in.readInt();
        if (triCount != triangles.size()) {
            throw new IOException("triangle count mismatch");
        }
        for (SoftTriangle triangle : triangles) {
            boolean broken = in.readBoolean();
            triangle.broken = broken;
            triangle.collision = !broken;
            triangle.aero = !broken;
        }
    }

    private static void writeVec(DataOutputStream out, Vec3 value) throws IOException {
        out.writeDouble(value.x);
        out.writeDouble(value.y);
        out.writeDouble(value.z);
    }

    private static void readVec(DataInputStream in, Vec3 value) throws IOException {
        value.x = in.readDouble();
        value.y = in.readDouble();
        value.z = in.readDouble();
    }
}
