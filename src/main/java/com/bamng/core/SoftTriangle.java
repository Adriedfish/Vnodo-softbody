package com.bamng.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A triangle in the soft-body collision / shell topology.
 *
 * <p>The group is inherited from the first vertex, matching BeamNG's public
 * rule ("triangles have no group field; they use the first node's group").
 * Collision and aero are switched off when the triangle breaks.</p>
 */
public final class SoftTriangle {
    private static int NEXT_ID;
    public final int id = NEXT_ID++;
    public final int a;
    public final int b;
    public final int c;

    public boolean broken;
    public boolean collision = true;
    public boolean aero = true;
    public boolean disableTriangleBreaking;

    /** True when orientOutward reversed the winding-derived normal.  The
     *  flag must survive updateGeometry, otherwise the next substep recomputes
     *  the original inward normal and pressure pushes the panel the wrong way. */
    public boolean flipped;

    /** Cached unit normal and area; refreshed after each integration. */
    public double nx;
    public double ny;
    public double nz;
    public double area;

    /** C10: last frame with a collidable node within 10 cm. */
    public int activeFrame;
    public boolean sleeping;

    /** C13: 0 default, 1 in, 2 out, 3 oneWayIn, 4 oneWayOut. */
    public int bias;

    /** E2: aerodynamic coefficients.  dragCoef follows BeamNG's "percent of
     *  a flat plate" scale (100 = Cd 1.0).  Default 0 keeps aero off. */
    public double dragCoef;
    public double liftCoef;
    public double skinDrag;
    public double stallAngle = 0.58;

    /** Cached previous normal for CCD (lastN). */
    public double lastNx;
    public double lastNy;
    public double lastNz;

    /** Beams that form this triangle's three edges. */
    public final List<SoftBeam> edgeBeams = new ArrayList<>(3);

    public SoftTriangle(int a, int b, int c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }

    public String group(SoftNode[] nodes) {
        return nodes[a].group0;
    }

    public void updateGeometry(SoftNode[] nodes) {
        SoftNode na = nodes[a];
        SoftNode nb = nodes[b];
        SoftNode nc = nodes[c];
        double abx = nb.position.x - na.position.x;
        double aby = nb.position.y - na.position.y;
        double abz = nb.position.z - na.position.z;
        double acx = nc.position.x - na.position.x;
        double acy = nc.position.y - na.position.y;
        double acz = nc.position.z - na.position.z;

        double cx = aby * acz - abz * acy;
        double cy = abz * acx - abx * acz;
        double cz = abx * acy - aby * acx;
        double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
        this.area = 0.5 * len;
        if (len < 1.0e-9) {
            // Degenerate at runtime: skip collision, keep topology for repair.
            this.nx = 0.0;
            this.ny = 1.0;
            this.nz = 0.0;
            return;
        }
        this.lastNx = this.nx;
        this.lastNy = this.ny;
        this.lastNz = this.nz;
        this.nx = cx / len;
        this.ny = cy / len;
        this.nz = cz / len;
        if (this.flipped) {
            this.nx = -this.nx;
            this.ny = -this.ny;
            this.nz = -this.nz;
        }
    }

    /** Flip winding so the normal points away from the body centroid. */
    public void orientOutward(SoftNode[] nodes, double cx, double cy, double cz) {
        SoftNode na = nodes[a];
        double ox = (na.position.x + nodes[b].position.x + nodes[c].position.x) / 3.0 - cx;
        double oy = (na.position.y + nodes[b].position.y + nodes[c].position.y) / 3.0 - cy;
        double oz = (na.position.z + nodes[b].position.z + nodes[c].position.z) / 3.0 - cz;
        if (ox * nx + oy * ny + oz * nz < 0.0) {
            this.nx = -this.nx;
            this.ny = -this.ny;
            this.nz = -this.nz;
            this.flipped = !this.flipped;
        }
    }

    public boolean degenerate() {
        return area < 1.0e-8;
    }
}
