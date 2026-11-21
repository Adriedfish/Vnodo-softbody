package com.bamng.core;

/** F5/F12 regression: binary snapshot round-trip and damage flags. */
public final class BamngSnapshotTest {
    public static void main(String[] args) throws Exception {
        SoftBodyWorld world = new SoftBodyWorld();
        world.gravityScale = 0.0;
        world.groundY = -100.0;
        for (int i = 0; i < 3; i++) {
            SoftNode node = new SoftNode(i);
            node.position.set(i, 0.0, 0.0);
            node.previous.set(node.position);
            node.setMass(1.0);
            world.addNode(node);
        }
        SoftNode[] array = world.nodeArray();
        SoftBeam beam = new SoftBeam(0, 1, 1.0, 10.0, 0.0, 100.0);
        world.beams.add(beam);
        SoftTriangle triangle = new SoftTriangle(0, 1, 2);
        world.triangles.add(triangle);
        world.damage.register(world.beams);
        world.damage.registerTriangles(world.triangles, array);

        array[0].position.set(1.0, 2.0, 3.0);
        array[0].plasticX = 0.15;
        array[0].wallDented = true;
        beam.restLength = 0.75;
        beam.plasticStrain = 0.25;
        beam.deformed = true;
        triangle.broken = true;
        byte[] saved = SoftBodySnapshot.save(world);

        array[0].position.set(9.0, 9.0, 9.0);
        array[0].plasticX = 0.0;
        array[0].wallDented = false;
        beam.restLength = 1.0;
        beam.plasticStrain = 0.0;
        beam.deformed = false;
        triangle.broken = false;
        SoftBodySnapshot.load(world, saved);
        check(Math.abs(array[0].position.x - 1.0) < 1.0e-9, "position restored");
        check(Math.abs(array[0].position.y - 2.0) < 1.0e-9, "y restored");
        check(triangle.broken, "triangle damage restored");
        check(Math.abs(array[0].plasticX - 0.15) < 1.0e-9, "plastic offset restored");
        check(array[0].wallDented, "wall impact state restored");
        check(Math.abs(beam.restLength - 0.75) < 1.0e-9, "beam plastic rest length restored");
        check(Math.abs(beam.plasticStrain - 0.25) < 1.0e-9, "beam plastic strain restored");
        check(beam.deformed, "beam deformed flag restored");
        System.out.println("SNAPSHOT OK bytes=" + saved.length);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
