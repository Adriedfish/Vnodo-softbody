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
    /** 2: plastic/rest length only. 3: full beam yield state. 4: beam strength. */
    private static final int VERSION = 4;
    private static final int LEGACY_VERSION = 2;

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
            // Version 3: the yield plateau.  Without these a reload hands every
            // beam its full undamaged strength again, so the wreck that felt
            // right on exit turns stiff and mismatched on re-entry.
            out.writeDouble(beam.maxPosStress);
            out.writeDouble(beam.maxNegStress);
            out.writeDouble(beam.foldAccum);
            out.writeInt(beam.foldEvents);
            // Version 4: the current beam strength.  Tension yield permanently
            // lowers it; without this field a reloaded wreck gets its
            // undamaged strength back and stops breaking where it used to.
            out.writeDouble(beam.beamStrength);
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
        if (version < LEGACY_VERSION || version > VERSION) {
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
            if (version >= 3) {
                beam.maxPosStress = in.readDouble();
                beam.maxNegStress = in.readDouble();
                beam.foldAccum = in.readDouble();
                beam.foldEvents = in.readInt();
            } else {
                // Legacy saves only carried the shortened rest length.  Rebuild
                // the yield plateau roughly from the plastic strain so the
                // reloaded truck is not magically stronger than the one that
                // was parked.
                beam.maxPosStress = Math.min(beam.beamStrength * 0.85,
                        Math.max(beam.maxPosStress,
                                beam.beamStrength * (1.0 - Math.min(0.60, beam.plasticStrain))));
            }
            if (version >= 4) {
                double savedStrength = in.readDouble();
                if (Double.isFinite(savedStrength) && savedStrength > 0.0) {
                    beam.beamStrength = savedStrength;
                }
            }
        }
        // v2 saves came from the runaway-plasticity build and need the old
        // length repair.  v3/v4 store exact authored / yielded lengths; clamping
        // them again on every load changed a legal 5 cm front beam into 10.6 cm.
        if (version < 3) {
            sanitizeBeamLengths(world, beams);
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

    /**
     * A v2 save from the runaway-plasticity build stored rest lengths that had
     * already collapsed to the floor while the node plastic was later clamped
     * on load.  Restoring both untouched made the shell either slack (rest
     * length far below the node spacing) or springy.  Keep front crush beams
     * free to shorten to their authored floor, but stop body/cab beams from
     * shrinking past 60 % of their authored length.  This repair is only for
     * v2; v3/v4 already store exact yielded lengths and must round-trip them.
     */
    private static void sanitizeBeamLengths(SoftBodyWorld world, List<SoftBeam> beams) {
        List<SoftNode> nodes = world.nodes;
        for (SoftBeam beam : beams) {
            double original = beam.originalRestLength;
            if (!(original > 1.0e-6)) {
                continue;
            }
            boolean front = false;
            if (beam.a >= 0 && beam.a < nodes.size()) {
                front |= "front".equals(nodes.get(beam.a).group0);
            }
            if (beam.b >= 0 && beam.b < nodes.size()) {
                front |= "front".equals(nodes.get(beam.b).group0);
            }
            double min = front ? Math.max(0.10, original * 0.20) : original * 0.60;
            double max = original * 1.60;
            double length = Double.isFinite(beam.restLength) ? beam.restLength : original;
            beam.restLength = Math.max(min, Math.min(max, length));
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
