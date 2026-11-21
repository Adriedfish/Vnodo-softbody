package com.bamng.core;

/**
 * Point-triangle narrow phase for the soft-body collision pass.
 *
 * <p>Implements C1/C2/C3/C9 of the blueprint as pure static helpers so the
 * solver can be unit tested without a Minecraft world:</p>
 * <ul>
 *   <li>closest point on a triangle and signed distance;</li>
 *   <li>2.5 cm contact band handled by the caller;</li>
 *   <li>front-side (anticlip) test using the triangle winding;</li>
 *   <li>swept point vs triangle (TOI) for CCD.</li>
 * </ul>
 */
public final class TriangleCollision {
    private TriangleCollision() {
    }

    /**
     * Closest point on triangle ABC to P.  Writes the point into {@code out}
     * and returns the squared distance.
     */
    public static double closestPoint(double px, double py, double pz,
                                      double ax, double ay, double az,
                                      double bx, double by, double bz,
                                      double cx, double cy, double cz,
                                      Vec3 out) {
        double abx = bx - ax, aby = by - ay, abz = bz - az;
        double acx = cx - ax, acy = cy - ay, acz = cz - az;
        double apx = px - ax, apy = py - ay, apz = pz - az;

        double d1 = abx * apx + aby * apy + abz * apz;
        double d2 = acx * apx + acy * apy + acz * apz;
        if (d1 <= 0.0 && d2 <= 0.0) {
            out.set(ax, ay, az);
            return sq(px - ax, py - ay, pz - az);
        }

        double bpx = px - bx, bpy = py - by, bpz = pz - bz;
        double d3 = abx * bpx + aby * bpy + abz * bpz;
        double d4 = acx * bpx + acy * bpy + acz * bpz;
        if (d3 >= 0.0 && d4 <= d3) {
            out.set(bx, by, bz);
            return sq(px - bx, py - by, pz - bz);
        }

        double vc = d1 * d4 - d3 * d2;
        if (vc <= 0.0 && d1 >= 0.0 && d3 <= 0.0) {
            double v = d1 / (d1 - d3);
            out.set(ax + abx * v, ay + aby * v, az + abz * v);
            return sq(px - out.x, py - out.y, pz - out.z);
        }

        double cpx = px - cx, cpy = py - cy, cpz = pz - cz;
        double d5 = abx * cpx + aby * cpy + abz * cpz;
        double d6 = acx * cpx + acy * cpy + acz * cpz;
        if (d6 >= 0.0 && d5 <= d6) {
            out.set(cx, cy, cz);
            return sq(px - cx, py - cy, pz - cz);
        }

        double vb = d5 * d2 - d1 * d6;
        if (vb <= 0.0 && d2 >= 0.0 && d6 <= 0.0) {
            double w = d2 / (d2 - d6);
            out.set(ax + acx * w, ay + acy * w, az + acz * w);
            return sq(px - out.x, py - out.y, pz - out.z);
        }

        double va = d3 * d6 - d5 * d4;
        if (va <= 0.0 && (d4 - d3) >= 0.0 && (d5 - d6) >= 0.0) {
            double w = (d4 - d3) / ((d4 - d3) + (d5 - d6));
            out.set(bx + (cx - bx) * w, by + (cy - by) * w, bz + (cz - bz) * w);
            return sq(px - out.x, py - out.y, pz - out.z);
        }

        double denom = 1.0 / (va + vb + vc);
        double v = vb * denom;
        double w = vc * denom;
        out.set(ax + abx * v + acx * w, ay + aby * v + acy * w, az + abz * v + acz * w);
        return sq(px - out.x, py - out.y, pz - out.z);
    }

    /**
     * Signed distance of P from triangle plane, positive on the winding's
     * normal side.  The closest point is written to {@code closest}.
     */
    public static double signedDistance(double px, double py, double pz,
                                        double ax, double ay, double az,
                                        double bx, double by, double bz,
                                        double cx, double cy, double cz,
                                        double nx, double ny, double nz,
                                        Vec3 closest) {
        closestPoint(px, py, pz, ax, ay, az, bx, by, bz, cx, cy, cz, closest);
        return (px - closest.x) * nx + (py - closest.y) * ny + (pz - closest.z) * nz;
    }

    /**
     * Swept point test against a triangle.  The point travels from P0 to P1.
     * Returns a time of impact in [0,1], or {@link Double#NaN} when there is
     * no crossing.  {@code hit} contains the contact point at the impact.
     */
    public static double sweepPointTriangle(double p0x, double p0y, double p0z,
                                            double p1x, double p1y, double p1z,
                                            double ax, double ay, double az,
                                            double bx, double by, double bz,
                                            double cx, double cy, double cz,
                                            Vec3 hit) {
        double dx = p1x - p0x, dy = p1y - p0y, dz = p1z - p0z;
        double e1x = bx - ax, e1y = by - ay, e1z = bz - az;
        double e2x = cx - ax, e2y = cy - ay, e2z = cz - az;
        double hx = dy * e2z - dz * e2y;
        double hy = dz * e2x - dx * e2z;
        double hz = dx * e2y - dy * e2x;
        double det = e1x * hx + e1y * hy + e1z * hz;
        if (Math.abs(det) < 1.0e-12) {
            return Double.NaN;
        }
        double inv = 1.0 / det;
        double sx = p0x - ax, sy = p0y - ay, sz = p0z - az;
        double u = (sx * hx + sy * hy + sz * hz) * inv;
        if (u < 0.0 || u > 1.0) {
            return Double.NaN;
        }
        double qx = sy * e1z - sz * e1y;
        double qy = sz * e1x - sx * e1z;
        double qz = sx * e1y - sy * e1x;
        double v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < 0.0 || u + v > 1.0) {
            return Double.NaN;
        }
        double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        if (t < 0.0 || t > 1.0) {
            return Double.NaN;
        }
        if (hit != null) {
            hit.set(p0x + dx * t, p0y + dy * t, p0z + dz * t);
        }
        return t;
    }

    /** Barycentric weights for a point already projected onto the triangle. */
    public static void barycentric(double px, double py, double pz,
                                   double ax, double ay, double az,
                                   double bx, double by, double bz,
                                   double cx, double cy, double cz,
                                   double[] out) {
        double v0x = bx - ax, v0y = by - ay, v0z = bz - az;
        double v1x = cx - ax, v1y = cy - ay, v1z = cz - az;
        double v2x = px - ax, v2y = py - ay, v2z = pz - az;
        double d00 = v0x * v0x + v0y * v0y + v0z * v0z;
        double d01 = v0x * v1x + v0y * v1y + v0z * v1z;
        double d11 = v1x * v1x + v1y * v1y + v1z * v1z;
        double d20 = v2x * v0x + v2y * v0y + v2z * v0z;
        double d21 = v2x * v1x + v2y * v1y + v2z * v1z;
        double denom = d00 * d11 - d01 * d01;
        if (Math.abs(denom) < 1.0e-12) {
            out[0] = 1.0;
            out[1] = 0.0;
            out[2] = 0.0;
            return;
        }
        double v = (d11 * d20 - d01 * d21) / denom;
        double w = (d00 * d21 - d01 * d20) / denom;
        out[0] = 1.0 - v - w;
        out[1] = v;
        out[2] = w;
    }

    private static double sq(double x, double y, double z) {
        return x * x + y * y + z * z;
    }
}
