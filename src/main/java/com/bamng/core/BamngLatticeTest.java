package com.bamng.core;

/** Full-size lattice smoke test: 9x4x6 nodes, 900 kN/m beams, anchors, ground. */
public final class BamngLatticeTest {
    public static void main(String[] args) {
        SoftBodyWorld world = new SoftBodyWorld();
        world.gravityScale = 1.0;
        world.groundY = 0.0;
        int nx = 9, ny = 4, nz = 6;
        int count = (nx + 1) * (ny + 1) * (nz + 1);
        for (int ix = 0; ix <= nx; ix++) {
            for (int iy = 0; iy <= ny; iy++) {
                for (int iz = 0; iz <= nz; iz++) {
                    SoftNode node = new SoftNode(world.nodes.size());
                    node.position.set(-2.37 + 4.77 * ix / nx, 0.86 + 1.0 * iy / ny, -0.88 + 1.76 * iz / nz);
                    node.previous.set(node.position);
                    node.rest.set(node.position);
                    node.targetX = node.position.x;
                    node.targetY = node.position.y;
                    node.targetZ = node.position.z;
                    node.setMass(1450.0 / count);
                    node.group0 = ix > nx - 2 ? "front" : (ix < 2 ? "bed" : "body");
                    node.anchored = !"front".equals(node.group0);
                    world.addNode(node);
                }
            }
        }
        java.util.Set<Long> used = new java.util.HashSet<>();
        SoftNode[] nodes = world.nodeArray();
        for (int i = 0; i < nodes.length; i++) {
            int[] best = new int[14];
            double[] bestD = new double[14];
            java.util.Arrays.fill(bestD, Double.POSITIVE_INFINITY);
            for (int j = 0; j < nodes.length; j++) {
                if (i == j) continue;
                double dx = nodes[j].position.x - nodes[i].position.x;
                double dy = nodes[j].position.y - nodes[i].position.y;
                double dz = nodes[j].position.z - nodes[i].position.z;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d > 0.86 || d < 1e-9) continue;
                int slot = -1;
                for (int k = 0; k < 14; k++) { if (d < bestD[k]) { slot = k; break; } }
                if (slot >= 0) {
                    for (int k = 13; k > slot; k--) { best[k] = best[k - 1]; bestD[k] = bestD[k - 1]; }
                    best[slot] = j; bestD[slot] = d;
                }
            }
            for (int k = 0; k < 14; k++) {
                if (!Double.isFinite(bestD[k])) break;
                int j = best[k];
                long key = i < j ? (((long) i << 32) | (j & 0xffffffffL)) : (((long) j << 32) | (i & 0xffffffffL));
                if (!used.add(key)) continue;
                SoftBeam beam = new SoftBeam(i, j, bestD[k], 1.0, 0.2, 0.85);
                beam.beamSpring = 900_000.0;
                beam.beamSpringCompress = 700_000.0;
                beam.beamDamp = 300.0;
                beam.beamDeform = 30_000.0;
                beam.beamStrength = 90_000.0;
                beam.forceDamage = true;
                world.beams.add(beam);
            }
        }
        world.damage.register(world.beams);
        double maxSpeed = 0.0;
        for (int tick = 0; tick < 120; tick++) {
            world.step(0.05, 60);
            maxSpeed = Math.max(maxSpeed, world.maxSpeed());
            if (world.hasNaN()) throw new AssertionError("NaN at tick " + tick);
            if (tick % 20 == 0) {
                System.out.printf("tick=%d center=%s minY=%.3f maxSpeed=%.3f contacts=%d broken=%d%n",
                        tick, world.center(), world.minY(), world.maxSpeed(), world.lastContacts, world.damage.brokenBeamCount());
            }
        }
        System.out.printf("LATTICE OK nodes=%d beams=%d maxSpeed=%.3f minY=%.3f broken=%d maxDrift=%.3f%n",
                nodes.length, world.beams.size(), maxSpeed, world.minY(),
                world.damage.brokenBeamCount(), world.maxDisplacementFromTargets());
        if (maxSpeed > 1.0) throw new AssertionError("unstable maxSpeed " + maxSpeed);
        if (world.damage.brokenBeamCount() != 0) throw new AssertionError("spontaneous breaks " + world.damage.brokenBeamCount());
        if (world.maxDisplacementFromTargets() > 0.05) throw new AssertionError("drift " + world.maxDisplacementFromTargets());
    }
}
