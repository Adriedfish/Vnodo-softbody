package com.bamng.core;

import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A vehicle-agnostic BAMNG lattice built from Vettura's public layout/spec.
 * No mesh names, part names or T15 dimensions are hard-coded here, so an
 * imported Vettura vehicle gets the same soft-body topology from its own
 * layout.
 */
public final class VehicleBody {
    public final SoftBodyWorld world = new SoftBodyWorld();
    public final List<WheelSuspension> wheels = new ArrayList<>();
    public final PressureVolume pressure = new PressureVolume(0.0);
    public final List<SoftTire> tires = new ArrayList<>();
    public final EventTimeline timeline = new EventTimeline();
    public final double baseY;
    public final double front;
    public final double rear;
    public final double halfWidth;
    public final double top;
    /** One whole-vehicle displacement envelope, never a per-panel ceiling. */
    public final double envelope;
    private int nx;
    private int ny;
    private int nz;

    private VehicleBody(VehicleLayout layout, VehicleSpec spec, double baseY) {
        this.baseY = baseY;
        this.front = Math.max(0.6, layout.front);
        this.rear = Math.min(-0.6, layout.rear);
        this.halfWidth = Math.max(0.4, layout.halfWidth);
        this.top = Math.max(1.0, layout.top);
        // The only hard deformation ceiling is the vehicle's own size.  A
        // 65% length envelope is large enough to fold a nose into the cab on a
        // 100 km/h hit, while still preventing a node from leaving the whole
        // truck.  Per-region / per-beam ceilings are deliberately not used.
        this.envelope = Math.max(1.0, (this.front - this.rear) * 0.65);
        this.world.globalPlasticLimit = this.envelope;
        // M1–M4 use a real mass-spring lattice.  The truck entity remains the
        // authoritative rigid body; the shell is attached at its core nodes
        // and is free to crush at the front / rear / tailgate.
        this.world.gravityScale = 1.0;
        // This is a client-side crush shell following Vettura's rigid
        // vehicle, not a suspension simulator.  Strong global velocity loss
        // prevents a hit from turning the sparse fallback lattice into a
        // ringing tuning fork while its plastic beams retain the dent.
        this.world.globalDamping = 3.2;
        // The authored Vettura truck owns aero and the actual rigid-body
        // motion.  Running drag/lift on the hidden crush shell was 464
        // triangle evaluations with sqrt+asin on every substep.
        this.world.airDensity = 0.0;
        buildNodes(layout);
        buildBeams(layout);
        buildTriangles();
        assignMass(spec.mass);
        this.pressure.triangles.addAll(this.world.triangles);
        this.pressure.computeVolume(this.world.nodeArray());
        this.pressure.check(this.world.nodeArray());
        this.world.pressureVolumes.add(this.pressure);
        // Tires are separate closed chambers; they must not be mixed into the
        // chassis pressure volume, or the shell's manifold check fails.
        buildWheels(spec);
        this.applyTuning();
        this.world.damage.register(this.world.beams);
        this.world.damage.registerTriangles(this.world.triangles, this.world.nodeArray());
        
    }

    public static VehicleBody create(VehicleLayout layout, VehicleSpec spec, double baseY) {
        return new VehicleBody(layout, spec, baseY);
    }

    private void buildNodes(VehicleLayout layout) {
        double length = this.front - this.rear;
        double width = this.halfWidth * 2.0;
        this.nx = clamp((int) Math.round(length / 0.46), 4, 9);
        this.ny = clamp((int) Math.round(this.top / 0.46), 2, 4);
        this.nz = clamp((int) Math.round(width / 0.46), 3, 6);
        for (int ix = 0; ix <= this.nx; ix++) {
            double x = this.rear + length * ix / this.nx;
            for (int iy = 0; iy <= this.ny; iy++) {
                double y = 0.04 + (this.top - 0.04) * iy / this.ny;
                for (int iz = 0; iz <= this.nz; iz++) {
                    double z = -this.halfWidth + width * iz / this.nz;
                    SoftNode node = new SoftNode(this.world.nodes.size());
                    node.part = partFor(x, layout);
                    node.group0 = groupFor(node.part);
                    node.position.set(x, this.baseY + y, z);
                    node.previous.set(node.position);
                    node.rest.set(node.position);
                    node.setMass(1.0);
                    // No per-node 0.90 m ceiling.  A node may travel as far as
                    // the whole-vehicle envelope; the clamp in
                    // BamngVetturaPhysicsClient is the final authority.
                    node.plasticLimit = this.envelope;
                    // Core body is the kinematic attachment.  The bumper,
                    // hood and tailgate deliberately stay unanchored so they
                    // can crush against a wall instead of being teleported
                    // through it with the rigid chassis.
                    node.anchored = "body".equals(node.group0)
                            || "bed".equals(node.group0)
                            || "rear".equals(node.group0);
                    // The truck entity is still the authority, but a 120 kN/m
                    // anchor made the whole shell read as one welded block.
                    // 95 kN/m lets the body pitch/settle a few centimetres on
                    // impact while the spawn settle test stays flat.
                    // The cab band sits directly behind the hood.  Anchoring it
                    // at the chassis stiffness made the windscreen frame and
                    // the whole observation-window area read as one welded
                    // block.  30 kN/m lets that band collapse 15-20 % and then
                    // settle, instead of ignoring the hit entirely.
                    double cabBandStart = this.front
                            - Math.max(1.0, this.front - this.rear) * 0.62;
                    boolean cabBand = "body".equals(node.group0) && x > cabBandStart;
                    node.anchorStiffness = cabBand ? 30_000.0 : 95_000.0;
                    node.anchorDamping = cabBand ? 760.0 : 520.0;
                    this.world.addNode(node);
                }
            }
        }
    }

    private static String groupFor(String part) {
        return switch (part) {
            case "front_bumper", "hood" -> "front";
            case "tailgate" -> "tailgate";
            case "bed_floor" -> "bed";
            case "rear_body" -> "rear";
            default -> "body";
        };
    }

    private String partFor(double x, VehicleLayout layout) {
        double length = Math.max(1.0, this.front - this.rear);
        // Proportional, not T15-specific.  The old 0.38 / 1.45 / 1.55 m
        // constants made a longer or shorter imported truck classify half of
        // its nose as "body"; every Vettura layout now maps its own front and
        // rear spans.
        if (x > this.front - length * 0.080) {
            return "front_bumper";
        }
        if (x > this.front - length * 0.300) {
            return "hood";
        }
        if (x < this.rear + length * 0.080) {
            return layout.hasBed() ? "tailgate" : "rear";
        }
        if (x < this.rear + length * 0.320) {
            return layout.hasBed() ? "bed_floor" : "rear_body";
        }
        return "body";
    }

    private void buildBeams(VehicleLayout layout) {
        SoftNode[] nodes = this.world.nodeArray();
        Set<Long> used = new HashSet<>();
        // Keep the neighbour radius proportional to the actual cell size.  A
        // fixed 0.85 m radius left gaps on long-wheelbase imported trucks.
        double cell = Math.max(0.46,
                Math.max((this.front - this.rear) / Math.max(1, this.nx),
                        this.top / Math.max(1, this.ny)));
        double maxDistance = cell * 1.60;
        double maxSq = maxDistance * maxDistance;
        for (int i = 0; i < nodes.length; i++) {
            int[] best = new int[14];
            double[] bestD = new double[14];
            java.util.Arrays.fill(bestD, Double.POSITIVE_INFINITY);
            for (int j = 0; j < nodes.length; j++) {
                if (i == j) {
                    continue;
                }
                double dx = nodes[j].rest.x - nodes[i].rest.x;
                double dy = nodes[j].rest.y - nodes[i].rest.y;
                double dz = nodes[j].rest.z - nodes[i].rest.z;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > maxSq) {
                    continue;
                }
                int slot = -1;
                for (int k = 0; k < best.length; k++) {
                    if (d2 < bestD[k]) {
                        slot = k;
                        break;
                    }
                }
                if (slot >= 0) {
                    for (int k = best.length - 1; k > slot; k--) {
                        best[k] = best[k - 1];
                        bestD[k] = bestD[k - 1];
                    }
                    best[slot] = j;
                    bestD[slot] = d2;
                }
            }
            for (int k = 0; k < best.length; k++) {
                if (!Double.isFinite(bestD[k])) {
                    break;
                }
                int j = best[k];
                long key = i < j ? (((long) i << 32) | (j & 0xffffffffL))
                        : (((long) j << 32) | (i & 0xffffffffL));
                if (!used.add(key)) {
                    continue;
                }
                double rest = Math.sqrt(bestD[k]);
                SoftBeam beam = new SoftBeam(i, j, rest, 1.0, 0.20, 0.85);
                configureBeam(beam, nodes[i], nodes[j], layout);
                this.world.beams.add(beam);
            }
        }
        if (layout.hasBed()) {
            this.world.damage.linkPart("tailgate", "tailgate");
            this.world.damage.linkDeformPart("tailgate", "tailgate");
        }
        this.world.damage.register(this.world.beams);
    }

    private void configureBeam(SoftBeam beam, SoftNode a, SoftNode b, VehicleLayout layout) {
        // A generic layout has no material map, so it must be conservative:
        // use a compliant compression response and near-critical damping.
        // The former 320 kN/m / 180 Ns/m values required 48--60 substeps and
        // returned impact energy as an obvious "piano string" rebound.
        // User's latest calibration: double the previous five-times-harder
        // crash setting, restoring the authored spring/yield values.
        beam.crashSpringScale = 1.0;
        beam.beamSpring = 80_000.0;
        beam.beamSpringCompress = 40_000.0;
        beam.beamDamp = 520.0;
        beam.beamDeform = 8_000.0;
        beam.beamStrength = 95_000.0;
        beam.forceDamage = true;
        // The generic frame is a fallback lattice.  Give it the same bounded
        // plastic flow as the hand-authored T15 map instead of the old 0.35
        // defaults, otherwise a soft spring yields for one frame and then
        // locks because the rest length barely moves.
        beam.plasticityRate = 0.42;
        beam.deformLimit = 1.0;

        double cx = (a.rest.x + b.rest.x) * 0.5;
        if (cx > this.front * 0.40) {
            beam.breakGroups.add("front");
            beam.breakGroupType = 1;
            // Progressive front crumple zone.  The 0.40 threshold covers the
            // whole authored nose, not only the last 1.1 m, so the bumper,
            // hood and inner rail form a layered crush instead of one narrow
            // hinge.  Compression is much softer than extension; the beam
            // keeps its shortened rest length after yield.
            // Authored front crush stiffness and yield force restored.
            beam.beamSpring = 26_000.0;
            beam.beamSpringCompress = 13_000.0;
            beam.crashSpringScale = 1.0;
            beam.beamDamp = 145.0;
            beam.beamStrength = 32_000.0;
            beam.beamDeform = 1_200.0 + frontYieldJitter(a, b);
            beam.plasticityRate = 0.60;
            // Discrete fold events: ~4.5 cm per buckle, then +0.4 kN local
            // hardening.  Only the front crush group uses this; chassis and
            // suspension beams keep the continuous plastic model.
            beam.foldStep = 0.045;
            beam.foldHardening = 400.0;
            beam.deformLimit = 1.0;
            beam.breakStrain = 0.45;
        }
        if (layout.hasBed() && ("tailgate".equals(a.part) || "tailgate".equals(b.part))) {
            beam.breakGroups.add("tailgate");
            beam.breakGroupType = 1;
            beam.deformGroups.add("tailgate");
            beam.deformationTriggerRatio = 0.12;
            beam.beamStrength = 220_000.0;
            beam.beamDeform = 60_000.0;
            beam.disableMeshBreaking = true;
            beam.disableTriangleBreaking = true;
        } else {
            beam.breakGroupType = 1;
        }

        // Crash-only regional compliance.  The normal spring stays authored;
        // only the plastic window (a real wall impact) gets the softer value.
        // User's exact calibration: bumper 0.80, hood 0.50, body/cab/engine
        // 0.70, bed floor 0.90, rear body 0.80, tailgate 0.70.  Road-wheel
        // tethers stay 1.00 so the wheel is still a rigid visual part and only
        // the damage group detaches it.  Lowering beamDeform also lowers the
        // yield force in the same crash window; beamStrength is deliberately
        // untouched so the part folds before it tears off.
        String region = beamRegion(a, b);
        beam.region = region;
        beam.baseDeform = beam.beamDeform;
        double scale = regionScale(region);
        beam.crashSpringScale = scale;
        beam.beamDeform = beam.baseDeform * scale;
    }

    /**
     * Region of a beam, chosen from its endpoint labels.  A beam crossing two
     * regions belongs to the softer authored panel (front bumper, then hood,
     * then bed/rear, then tailgate) so the seam does not remain a hard hinge.
     */
    private static String beamRegion(SoftNode a, SoftNode b) {
        String pa = a.part == null ? "" : a.part;
        String pb = b.part == null ? "" : b.part;
        if (pa.startsWith("tire") || pb.startsWith("tire")
                || pa.startsWith("wheel") || pb.startsWith("wheel")) {
            return "wheel";
        }
        if ("tailgate".equals(pa) || "tailgate".equals(pb)) {
            return "tailgate";
        }
        if ("front_bumper".equals(pa) || "front_bumper".equals(pb)) {
            return "front_bumper";
        }
        if ("hood".equals(pa) || "hood".equals(pb)) {
            return "hood";
        }
        if ("bed_floor".equals(pa) || "bed_floor".equals(pb)) {
            return "bed_floor";
        }
        if ("rear_body".equals(pa) || "rear_body".equals(pb)) {
            return "rear_body";
        }
        return "body";
    }

    /**
     * Authored-part crash compliance multiplier.  Values come from the
     * persisted Vnodo profile, with -Dvnodo.region.* (or the legacy
     * -Dbamng.region.*) retained for headless probes.  Values are clamped to
     * 0.10..1.50 so a typo cannot turn the truck into paper or steel.
     */
    private static double regionScale(String region) {
        return VnodoConfig.scale(region);
    }

    /**
     * Re-apply the live Vnodo profile to every built beam.  Called once at
     * construction and again whenever the settings screen changes a slider.
     * The authored yield force is stored separately so repeated edits never
     * compound the multiplier.
     */
    public void applyTuning() {
        for (SoftBeam beam : this.world.beams) {
            if (beam.baseDeform < 0.0) {
                beam.baseDeform = beam.beamDeform;
            }
            if (beam.region == null || beam.region.isBlank()) {
                beam.region = "body";
            }
            double scale = VnodoConfig.scale(beam.region);
            beam.crashSpringScale = scale;
            beam.beamDeform = beam.baseDeform * scale;
        }
    }

    /**
     * Build the outer shell quads.  Only the hull is triangulated, not the
     * interior lattice, so the collision surface stays cheap and each
     * triangle inherits a meaningful node group.
     */
    private void buildTriangles() {
        if (this.nx <= 0 || this.ny <= 0 || this.nz <= 0) {
            return;
        }
        for (int ix = 0; ix < this.nx; ix++) {
            for (int iz = 0; iz < this.nz; iz++) {
                addQuad(ix, this.ny, iz, ix + 1, this.ny, iz,
                        ix + 1, this.ny, iz + 1, ix, this.ny, iz + 1);
                addQuad(ix, 0, iz, ix, 0, iz + 1,
                        ix + 1, 0, iz + 1, ix + 1, 0, iz);
            }
        }
        for (int iy = 0; iy < this.ny; iy++) {
            for (int iz = 0; iz < this.nz; iz++) {
                addQuad(this.nx, iy, iz, this.nx, iy + 1, iz,
                        this.nx, iy + 1, iz + 1, this.nx, iy, iz + 1);
                addQuad(0, iy, iz, 0, iy, iz + 1,
                        0, iy + 1, iz + 1, 0, iy + 1, iz);
            }
            for (int ix = 0; ix < this.nx; ix++) {
                addQuad(ix, iy, this.nz, ix, iy + 1, this.nz,
                        ix + 1, iy + 1, this.nz, ix + 1, iy, this.nz);
                addQuad(ix, iy, 0, ix + 1, iy, 0,
                        ix + 1, iy + 1, 0, ix, iy + 1, 0);
            }
        }

        SoftNode[] nodes = this.world.nodeArray();
        Vec3 c = new Vec3(this.rear + (this.front - this.rear) * 0.5,
                this.baseY + this.top * 0.5, 0.0);
        for (SoftTriangle triangle : this.world.triangles) {
            triangle.updateGeometry(nodes);
            triangle.orientOutward(nodes, c.x, c.y, c.z);
            // E2: start with a conservative full-body drag.  Lift stays 0
            // until a definition supplies a real wing/underbody surface.
            triangle.dragCoef = 30.0;
            // E2: a small, real lift coefficient.  100 = flat-plate Cd 1.0,
            // so 8 is Cd 0.08.  Stall is evaluated in SoftBodyWorld.
            triangle.liftCoef = 8.0;
            triangle.stallAngle = 0.55;
        }
    }

    private void addQuad(int ax, int ay, int az, int bx, int by, int bz,
                         int cx, int cy, int cz, int dx, int dy, int dz) {
        int a = gridIndex(ax, ay, az);
        int b = gridIndex(bx, by, bz);
        int c = gridIndex(cx, cy, cz);
        int d = gridIndex(dx, dy, dz);
        this.world.triangles.add(new SoftTriangle(a, b, c));
        this.world.triangles.add(new SoftTriangle(a, c, d));
    }

    private int gridIndex(int ix, int iy, int iz) {
        return ix * (this.ny + 1) * (this.nz + 1) + iy * (this.nz + 1) + iz;
    }

    private void assignMass(double totalMass) {
        double mass = Math.max(100.0, totalMass) / Math.max(1, this.world.nodes.size());
        for (SoftNode node : this.world.nodes) {
            node.setMass(mass);
        }
    }

    private void buildWheels(VehicleSpec spec) {
        for (int i = 0; i < spec.wheels; i++) {
            double x = spec.hubX[i];
            double z = spec.hubZ[i];
            int attach = nearestNode(x, 0.0, z);
            WheelSuspension wheel = new WheelSuspension(this.world, attach, x, z, spec.radius);
            // The chassis bottom sits at baseY + 0.04; that is the height the
            // suspension has to hold above the road.
            wheel.restLength = Math.max(0.0, this.baseY + 0.04 - spec.radius);
            // The wheel spring's only job was to hold this shell off the
            // ground.  Gravity is off and the truck already provides that
            // support, so any spring force here is a second, unstable lift.
            wheel.spring = 0.0;
            wheel.damper = 0.0;
            wheel.maxForce = 0.0;
            // BAMNG is a visual shell over Vettura's authoritative truck.  A
            // wheel here only provides vertical suspension compliance; it must
            // never drive the lattice independently of the real vehicle.
            wheel.driveForce = 0.0;
            wheel.brakeForce = 0.0;
            this.wheels.add(wheel);
            this.world.wheels.add(wheel);
            // E3: procedural closed tire chamber at each hub.
            SoftTire tire = new SoftTire(this.world, x, z, spec.radius,
                    Math.max(0.14, spec.radius * 0.62));
            this.tires.add(tire);
            int chassis = nearestNode(x, 0.0, z);
            attachTire(tire, chassis, i);
        }
    }

    private void attachTire(SoftTire tire, int chassis, int wheelIndex) {
        SoftNode hub = this.world.nodes.get(tire.hubA);
        SoftNode bodyNode = this.world.nodes.get(chassis);
        double dx = hub.rest.x - bodyNode.rest.x;
        double dy = hub.rest.y - bodyNode.rest.y;
        double dz = hub.rest.z - bodyNode.rest.z;
        double rest = Math.max(0.12, Math.sqrt(dx * dx + dy * dy + dz * dz));
        SoftBeam beam = new SoftBeam(tire.hubA, chassis, rest, 1.0, 0.20, 0.85);
        // The old 200 kN / 70 kN wheel tether was effectively a welded strut:
        // it never yielded under a crash, so the authored wheel never moved
        // relative to the chassis.  Keep normal driving secured, but let a
        // hard hit stretch this tether and expose the per-corner group to the
        // damage graph.  The visual wheel remains Vettura's authored mesh.
        beam.beamSpring = 160_000.0;
        beam.beamSpringCompress = 120_000.0;
        beam.beamDamp = 70.0;
        beam.crashSpringScale = 1.0;
        beam.beamStrength = 12_000.0;
        beam.beamDeform = 4_000.0;
        beam.region = "wheel";
        beam.baseDeform = beam.beamDeform;
        beam.crashSpringScale = VnodoConfig.scale("wheel");
        beam.beamDeform = beam.baseDeform * beam.crashSpringScale;
        beam.breakGroups.add(wheelGroup(wheelIndex));
        beam.breakGroupType = 0;
        this.world.beams.add(beam);
    }

    private static String wheelGroup(int index) {
        return switch (index) {
            case 0 -> "wheel_fl";
            case 1 -> "wheel_fr";
            case 2 -> "wheel_rl";
            case 3 -> "wheel_rr";
            default -> "wheel";
        };
    }

    /** Stable ±200 N yield offset derived from the beam endpoints. */
    private static double frontYieldJitter(SoftNode a, SoftNode b) {
        long h = ((long) (a.rest.x * 1000.0) * 73856093L)
                ^ ((long) (a.rest.y * 1000.0) * 19349663L)
                ^ ((long) (a.rest.z * 1000.0) * 83492791L)
                ^ ((long) (b.rest.x * 1000.0) * 2654435761L)
                ^ ((long) (b.rest.y * 1000.0) * 40503L)
                ^ ((long) (b.rest.z * 1000.0) * 12289L);
        return (double) (Math.floorMod(h, 401L) - 200L);
    }

    public int nearestNode(double x, double y, double z) {
        int best = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (SoftNode node : this.world.nodes) {
            double dx = node.rest.x - x;
            double dy = node.rest.y - (this.baseY + y);
            double dz = node.rest.z - z;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = node.index;
            }
        }
        return best;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
