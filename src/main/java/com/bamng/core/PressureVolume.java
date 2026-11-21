package com.bamng.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * E1: a closed triangle soup with a target gauge pressure.
 *
 * <p>This is the blueprint's minimal pressure volume: volume from the
 * divergence theorem, absolute pressure = gauge + 101325 Pa, force per face
 * split equally between its three nodes.  Callers must run {@link #check()}
 * before enabling it; an open or concave volume is rejected rather than
 * producing "ghost force".</p>
 */
public final class PressureVolume {
    public static final double ATMOSPHERE = 101_325.0;

    public final List<SoftTriangle> triangles = new ArrayList<>();
    public double gaugePressure;
    public double volume;
    public boolean closed;
    public boolean convex;

    public PressureVolume(double gaugePressure) {
        this.gaugePressure = gaugePressure;
    }

    /** Divergence theorem: V = Σ dot(a, cross(b, c)) / 6. */
    public double computeVolume(SoftNode[] nodes) {
        double sum = 0.0;
        for (SoftTriangle triangle : triangles) {
            if (triangle.broken) {
                continue;
            }
            SoftNode a = nodes[triangle.a];
            SoftNode b = nodes[triangle.b];
            SoftNode c = nodes[triangle.c];
            double term = a.position.x * (b.position.y * c.position.z - b.position.z * c.position.y)
                    + a.position.y * (b.position.z * c.position.x - b.position.x * c.position.z)
                    + a.position.z * (b.position.x * c.position.y - b.position.y * c.position.x);
            // A flipped normal without a flipped winding still leaves the
            // divergence sum negative; keep volume and force direction in sync.
            sum += triangle.flipped ? -term : term;
        }
        this.volume = sum / 6.0;
        return this.volume;
    }

    /**
     * Apply pressure force.  The normal must point outward; a face whose
     * normal points inward will push the wrong way, which {@link #check()}
     * catches through the convexity test.
     */
    public void applyForce(SoftNode[] nodes) {
        if (!closed || !convex) {
            return;
        }
        // Only gauge pressure is applied.  The external atmosphere pushes
        // equally on every face, so using absolute pressure here would inflate
        // every closed shell regardless of its target pressure.
        double absolute = Math.max(0.0, gaugePressure);
        for (SoftTriangle triangle : triangles) {
            if (triangle.broken || triangle.degenerate()) {
                continue;
            }
            double force = absolute * triangle.area;
            double fx = triangle.nx * force / 3.0;
            double fy = triangle.ny * force / 3.0;
            double fz = triangle.nz * force / 3.0;
            nodes[triangle.a].addForce(fx, fy, fz);
            nodes[triangle.b].addForce(fx, fy, fz);
            nodes[triangle.c].addForce(fx, fy, fz);
        }
    }

    /** E1 runtime: recompute triangle geometry and volume, then apply gauge pressure for this substep. */
    public void step(SoftNode[] nodes) {
        if (!closed || !convex) {
            return;
        }
        // computeVolume reads current node positions, but applyForce uses the
        // cached area and outward normal.  Waiting until the end of the tick
        // left every substep using the previous tick's tyre normals, which
        // made the chamber force lag the contact patch and injected wobble.
        for (SoftTriangle triangle : triangles) {
            if (!triangle.broken) {
                triangle.updateGeometry(nodes);
            }
        }
        computeVolume(nodes);
        applyForce(nodes);
    }

    /** Precheck: every edge exactly twice, and adjacent normals not concave. */
    public boolean check(SoftNode[] nodes) {
        Map<Long, Integer> edges = new HashMap<>();
        for (SoftTriangle triangle : triangles) {
            if (triangle.broken) {
                continue;
            }
            addEdge(edges, triangle.a, triangle.b);
            addEdge(edges, triangle.b, triangle.c);
            addEdge(edges, triangle.c, triangle.a);
        }
        closed = true;
        for (int count : edges.values()) {
            if (count != 2) {
                closed = false;
                break;
            }
        }

        convex = true;
        for (int i = 0; i < triangles.size() && convex; i++) {
            SoftTriangle first = triangles.get(i);
            for (int j = i + 1; j < triangles.size(); j++) {
                SoftTriangle second = triangles.get(j);
                if (sharesEdge(first, second)) {
                    double dot = first.nx * second.nx + first.ny * second.ny + first.nz * second.nz;
                    if (dot < -0.05) {
                        convex = false;
                        break;
                    }
                }
            }
        }
        return closed && convex;
    }

    private static void addEdge(Map<Long, Integer> edges, int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        long key = ((long) lo << 32) | (hi & 0xffffffffL);
        edges.merge(key, 1, Integer::sum);
    }

    private static boolean sharesEdge(SoftTriangle x, SoftTriangle y) {
        return sameEdge(x.a, x.b, y) || sameEdge(x.b, x.c, y) || sameEdge(x.c, x.a, y);
    }

    private static boolean sameEdge(int a, int b, SoftTriangle y) {
        return (a == y.a && b == y.b) || (a == y.b && b == y.a)
                || (a == y.b && b == y.c) || (a == y.c && b == y.b)
                || (a == y.c && b == y.a) || (a == y.a && b == y.c);
    }
}
