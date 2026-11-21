package com.bamng.core;

/**
 * F5: one headless entry point for the test pyramid's current layers.
 * Run with {@code java -cp ... com.bamng.core.BamngTestSuite}.
 */
public final class BamngTestSuite {
    public static void main(String[] args) throws Exception {
        BamngPhysicsTest.main(args);
        BamngCollisionTest.main(args);
        BamngBeamYieldTest.main(args);
        BamngDamageTest.main(args);
        BamngLatticeTest.main(args);
        BamngSnapshotTest.main(args);
        BamngVehiclePairTest.main(args);
        System.out.println("BAMNG SUITE OK");
    }
}
