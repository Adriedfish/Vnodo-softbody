package com.bamng.core;

import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;

/** Barrier regression using the actual Vettura T15 layout and spec. */
public final class BamngT15CrashTest {
    public static void main(String[] args) {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        SoftBodyWorld world = body.world;
        world.gravityScale = 0.0;
        world.groundY = -100.0;

        // Mirror the runtime's init(): capture local coordinates and anchor
        // targets before imparting the approach velocity to the free crumple zone.
        for (SoftNode node : world.nodes) {
            node.localX = node.position.x;
            node.localY = node.position.y - body.baseY;
            node.localZ = node.position.z;
            node.targetX = node.position.x;
            node.targetY = node.position.y;
            node.targetZ = node.position.z;
            if (!node.anchored) {
                node.velocity.set(28.0, 0.0, 0.0);
            }
        }
        world.addCollisionBox(body.front + 0.10, 0.0, -3.0,
                body.front + 0.70, 3.2, 3.0);
        // This crash harness is the confirmed-impact window equivalent used
        // by the client.  Without arming it, the wall contact is treated as a
        // parked squeeze and intentionally writes no permanent plastic.
        world.damageUpdateEnabled = true;
        world.crashGateArmed = true;

        double originalFront = -1.0e9;
        for (SoftNode node : world.nodes) {
            originalFront = Math.max(originalFront, node.rest.x);
        }
        int substeps = Math.max(48, Math.min(60, world.recommendedSubsteps(0.05)));
        for (int i = 0; i < 40; i++) {
            world.step(0.05, substeps);
        }

        int shortened = 0;
        double maxShorten = 0.0;
        for (SoftBeam beam : world.beams) {
            double delta = beam.originalRestLength - beam.restLength;
            if (delta > 0.004) {
                shortened++;
                maxShorten = Math.max(maxShorten, delta);
            }
        }
        double frontAfterHit = -1.0e9;
        for (SoftNode node : world.nodes) {
            frontAfterHit = Math.max(frontAfterHit, node.position.x);
            node.velocity.set(-3.0, 0.0, 0.0);
        }
        for (int i = 0; i < 20; i++) {
            world.step(0.05, substeps);
        }
        double frontAfterBack = -1.0e9;
        double maxSag = 0.0;
        int sagNodes = 0;
        for (SoftNode node : world.nodes) {
            frontAfterBack = Math.max(frontAfterBack, node.position.x);
            if (Math.abs(node.plasticY) > 0.005) {
                sagNodes++;
                maxSag = Math.max(maxSag, Math.abs(node.plasticY));
            }
        }

        System.out.printf("T15 CRASH frontRest=%.3f hit=%.3f back=%.3f shortened=%d maxShorten=%.3f brokenBeams=%d plastic=%.3f sagNodes=%d maxSag=%.3f%n",
                originalFront, frontAfterHit, frontAfterBack, shortened, maxShorten,
                world.damage.brokenBeamCount(), world.plasticDepth(), sagNodes, maxSag);
        if (sagNodes == 0 || maxSag < 0.005) {
            throw new AssertionError("front fold has no banana sag: " + sagNodes + " / " + maxSag);
        }
        if (shortened < 10) {
            throw new AssertionError("wall hit did not permanently crush enough beams: " + shortened);
        }
        if (maxShorten < 0.01) {
            throw new AssertionError("crush too small: " + maxShorten);
        }
        if (frontAfterBack > originalFront + 0.05) {
            throw new AssertionError("front sprang back past rest plane: " + frontAfterBack);
        }
        System.out.println("T15 CRASH OK");
    }
}
