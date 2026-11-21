package com.bamng.core;

import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;

/** Headless 20-second settling test using the same T15 layout as the runtime. */
public final class BamngCoreSim {
    public static void main(String[] args) {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        body.world.groundY = 0.0;
        // Match SoftRuntime.init(): capture the undeformed truck-frame pose as
        // the anchor target. Without this, local offsets default to zero and
        // every anchored node is pulled toward the world origin.
        for (SoftNode node : body.world.nodes) {
            node.localX = node.position.x;
            node.localY = node.position.y - body.baseY;
            node.localZ = node.position.z;
            node.targetX = node.position.x;
            node.targetY = node.position.y;
            node.targetZ = node.position.z;
        }
        int substeps = Math.max(48, Math.min(60, body.world.recommendedSubsteps(0.05)));
        System.out.printf("nodes=%d beams=%d triangles=%d substeps=%d%n",
                body.world.nodes.size(), body.world.beams.size(), body.world.triangles.size(), substeps);
        for (int tick = 0; tick < 20 * 20; tick++) {
            body.world.step(0.05, substeps);
            if (body.world.hasNaN()) {
                throw new IllegalStateException("soft-body state diverged at tick " + tick);
            }
        }
        System.out.printf("SETTLE OK center=%s minY=%.3f maxSpeed=%.4f broken=%d%n",
                body.world.center(), body.world.minY(), body.world.maxSpeed(),
                body.world.damage.brokenBeamCount());
        if (body.world.damage.brokenBeamCount() != 0) {
            throw new AssertionError("spontaneous beam breaks: " + body.world.damage.brokenBeamCount());
        }
        if (body.world.maxSpeed() > 0.15) {
            throw new AssertionError("settling velocity too high: " + body.world.maxSpeed());
        }
    }
}
