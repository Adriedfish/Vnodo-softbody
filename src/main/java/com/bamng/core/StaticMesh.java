package com.bamng.core;

import java.util.ArrayList;
import java.util.List;

/**
 * C11: static triangle mesh backend.
 *
 * <p>The map's collision mesh is a triangle soup whose vertices never move.
 * A node is tested against it with the same closest-point logic as the soft
 * shell, but the triangle's inverse mass is zero, so the reaction is one-way.
 * The caller supplies outward normals; no winding repair is attempted at
 * runtime.</p>
 */
public final class StaticMesh {
    public static final class Triangle {
        public final double ax, ay, az;
        public final double bx, by, bz;
        public final double cx, cy, cz;
        public final double nx, ny, nz;
        public final double area;
        public final String material;

        public Triangle(double ax, double ay, double az,
                        double bx, double by, double bz,
                        double cx, double cy, double cz,
                        double nx, double ny, double nz,
                        String material) {
            this.ax = ax; this.ay = ay; this.az = az;
            this.bx = bx; this.by = by; this.bz = bz;
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.nx = nx; this.ny = ny; this.nz = nz;
            double[] normal = cross(ax, ay, az, bx, by, bz, cx, cy, cz);
            this.area = 0.5 * length(normal[0], normal[1], normal[2]);
            this.material = material;
        }

        public boolean degenerate() {
            return area < 1.0e-8;
        }
    }

    public final List<Triangle> triangles = new ArrayList<>();

    public void add(double ax, double ay, double az,
                    double bx, double by, double bz,
                    double cx, double cy, double cz,
                    double nx, double ny, double nz,
                    String material) {
        double len = length(nx, ny, nz);
        if (len < 1.0e-9) {
            return;
        }
        triangles.add(new Triangle(ax, ay, az, bx, by, bz, cx, cy, cz,
                nx / len, ny / len, nz / len, material));
    }

    /** Push a node out of the front side of the static surface. */
    public boolean resolve(SoftNode node, double thickness) {
        double bestDepth = 0.0;
        double bestNx = 0.0;
        double bestNy = 0.0;
        double bestNz = 0.0;
        boolean hit = false;
        for (Triangle triangle : triangles) {
            if (triangle.degenerate()) {
                continue;
            }
            Vec3 closest = new Vec3();
            double signed = TriangleCollision.signedDistance(
                    node.position.x, node.position.y, node.position.z,
                    triangle.ax, triangle.ay, triangle.az,
                    triangle.bx, triangle.by, triangle.bz,
                    triangle.cx, triangle.cy, triangle.cz,
                    triangle.nx, triangle.ny, triangle.nz, closest);
            double band = node.radius + thickness;
            if (signed > 0.0 && signed < band) {
                double depth = band - signed;
                if (depth > bestDepth) {
                    bestDepth = depth;
                    bestNx = triangle.nx;
                    bestNy = triangle.ny;
                    bestNz = triangle.nz;
                    hit = true;
                }
            }
        }
        if (hit) {
            node.position.x += bestNx * bestDepth;
            node.position.y += bestNy * bestDepth;
            node.position.z += bestNz * bestDepth;
            double vn = node.velocity.x * bestNx + node.velocity.y * bestNy
                    + node.velocity.z * bestNz;
            if (vn < 0.0) {
                node.velocity.x -= bestNx * vn;
                node.velocity.y -= bestNy * vn;
                node.velocity.z -= bestNz * vn;
            }
            return true;
        }
        return false;
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    private static double[] cross(double ax, double ay, double az,
                                  double bx, double by, double bz,
                                  double cx, double cy, double cz) {
        // Not used; kept for callers that want a raw cross product.
        return new double[]{
                (by - ay) * (cz - az) - (bz - az) * (cy - ay),
                (bz - az) * (cx - ax) - (bx - ax) * (cz - az),
                (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        };
    }
}
