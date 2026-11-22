import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftBodyWorld;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;

/**
 * Reproduces the in-game case where Vettura has already stopped the rigid hull
 * by the time the soft shell reaches the wall: rigidVel is a 3 m/s crawl, but
 * the truck arrived at 13 m/s.  The wall marker must record the arrival speed
 * and the crush panel must take permanent plastic from it.
 */
public final class VnodoApproachProbe {
    private VnodoApproachProbe() {}

    public static void main(String[] args) {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        SoftBodyWorld world = body.world;
        world.gravityScale = 0.0;
        world.groundY = -100.0;
        for (SoftNode node : world.nodes) {
            node.targetX = node.position.x;
            node.targetY = node.position.y;
            node.targetZ = node.position.z;
            node.velocity.set(0.0, 0.0, 0.0);
        }
        world.addCollisionBox(body.front + 0.10, 0.0, -3.0,
                body.front + 0.70, 3.2, 3.0);
        world.crashGateArmed = true;
        world.damageUpdateEnabled = false;

        SoftNode nose = null;
        for (SoftNode node : world.nodes) {
            if ("front".equals(node.group0)
                    && (nose == null || node.position.x > nose.position.x)) {
                nose = node;
            }
        }
        if (nose == null) {
            throw new AssertionError("no front crush node");
        }

        System.out.printf("start x=%.4f r=%.4f%n", nose.position.x, nose.radius); double[] out = new double[3];
        world.sweepRigidFollow(nose, 0.40, 0.0, 0.0, out);
        nose.position.x += out[0]; System.out.printf("swept dx=%.5f x=%.5f%n", out[0], nose.position.x);
        nose.previous.x += out[0];

        // Vettura stopped the hull: only the leftover crawl reaches the shell.
        world.rigidVelX = 3.05;
        world.rigidVelZ = 0.0;
        // ...but the truck arrived at 13 m/s along +x, straight into the wall.
        world.crashApproachSpeed = 13.0;
        world.crashApproachDirX = 1.0;
        world.crashApproachDirZ = 0.0;

        int substeps = Math.max(48, Math.min(60, world.recommendedSubsteps(0.05)));
        world.step(0.05, substeps);

        double plastic = Math.sqrt(nose.plasticX * nose.plasticX
                + nose.plasticY * nose.plasticY + nose.plasticZ * nose.plasticZ);
        System.out.printf("APPROACH marker=%.3f contacts=%d noseX=%.3f plastic=%.3f%n",
                world.lastStepFrontWallImpact, world.lastStepFrontWallContacts,
                nose.position.x, plastic);
        if (world.lastStepFrontWallContacts <= 0) {
            throw new AssertionError("wall contact missing");
        }
        if (world.lastStepFrontWallImpact < 10.0) {
            throw new AssertionError("arrival speed lost: " + world.lastStepFrontWallImpact);
        }
        if (plastic < 0.01) {
            throw new AssertionError("crush panel took no plastic: " + plastic);
        }
        System.out.println("APPROACH OK");
    }
}

