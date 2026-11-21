package com.bamng.core;

/** ROR-style asymmetric beam yield: compression hardens, extension weakens. */
public final class BamngBeamYieldTest {
    public static void main(String[] args) {
        compressionHardens();
        extensionWeakensAndBreaks();
        System.out.println("BEAM YIELD OK");
    }

    private static void compressionHardens() {
        SoftBodyWorld w = new SoftBodyWorld();
        w.gravityScale = 0.0;
        SoftNode a = node(w, 0, 0.0);
        SoftNode b = node(w, 1, 1.0);
        SoftBeam beam = beam(0, 1, 1.0);
        beam.maxPosStress = 32_700.0;
        beam.maxNegStress = -32_700.0;
        beam.beamStrength = 220_000.0;

        b.position.x = 0.80; // 20 cm compression
        double before = beam.restLength;
        double force = beam.solveForce(w.nodeArray());
        check(force < 0.0, "compression force must push apart");
        check(beam.restLength < before, "compression must shorten rest length");
        check(beam.maxPosStress > 32_700.0, "compression must harden the limit");
        check(beam.beamStrength == 220_000.0, "compression must not erode strength");
        check(!beam.evaluateDamage(w.nodeArray()), "compression alone must not break");
    }

    private static void extensionWeakensAndBreaks() {
        SoftBodyWorld w = new SoftBodyWorld();
        w.gravityScale = 0.0;
        SoftNode a = node(w, 0, 0.0);
        SoftNode b = node(w, 1, 1.0);
        SoftBeam beam = beam(0, 1, 1.0);
        beam.maxPosStress = 32_700.0;
        beam.maxNegStress = -32_700.0;
        beam.beamStrength = 220_000.0;

        b.position.x = 1.65; // 65 cm extension
        beam.solveForce(w.nodeArray());
        check(beam.restLength > 1.0, "extension must grow rest length");
        check(beam.beamStrength < 220_000.0,
                "extension must erode strength, got " + beam.beamStrength);
        check(beam.evaluateDamage(w.nodeArray()), "eroded beam must break");
    }

    private static SoftBeam beam(int a, int b, double rest) {
        SoftBeam beam = new SoftBeam(a, b, rest, 1.0, 0.2, 0.85);
        beam.beamSpring = 1_400_000.0;
        beam.beamSpringCompress = 1_120_000.0;
        beam.beamDamp = 140.0;
        beam.beamDeform = 32_700.0;
        return beam;
    }

    private static SoftNode node(SoftBodyWorld w, int i, double x) {
        SoftNode n = new SoftNode(i);
        n.position.set(x, 0.0, 0.0);
        n.previous.set(n.position);
        n.setMass(10.0);
        w.addNode(n);
        return n;
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
