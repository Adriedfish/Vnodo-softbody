package com.bamng.core;

/** Focused tests for C1/C2/C3/C6/C9. */
public final class BamngCollisionTest {
    public static void main(String[] args) {
        pointTriangle();
        sweep();
        thicknessAndGroup();
        ccd();
        System.out.println("COLLISION OK");
    }

    private static void pointTriangle() {
        Vec3 p = new Vec3();
        double d = TriangleCollision.closestPoint(0.25, 1.0, 0.25,
                0, 0, 0, 1, 0, 0, 0, 0, 1, p);
        check(Math.abs(d - 1.0) < 1.0e-9, "closest distance");
        check(Math.abs(p.x - 0.25) < 1.0e-9 && Math.abs(p.z - 0.25) < 1.0e-9, "closest point");
        Vec3 c = new Vec3();
        double signed = TriangleCollision.signedDistance(0.25, 0.4, 0.25,
                0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, c);
        check(Math.abs(signed - 0.4) < 1.0e-9, "signed distance");
    }

    private static void sweep() {
        Vec3 hit = new Vec3();
        double t = TriangleCollision.sweepPointTriangle(0.25, 1.0, 0.25,
                0.25, -1.0, 0.25,
                0, 0, 0, 1, 0, 0, 0, 0, 1, hit);
        check(Math.abs(t - 0.5) < 1.0e-9, "TOI half");
        check(Math.abs(hit.y) < 1.0e-9, "hit plane");
        double miss = TriangleCollision.sweepPointTriangle(2.0, 1.0, 2.0,
                2.0, -1.0, 2.0,
                0, 0, 0, 1, 0, 0, 0, 0, 1, hit);
        check(!Double.isFinite(miss), "miss outside triangle");
    }

    private static void thicknessAndGroup() {
        SoftBodyWorld w = new SoftBodyWorld();
        w.groundY = -100.0;
        w.gravityScale = 0.0;
        SoftNode n = new SoftNode(0);
        n.position.set(0.25, 0.02, 0.25);
        n.previous.set(n.position);
        n.setMass(5.0);
        n.group0 = "a";
        n.selfCollision = true;
        w.addNode(n);
        SoftTriangle t = new SoftTriangle(1, 2, 3);
        w.addNode(fixed(1, 0, 0, 0));
        w.addNode(fixed(2, 0, 0, 1));
        w.addNode(fixed(3, 1, 0, 0));
        w.triangles.add(t);
        w.damage.register(w.beams);
        w.damage.registerTriangles(w.triangles, w.nodeArray());
        t.updateGeometry(w.nodeArray());
        w.step(0.001, 1);
        check(n.position.y >= 0.0649, "thickness band " + n.position.y);
        check(w.lastTriangleContacts > 0, "triangle contact counted");

        n.group0 = "b";
        n.position.set(0.25, 0.02, 0.25);
        n.previous.set(n.position);
        n.velocity.set(0, -0.2, 0);
        w.step(0.001, 1);
        check(n.position.y < 0.02, "first group exemption");
    }

    private static void ccd() {
        SoftBodyWorld w = new SoftBodyWorld();
        w.groundY = -100.0;
        w.gravityScale = 0.0;
        SoftNode n = new SoftNode(0);
        n.position.set(0.25, 0.5, 0.25);
        n.previous.set(n.position);
        n.velocity.set(0, -1000.0, 0);
        n.setMass(1.0);
        n.group0 = "a";
        n.selfCollision = true;
        n.radius = 0.0;
        w.addNode(n);
        SoftTriangle t = new SoftTriangle(1, 2, 3);
        w.addNode(fixed(1, 0, 0, 0));
        w.addNode(fixed(2, 0, 0, 1));
        w.addNode(fixed(3, 1, 0, 0));
        w.triangles.add(t);
        w.damage.register(w.beams);
        w.damage.registerTriangles(w.triangles, w.nodeArray());
        t.updateGeometry(w.nodeArray());
        w.step(0.002, 1);
        check(n.position.y > 0.0, "CCD kept node above plane " + n.position.y);
    }

    private static SoftNode fixed(int i, double x, double y, double z) {
        SoftNode n = new SoftNode(i);
        n.position.set(x, y, z);
        n.previous.set(n.position);
        n.setMass(1.0e6);
        n.setFixed(true);
        n.group0 = "b";
        return n;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
