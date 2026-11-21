package com.bamng.core;

/**
 * E3: a procedurally generated soft tire chamber.
 *
 * <p>The tire is a closed lens built from two hub nodes and a ring of tread
 * nodes.  Every tread node is connected to its neighbours, the two hubs are
 * connected by a short axle beam, and the chamber carries a real gauge
 * pressure.  The chamber is not part of the body shell's pressure volume: it
 * is its own closed component, so a tire can be soft and inflated while the
 * chassis stays a separate shell.</p>
 */
public final class SoftTire {
    public final int[] ring;
    public final int hubA;
    public final int hubB;
    public final double radius;
    public final double halfWidth;
    public final PressureVolume chamber = new PressureVolume(180_000.0);

    public SoftTire(SoftBodyWorld world, double hubX, double hubZ, double radius, double halfWidth) {
        this.radius = Math.max(0.18, radius);
        this.halfWidth = Math.max(0.08, halfWidth);
        int segments = 14;
        this.ring = new int[segments];
        double centerY = this.radius;
        double axleY = centerY;
        this.hubA = addNode(world, hubX, axleY, hubZ - this.halfWidth * 0.5, 3.0, "tire_hub");
        this.hubB = addNode(world, hubX, axleY, hubZ + this.halfWidth * 0.5, 3.0, "tire_hub");
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            double x = hubX + Math.cos(angle) * this.radius;
            double y = centerY + Math.sin(angle) * this.radius;
            this.ring[i] = addNode(world, x, y, hubZ, 4.0, "tire_tread");
        }

        // Tread ring: circumferential springs plus a crossed belt for shear.
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % segments;
            // The tire shell is a hidden collision proxy under Vettura's
            // authored wheel.  Its previous 210 kN tread / 600 kN axle
            // stiffness set SoftBodyWorld.recommendedSubsteps from the tire
            // instead of the body and kept every normal drive at 20+ substeps.
            // These values still hold the bead ring, but stay inside the same
            // stability band as the chassis beams.
            beam(world, this.ring[i], this.ring[j], 150_000.0, 90.0, 35_000.0, "tire");
            beam(world, this.ring[i], this.ring[(i + 2) % segments], 70_000.0, 45.0, 18_000.0, "tire");
            // Sidewall spokes to both bead rings.
            beam(world, this.hubA, this.ring[i], 110_000.0, 60.0, 24_000.0, "tire");
            beam(world, this.hubB, this.ring[i], 110_000.0, 60.0, 24_000.0, "tire");
        }
        // Axle: the two beads stay a fixed distance apart.
        beam(world, this.hubA, this.hubB, 150_000.0, 130.0, 80_000.0, "tire");

        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % segments;
            SoftTriangle a = new SoftTriangle(this.hubA, this.ring[i], this.ring[j]);
            SoftTriangle b = new SoftTriangle(this.hubB, this.ring[j], this.ring[i]);
            world.triangles.add(a);
            world.triangles.add(b);
            this.chamber.triangles.add(a);
            this.chamber.triangles.add(b);
        }
        SoftNode[] nodes = world.nodeArray();
        for (SoftTriangle triangle : this.chamber.triangles) {
            triangle.updateGeometry(nodes);
            triangle.orientOutward(nodes, hubX, axleY, hubZ);
        }
        this.chamber.computeVolume(nodes);
        this.chamber.check(nodes);
        world.pressureVolumes.add(this.chamber);
    }

    private static int addNode(SoftBodyWorld world, double x, double y, double z, double mass, String part) {
        SoftNode node = new SoftNode(world.nodes.size());
        node.position.set(x, y, z);
        node.previous.set(node.position);
        node.rest.set(node.position);
        node.part = part;
        node.group0 = "wheel";
        node.selfCollision = false;
        node.staticCollision = true;
        node.setMass(mass);
        node.friction = 0.72;
        node.restitution = 0.03;
        world.addNode(node);
        return node.index;
    }

    private static void beam(SoftBodyWorld world, int a, int b, double spring,
                             double damp, double strength, String group) {
        SoftNode na = world.nodes.get(a);
        SoftNode nb = world.nodes.get(b);
        double dx = nb.rest.x - na.rest.x;
        double dy = nb.rest.y - na.rest.y;
        double dz = nb.rest.z - na.rest.z;
        SoftBeam beam = new SoftBeam(a, b, Math.sqrt(dx * dx + dy * dy + dz * dz), 1.0, 0.20, 0.85);
        beam.beamSpring = spring;
        beam.beamSpringCompress = spring * 0.80;
        beam.beamDamp = damp;
        beam.beamStrength = strength;
        beam.beamDeform = strength * 0.30;
        beam.breakGroups.add(group);
        beam.deformGroups.add(group);
        // A tire is a hidden collision proxy under Vettura's authored wheel.
        // One tread break used to fire the whole "tire" group, delete every
        // tire triangle and leave the visible rim floating. Keep tire damage
        // local and let the authored wheel mesh stay in place.
        beam.breakGroupType = 1;
        beam.disableTriangleBreaking = true;
        world.beams.add(beam);
    }
}
