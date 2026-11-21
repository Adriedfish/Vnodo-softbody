package com.bamng.core;

import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.crush.CrushFrame;

/**
 * Bridges the Vnodo node lattice into Vettura's authored crush frame.
 *
 * <p>Vettura's collision hull is generated from its own {@link CrushFrame}
 * field, not from the render mesh.  The frame is a regular f/r/y grid; Vnodo
 * is a sparse lattice.  This class precomputes a three-node inverse-distance
 * map from every frame point to the rest lattice, then each tick writes the
 * Vnodo permanent displacement into the frame's native f/r/y displacement
 * array.  Vettura remains the owner of {@code bodyBoxes()}, {@code bent()},
 * {@code hull()} and {@code applyTo()}; only the input field is replaced.</p>
 *
 * <p>Coordinate proof: {@code CrushFrame.rest} stores f as longitudinal,
 * r as lateral and y relative to the chassis base.  Vnodo nodes are authored
 * x=longitudinal, z=lateral, y=world absolute.  The closest mapping was
 * obtained by comparing {@code frameY + body.baseY} against node rest y.</p>
 */
public final class VnodoCollisionField {
    private static final int NEIGHBOURS = 3;
    private static final double DEFORM_EPSILON = 0.0025;

    private final VehicleBody body;
    private final CrushFrame frame;
    private final int frameCount;
    private final int cellCount;
    private final int[] nearestA;
    private final int[] nearestB;
    private final int[] nearestC;
    private final double[] weightA;
    private final double[] weightB;
    private final double[] weightC;
    private final double[] nodeF;
    private final double[] nodeR;
    private final double[] nodeY;
    private final float[] field;
    private long checksum;
    // Separate version namespace from Vettura's own crushVersion.  If both
    // start at 1, PickupTruckEntity.bodyBoxes() can already have a cached
    // bentBody for version 1 and will keep returning the old box list even
    // though the Vnodo field has just deformed.
    private int version = 1_000_003;
    private boolean deformed;

    public VnodoCollisionField(VehicleBody body, VehicleLayout layout) {
        this.body = body;
        this.frame = CrushFrame.of(layout, body.baseY);
        this.frameCount = this.frame.count;
        this.cellCount = this.frame.cell.length;
        this.nearestA = new int[this.cellCount];
        this.nearestB = new int[this.cellCount];
        this.nearestC = new int[this.cellCount];
        this.weightA = new double[this.cellCount];
        this.weightB = new double[this.cellCount];
        this.weightC = new double[this.cellCount];
        this.nodeF = new double[body.world.nodes.size()];
        this.nodeR = new double[body.world.nodes.size()];
        this.nodeY = new double[body.world.nodes.size()];
        this.field = new float[this.cellCount * 3];
        this.buildNearestMap();
    }

    public float[] field() {
        return this.field;
    }

    public int version() {
        return this.version;
    }

    public boolean hasDeformation() {
        return this.deformed;
    }

    public int frameCount() {
        return this.frameCount;
    }

    public int cellCount() {
        return this.cellCount;
    }

    public CrushFrame frame() {
        return this.frame;
    }

    /**
     * Rebuild the frame displacement from the current Vnodo permanent shape.
     * Call once after the soft step and before Vettura asks for collision
     * boxes.  The field is reused; only version changes when its values move.
     */
    public void update(double truckX, double truckY, double truckZ, float truckYaw) {
        double modelYaw = Math.toRadians(180.0 - truckYaw);
        double cos = Math.cos(modelYaw);
        double sin = Math.sin(modelYaw);
        for (int i = 0; i < this.body.world.nodes.size(); i++) {
            SoftNode node = this.body.world.nodes.get(i);
            // Same rest pose used by BamngVetturaPhysicsClient.init(): the
            // model frame is X=lateral, Y=up, Z=-longitudinal.  Tire nodes
            // are authored at absolute road height; they are excluded from
            // the frame map below, but this branch keeps the formula honest.
            boolean tire = node.part != null && node.part.startsWith("tire");
            double restY = node.rest.y;
            if (!tire) {
                restY -= this.body.baseY;
            }
            double restX = truckX + node.rest.z * cos - node.rest.x * sin;
            double restZ = truckZ - node.rest.z * sin - node.rest.x * cos;
            double dentX = node.targetX + node.plasticX;
            double dentY = node.targetY + node.plasticY;
            double dentZ = node.targetZ + node.plasticZ;
            double dx = dentX - restX;
            double dy = dentY - (truckY + restY);
            double dz = dentZ - restZ;
            // Inverse of Vettura's model rotation.  The authored frame's
            // forward axis is -Z, so f = -bodyZ, r = bodyX.
            double bodyX = dx * cos - dz * sin;
            double bodyZ = dx * sin + dz * cos;
            this.nodeF[i] = -bodyZ;
            this.nodeR[i] = bodyX;
            this.nodeY[i] = dy;
        }

        long nextChecksum = 0L;
        double maxAbs = 0.0;
        // Build a dense displacement for every authored frame cell, including
        // cells that CrushFrame.cell marks as body holes.  Vettura's bent()
        // samples boxes at the frame corners; leaving a hole empty is exactly
        // how a corner impact used to escape collision-box follow.
        for (int cell = 0; cell < this.cellCount; cell++) {
            int a = this.nearestA[cell];
            int b = this.nearestB[cell];
            int c = this.nearestC[cell];
            double wa = this.weightA[cell];
            double wb = this.weightB[cell];
            double wc = this.weightC[cell];
            int out = cell * 3;
            float fx = (float) (this.nodeF[a] * wa
                    + (b >= 0 ? this.nodeF[b] * wb : 0.0)
                    + (c >= 0 ? this.nodeF[c] * wc : 0.0));
            float fr = (float) (this.nodeR[a] * wa
                    + (b >= 0 ? this.nodeR[b] * wb : 0.0)
                    + (c >= 0 ? this.nodeR[c] * wc : 0.0));
            float fy = (float) (this.nodeY[a] * wa
                    + (b >= 0 ? this.nodeY[b] * wb : 0.0)
                    + (c >= 0 ? this.nodeY[c] * wc : 0.0));
            this.field[out] = fx;
            this.field[out + 1] = fr;
            this.field[out + 2] = fy;
            maxAbs = Math.max(maxAbs, Math.abs(fx));
            maxAbs = Math.max(maxAbs, Math.abs(fr));
            maxAbs = Math.max(maxAbs, Math.abs(fy));
            nextChecksum = nextChecksum * 1099511628211L + Float.floatToIntBits(fx);
            nextChecksum = nextChecksum * 1099511628211L + Float.floatToIntBits(fr);
            nextChecksum = nextChecksum * 1099511628211L + Float.floatToIntBits(fy);
        }
        this.deformed = maxAbs > DEFORM_EPSILON;
        if (nextChecksum != this.checksum) {
            this.checksum = nextChecksum;
            this.version++;
        }
    }

    private void buildNearestMap() {
        int nodeCount = this.body.world.nodes.size();
        int ny = this.frame.ny;
        int nr = this.frame.nr;
        for (int cell = 0; cell < this.cellCount; cell++) {
            int fi = cell / (nr * ny);
            int rem = cell % (nr * ny);
            int ri = rem / ny;
            int yi = rem % ny;
            double frameF = this.frame.f0 + fi * this.frame.spacing;
            double frameR = this.frame.r0 + ri * this.frame.spacing;
            double frameY = this.frame.y0 + yi * this.frame.spacing + this.body.baseY;
            int a = -1, b = -1, c = -1;
            double da = Double.POSITIVE_INFINITY;
            double db = Double.POSITIVE_INFINITY;
            double dc = Double.POSITIVE_INFINITY;
            for (int i = 0; i < nodeCount; i++) {
                SoftNode node = this.body.world.nodes.get(i);
                if ((node.part != null && node.part.startsWith("tire"))
                        || "wheel".equals(node.group0)) {
                    continue;
                }
                double dx = frameF - node.rest.x;
                double dy = frameY - node.rest.y;
                double dz = frameR - node.rest.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 < da) {
                    c = b; dc = db;
                    b = a; db = da;
                    a = i; da = d2;
                } else if (d2 < db) {
                    c = b; dc = db;
                    b = i; db = d2;
                } else if (d2 < dc) {
                    c = i; dc = d2;
                }
            }
            this.nearestA[cell] = a;
            this.nearestB[cell] = b;
            this.nearestC[cell] = c;
            double wa = a < 0 ? 0.0 : 1.0 / (0.035 + Math.max(0.0, da));
            double wb = b < 0 ? 0.0 : 1.0 / (0.035 + Math.max(0.0, db));
            double wc = c < 0 ? 0.0 : 1.0 / (0.035 + Math.max(0.0, dc));
            double total = wa + wb + wc;
            if (total <= 1.0e-9) {
                this.nearestA[cell] = 0;
                this.weightA[cell] = 1.0;
                this.weightB[cell] = 0.0;
                this.weightC[cell] = 0.0;
            } else {
                this.weightA[cell] = wa / total;
                this.weightB[cell] = wb / total;
                this.weightC[cell] = wc / total;
            }
        }
    }
}
