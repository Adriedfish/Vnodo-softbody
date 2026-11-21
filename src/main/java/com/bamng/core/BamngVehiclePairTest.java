package com.bamng.core;

import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;

/** Headless regression test for symmetric T15-vs-T15 soft-body contact. */
public final class BamngVehiclePairTest {
    private BamngVehiclePairTest() {}

    public static void main(String[] args) {
        VehicleBody a = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        VehicleBody b = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        shift(b, 0.25);

        double aBefore = displacement(a, 0.0);
        double bBefore = displacement(b, 0.25);
        int ab = a.world.resolveForeign(b.world);
        double aAfterAB = displacement(a, 0.0);
        double bAfterAB = displacement(b, 0.25);
        int ba = b.world.resolveForeign(a.world);
        double aAfterBA = displacement(a, 0.0);
        double bAfterBA = displacement(b, 0.25);

        if (ab <= 0 || ba <= 0) {
            throw new AssertionError("both contact directions must produce hits: AB=" + ab + " BA=" + ba);
        }
        if (aAfterAB <= aBefore || bAfterAB <= bBefore) {
            throw new AssertionError("first direction did not move both bodies");
        }
        if (aAfterBA <= aAfterAB || bAfterBA <= bAfterAB) {
            throw new AssertionError("reverse direction did not resolve remaining contacts");
        }
        for (SoftNode n : a.world.nodes) {
            if (!Double.isFinite(n.position.x + n.position.y + n.position.z)) throw new AssertionError("NaN/Inf in A");
        }
        for (SoftNode n : b.world.nodes) {
            if (!Double.isFinite(n.position.x + n.position.y + n.position.z)) throw new AssertionError("NaN/Inf in B");
        }
        System.out.printf("VEHICLE PAIR OK AB=%d BA=%d Aresponse=%.6f Bresponse=%.6f%n",
                ab, ba, aAfterBA - aBefore, bAfterBA - bBefore);
    }

    private static void shift(VehicleBody body, double x) {
        for (SoftNode node : body.world.nodes) {
            node.position.x += x;
            node.previous.x += x;
        }
        for (SoftTriangle triangle : body.world.triangles) {
            triangle.updateGeometry(body.world.nodeArray());
        }
    }

    private static double displacement(VehicleBody body, double xOffset) {
        double sum = 0.0;
        for (SoftNode node : body.world.nodes) {
            double dx = node.position.x - node.rest.x - xOffset;
            double dy = node.position.y - node.rest.y;
            double dz = node.position.z - node.rest.z;
            sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return sum;
    }
}
