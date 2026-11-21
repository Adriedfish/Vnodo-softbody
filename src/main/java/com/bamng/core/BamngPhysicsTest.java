package com.bamng.core;

/**
 * Headless M1–M4 checks.  These run without Minecraft / Vettura so the
 * force solver, anchors, ground and block collision can be verified quickly.
 */
public final class BamngPhysicsTest {
    public static void main(String[] args) {
        testFreeFallGround();
        testAnchoredNode();
        testPlasticAnchorTarget();
        testResetClearsPlasticState();
        testBeamSpringAndDamping();
        testBoxStopsNode();
        testTriangleBreaksWithBeam();
        testDeterminism();
        System.out.println("BamngPhysicsTest OK");
    }

    private static void testFreeFallGround() {
        SoftBodyWorld world = new SoftBodyWorld();
        world.groundY = 0.0;
        SoftNode node = addNode(world, 0.0, 1.0, 0.0, 1.0);
        for (int i = 0; i < 60; i++) {
            world.step(0.05, 20);
        }
        check(!world.hasNaN(), "free fall must stay finite");
        check(Math.abs(node.position.y - node.radius) < 0.01,
                "node must rest on the ground plane, got " + node.position.y);
        check(Math.abs(node.velocity.y) < 0.1, "ground contact must remove downward velocity");
    }

    private static void testAnchoredNode() {
        SoftBodyWorld world = new SoftBodyWorld();
        world.gravityScale = 1.0;
        SoftNode node = addNode(world, 0.0, 1.0, 0.0, 5.8);
        node.anchored = true;
        node.targetX = node.position.x;
        node.targetY = node.position.y;
        node.targetZ = node.position.z;
        node.anchorStiffness = 120_000.0;
        node.anchorDamping = 700.0;
        for (int i = 0; i < 40; i++) {
            world.step(0.05, 20);
        }
        check(!world.hasNaN(), "anchor must stay finite");
        check(node.position.y < 1.0 && node.position.y > 0.995,
                "anchor sag must be sub-millimetre, got " + node.position.y);
    }

    private static void testPlasticAnchorTarget() {
        SoftBodyWorld world = new SoftBodyWorld();
        world.groundY = -10.0;
        SoftNode node = addNode(world, 8.0, 1.0, 0.0, 5.8);
        node.anchored = true;
        node.targetX = 0.0;
        node.targetY = 1.0;
        node.targetZ = 0.0;
        node.plasticX = 0.20;
        node.anchorStiffness = 120_000.0;
        node.anchorDamping = 700.0;
        // A safety snap must preserve the permanent crush offset rather than
        // teleporting the node back to the undeformed target.
        world.step(0.05, 20);
        check(Math.abs(node.position.x - 0.20) < 1.0e-6,
                "anchor recovery must preserve plastic offset, got " + node.position.x);

        // Once recovered, the anchor must settle around target + plastic once.
        for (int i = 0; i < 20; i++) {
            world.step(0.05, 20);
        }
        check(Math.abs(node.position.x - 0.20) < 0.01,
                "plastic anchor target must not be doubled, got " + node.position.x);
        check(!world.hasNaN(), "plastic anchor must remain finite");
    }

    private static void testResetClearsPlasticState() {
        SoftBodyWorld world = new SoftBodyWorld();
        SoftNode node = addNode(world, 0.0, 1.0, 0.0, 1.0);
        node.targetX = 0.0;
        node.targetY = 1.0;
        node.targetZ = 0.0;
        node.plasticX = 0.20;
        node.plasticY = -0.10;
        node.wallDented = true;
        node.wallImpactSpeed = 4.0;
        world.reset();
        check(node.plasticX == 0.0 && node.plasticY == 0.0 && node.plasticZ == 0.0,
                "reset must clear plastic offsets");
        check(!node.wallDented && node.wallImpactSpeed == 0.0,
                "reset must clear pending wall impact state");
        check(Math.abs(node.position.x - node.targetX) < 1.0e-12,
                "reset must restore undeformed target");
    }

    private static void testBeamSpringAndDamping() {
        SoftBodyWorld world = new SoftBodyWorld();
        world.groundY = -10.0;
        SoftNode a = addNode(world, 0.0, 1.0, 0.0, 5.8);
        SoftNode b = addNode(world, 1.0, 1.0, 0.0, 5.8);
        a.anchored = true;
        a.targetX = 0.0;
        a.targetY = 1.0;
        a.targetZ = 0.0;
        b.anchored = true;
        b.targetX = 1.0;
        b.targetY = 1.0;
        b.targetZ = 0.0;
        SoftBeam beam = new SoftBeam(0, 1, 1.0, 1.0, 0.2, 0.85);
        beam.beamSpring = 900_000.0;
        beam.beamDamp = 900.0;
        beam.beamDeform = 30_000.0;
        beam.beamStrength = 90_000.0;
        beam.forceDamage = false;
        world.beams.add(beam);
        world.damage.register(world.beams);
        world.step(0.2, 40);
        check(!world.hasNaN(), "beam must stay finite");
        check(b.position.x > 0.995 && b.position.x < 1.005,
                "beam must hold rest length, got " + b.position.x);
    }

    private static void testBoxStopsNode() {
        SoftBodyWorld world = new SoftBodyWorld();
        world.groundY = -10.0;
        SoftNode node = addNode(world, 0.0, 1.0, 0.0, 5.8);
        node.velocity.x = 2.0;
        world.clearCollisionBoxes();
        world.addCollisionBox(0.5, 0.5, -0.5, 1.5, 1.5, 0.5);
        world.step(0.4, 40);
        check(!world.hasNaN(), "box collision must stay finite");
        check(node.position.x <= 0.5 - node.radius + 0.001,
                "node must stop at the box face, got " + node.position.x);
        check(Math.abs(node.velocity.x) < 0.01, "normal velocity must be removed");
    }

    private static void testTriangleBreaksWithBeam() {
        SoftBodyWorld world = new SoftBodyWorld();
        SoftNode a = addNode(world, 0.0, 1.0, 0.0, 5.8);
        SoftNode b = addNode(world, 1.0, 1.0, 0.0, 5.8);
        SoftNode c = addNode(world, 0.0, 2.0, 0.0, 1.0);
        SoftBeam beam = new SoftBeam(0, 1, 1.0, 1.0, 0.2, 0.85);
        beam.forceDamage = false;
        world.beams.add(beam);
        world.damage.register(world.beams);
        SoftTriangle triangle = new SoftTriangle(0, 1, 2);
        world.triangles.add(triangle);
        world.damage.registerTriangles(world.triangles, world.nodeArray());
        b.position.x = 2.0;
        world.damage.update(world.nodeArray());
        check(beam.broken, "test beam must break");
        check(triangle.broken, "adjacent triangle must break with the beam");
        check(!triangle.collision, "broken triangle collision must be disabled");
    }

    private static void testDeterminism() {
        SoftBodyWorld left = new SoftBodyWorld();
        SoftBodyWorld right = new SoftBodyWorld();
        for (int i = 0; i < 6; i++) {
            double x = i % 3;
            double y = i / 3;
            left.nodes.add(node(0, 0));
            right.nodes.add(node(0, 0));
            left.nodes.get(i).position.set(x, 1.0 + y, 0.0);
            right.nodes.get(i).position.set(x, 1.0 + y, 0.0);
            left.nodes.get(i).previous.set(left.nodes.get(i).position);
            right.nodes.get(i).previous.set(right.nodes.get(i).position);
        }
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                SoftBeam bl = new SoftBeam(i, j, 1.0, 1.0, 0.2, 0.85);
                SoftBeam br = new SoftBeam(i, j, 1.0, 1.0, 0.2, 0.85);
                bl.forceDamage = false;
                br.forceDamage = false;
                left.beams.add(bl);
                right.beams.add(br);
            }
        }
        left.damage.register(left.beams);
        right.damage.register(right.beams);
        for (int tick = 0; tick < 10; tick++) {
            left.step(0.05, 20);
            right.step(0.05, 20);
        }
        for (int i = 0; i < 6; i++) {
            check(Math.abs(left.nodes.get(i).position.x - right.nodes.get(i).position.x) < 1.0e-12,
                    "deterministic x");
            check(Math.abs(left.nodes.get(i).position.y - right.nodes.get(i).position.y) < 1.0e-12,
                    "deterministic y");
        }
    }

    private static SoftNode addNode(SoftBodyWorld world, double x, double y, double z, double mass) {
        SoftNode node = node(world.nodes.size(), mass);
        node.position.set(x, y, z);
        node.previous.set(node.position);
        node.rest.set(node.position);
        world.addNode(node);
        return node;
    }

    private static SoftNode node(int index, double mass) {
        SoftNode node = new SoftNode(index);
        node.setMass(mass);
        return node;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
