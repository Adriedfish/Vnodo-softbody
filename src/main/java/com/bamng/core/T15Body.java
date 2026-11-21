package com.bamng.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T15-derived soft-body lattice. Dimensions come from Vettura's public vehicle
 * layout data; this class contains no copied Vettura source code.
 */
public final class T15Body {
    public static final double MASS = 1450.0;
    public static final double HALF_TRACK = 0.712;
    public static final double WHEEL_RADIUS = 0.36;
    public static final double WHEELBASE = 2.85;

    public final SoftBodyWorld world = new SoftBodyWorld();
    public final List<WheelSuspension> wheels = new ArrayList<>();
    public final double baseY;

    private T15Body(double baseY, double spacing) {
        this.baseY = baseY;
        buildNodes(spacing);
        buildBeams(0.78, 10);
        assignMass(MASS);
        buildWheels();
    }

    public static T15Body create(double baseY) {
        return new T15Body(baseY, 0.36);
    }

    private void buildNodes(double spacing) {
        // T15 body boxes: f0, f1, r0, r1, y0, y1.
        List<double[]> boxes = List.of(
                new double[]{2.275, 2.40, -0.86, 0.86, 0.0, 0.62},
                new double[]{1.65, 2.275, -0.845, 0.845, 0.0, 1.03},
                new double[]{0.95, 1.65, -0.845, 0.845, 0.0, 1.07},
                new double[]{-0.30, 0.95, -0.845, 0.845, 0.0, 1.07},
                new double[]{0.55, 0.80, -0.80, 0.80, 1.07, 1.50},
                new double[]{-0.30, 0.55, -0.80, 0.80, 1.07, 1.845},
                new double[]{-2.255, -0.36, -0.845, 0.845, 0.0, 0.76},
                new double[]{-0.40, -0.30, -0.845, 0.845, 0.76, 1.11},
                new double[]{-2.255, -0.36, 0.757, 0.845, 0.76, 1.11},
                new double[]{-2.255, -0.36, -0.845, -0.757, 0.76, 1.11},
                new double[]{-1.74, -0.76, 0.56, 0.757, 0.76, 0.89},
                new double[]{-1.74, -0.76, -0.757, -0.56, 0.76, 0.89},
                new double[]{-2.37, -2.255, -0.88, 0.88, 0.0, 0.58}
        );

        Map<String, Integer> dedupe = new HashMap<>();
        for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
            double[] b = boxes.get(boxIndex);
            String part = partForBox(boxIndex);
            int nf = samples(b[1] - b[0], spacing);
            int nr = samples(b[3] - b[2], spacing);
            int ny = samples(b[5] - b[4], spacing);
            for (int i = 0; i <= nf; i++) {
                double f = b[0] + (b[1] - b[0]) * i / nf;
                for (int j = 0; j <= nr; j++) {
                    double r = b[2] + (b[3] - b[2]) * j / nr;
                    for (int k = 0; k <= ny; k++) {
                        double y = b[4] + (b[5] - b[4]) * k / ny;
                        String key = String.format("%.4f,%.4f,%.4f", f, r, y);
                        if (dedupe.containsKey(key)) {
                            continue;
                        }
                        int index = world.nodes.size();
                        SoftNode node = new SoftNode(index);
                        node.part = part;
                        node.group0 = groupFor(part);
                        node.position.set(f, baseY + y, r);
                        node.previous.set(node.position);
                        node.rest.set(node.position);
                        node.targetX = f;
                        node.targetY = baseY + y;
                        node.targetZ = r;
                        node.setMass(1.0);
                        // The cab, bed and chassis follow the truck rigidly.
                        // Only the bumper, hood and tailgate are free to
                        // crush; anchoring those would drag them through
                        // walls instead of letting the barrier do the work.
                        node.anchored = !"front".equals(node.group0)
                                && !"tailgate".equals(node.group0);
                        node.anchorStiffness = 120_000.0;
                        node.anchorDamping = 700.0;
                        world.addNode(node);
                        dedupe.put(key, index);
                    }
                }
            }
        }
    }

    private static String groupFor(String part) {
        return switch (part) {
            case "front_bumper", "hood" -> "front";
            case "tailgate" -> "tailgate";
            case "bed_floor", "bed_bulkhead", "bed_side_l", "bed_side_r",
                 "bed_inner_l", "bed_inner_r" -> "bed";
            default -> "body";
        };
    }

    private static String partForBox(int boxIndex) {        return switch (boxIndex) {
            case 0 -> "front_bumper";
            case 1 -> "hood";
            case 2 -> "cab_front";
            case 3, 4, 5 -> "cab";
            case 6 -> "bed_floor";
            case 7 -> "bed_bulkhead";
            case 8 -> "bed_side_l";
            case 9 -> "bed_side_r";
            case 10 -> "bed_inner_l";
            case 11 -> "bed_inner_r";
            case 12 -> "tailgate";
            default -> "body";
        };
    }

    private static int samples(double length, double spacing) {
        return Math.max(1, (int) Math.round(Math.abs(length) / spacing));
    }

    private void buildBeams(double maxDistance, int maxNeighbors) {
        SoftNode[] nodes = world.nodeArray();
        Set<Long> used = new HashSet<>();
        double maxSq = maxDistance * maxDistance;
        for (int i = 0; i < nodes.length; i++) {
            // Gather nearest neighbours, then connect only those close enough.
            int[] best = new int[maxNeighbors];
            double[] bestD = new double[maxNeighbors];
            java.util.Arrays.fill(bestD, Double.POSITIVE_INFINITY);
            for (int j = 0; j < nodes.length; j++) {
                if (i == j) {
                    continue;
                }
                double dx = nodes[j].position.x - nodes[i].position.x;
                double dy = nodes[j].position.y - nodes[i].position.y;
                double dz = nodes[j].position.z - nodes[i].position.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > maxSq) {
                    continue;
                }
                int slot = -1;
                for (int k = 0; k < maxNeighbors; k++) {
                    if (d2 < bestD[k]) {
                        slot = k;
                        break;
                    }
                }
                if (slot >= 0) {
                    for (int k = maxNeighbors - 1; k > slot; k--) {
                        best[k] = best[k - 1];
                        bestD[k] = bestD[k - 1];
                    }
                    best[slot] = j;
                    bestD[slot] = d2;
                }
            }
            for (int k = 0; k < maxNeighbors; k++) {
                if (!Double.isFinite(bestD[k])) {
                    break;
                }
                int j = best[k];
                long key = i < j ? (((long) i << 32) | (j & 0xffffffffL)) : (((long) j << 32) | (i & 0xffffffffL));
                if (!used.add(key)) {
                    continue;
                }
                double rest = Math.sqrt(bestD[k]);
                double stiffness = 1.0;
                double yieldStrain = 0.20;
                double breakStrain = 0.85;
                SoftBeam beam = new SoftBeam(i, j, rest, stiffness, yieldStrain, breakStrain);
                configureBeamDamage(beam, nodes[i], nodes[j]);
                world.beams.add(beam);
            }
        }
        world.damage.linkPart("tailgate", "tailgate");
        world.damage.linkDeformPart("tailgate", "tailgate");
        world.damage.register(world.beams);
    }

    private static void configureBeamDamage(SoftBeam beam, SoftNode a, SoftNode b) {
        // Values are metadata for the future force solver. They are already
        // evaluated every substep for diagnostics and threshold tuning.
        beam.beamSpring = 160_000.0;
        beam.beamDamp = 140.0;
        beam.beamDeform = 32_700.0;
        beam.beamStrength = 220_000.0;
        // The authored shell is the visible vehicle, not the collision lattice.
        // Deleting triangles from this proxy is what produced the "没蒙上" holes
        // and the vanished nose after a light steer/contact.  Beams may still
        // break and drive plastic deformation, but the Vettura mesh triangles
        // stay authored and are deformed by BamngSkinBuffer.
        beam.disableTriangleBreaking = true;
        beam.disableMeshBreaking = true;

        // Front impact group: only the bumper/hood/front-cab lattice.  A
        // frontal hit must not fire the tailgate group, otherwise the whole
        // rear assembly disappears and the visual body explodes.
        boolean front = Math.max(a.rest.x, b.rest.x) > 1.05
                && Math.max(a.rest.x, b.rest.x) < 2.45;
        if (front) {
            beam.breakGroups.add("front");
            // The crumple zone must actually absorb energy.  A 1.4 MN/m,
            // 220 kN beam only moves about 4 cm at 100 km/h and reads as
            // "nothing happened".  Front-only softening keeps the cab and
            // bed rigid while letting the bumper fold and then tear.
            // 2026-11-21: second softening pass.  Compression is now much
            // softer than extension so the nose folds inward instead of
            // feeling like a rigid wall, while the higher tensile stiffness
            // still resists being pulled apart.  The yield force is low
            // enough to enter the crush plateau almost immediately; the
            // strength stays above the yield peak so the front does not
            // shed whole groups of beams.
            beam.beamSpring = 60_000.0;
            beam.beamSpringCompress = 32_000.0;
            beam.beamDamp = 150.0;
            beam.beamDeform = 1_800.0;
            beam.beamStrength = 38_000.0;
            beam.plasticityRate = 0.65;
            beam.deformLimit = 0.65;
            // Passive group: a broken front beam must not cascade the whole
            // front lattice.  Only beams whose own strain exceeds the limit
            // break, which is the difference between ductile and brittle.
            beam.breakGroupType = 1;
            beam.breakStrain = 0.40;
            // Keep the shell connected while it folds.  The old front
            // group deleted 300+ triangles on a 100 km/h hit, which read as
            // "the front disappeared" rather than a crushed bumper.
            beam.disableMeshBreaking = true;
            beam.disableTriangleBreaking = true;
        }
        if ("tailgate".equals(a.part) || "tailgate".equals(b.part)) {
            // Passive tailgate group: it must not cascade the whole rear
            // assembly from a frontal hit. A rear-impact bridge can fire this
            // group explicitly later.
            beam.breakGroups.add("tailgate");
            beam.breakGroupType = 1;
            beam.deformGroups.add("tailgate");
            beam.deformationTriggerRatio = 0.12;
            beam.breakStrain = 0.60;
            beam.disableTriangleBreaking = true;
        } else {
            // Ordinary body lattice beams are not group triggers yet. This
            // avoids turning one bumper hit into an instant whole-cab collapse.
            beam.breakGroupType = 1;
        }
    }

    private void assignMass(double totalMass) {
        double mass = totalMass / Math.max(1, world.nodes.size());
        for (SoftNode node : world.nodes) {
            node.setMass(mass);
        }
    }

    private void buildWheels() {
        // T15: front axle forward (+X); use half the wheelbase around the body centre.
        double frontX = WHEELBASE * 0.5;
        double rearX = -WHEELBASE * 0.5;
        addWheel(frontX, HALF_TRACK);
        addWheel(frontX, -HALF_TRACK);
        addWheel(rearX, HALF_TRACK);
        addWheel(rearX, -HALF_TRACK);
    }

    private void addWheel(double x, double z) {
        int attach = nearestNode(x, 0.0, z);
        WheelSuspension wheel = new WheelSuspension(world, attach, x, z, WHEEL_RADIUS);
        wheels.add(wheel);
        world.wheels.add(wheel);
    }

    public int nearestNode(double x, double y, double z) {
        int best = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (SoftNode node : world.nodes) {
            double dx = node.position.x - x;
            double dy = node.position.y - (baseY + y);
            double dz = node.position.z - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = node.index;
            }
        }
        return best;
    }

    public double speed() {
        Vec3 c = world.center();
        double vx = 0.0;
        double vz = 0.0;
        for (SoftNode node : world.nodes) {
            vx += node.velocity.x * node.mass;
            vz += node.velocity.z * node.mass;
        }
        double total = 0.0;
        for (SoftNode node : world.nodes) {
            total += node.mass;
        }
        if (total > 0.0) {
            vx /= total;
            vz /= total;
        }
        return Math.sqrt(vx * vx + vz * vz);
    }
}
