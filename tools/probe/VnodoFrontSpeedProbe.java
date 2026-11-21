import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftBodyWorld;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;

/** Headless mirror of the client's rigid feed-forward + wall impact. */
public final class VnodoFrontSpeedProbe {
    public static void main(String[] args) {
        double[] speeds = {5.0, 15.0, 28.0};
        for (double speed : speeds) {
            Result fr = run(speed, true);
            System.out.printf("FRONT speed=%.1f loss=%.2f impact=%.2f target=%.3f panel=%.3f plastic=%.3f shortened=%d contacts=%d box=%d%n",
                    speed, fr.loss, fr.impact, fr.target, fr.panel, fr.plastic, fr.shortened,
                    fr.contacts, fr.boxContacts);
            Result rr = run(speed, false);
            System.out.printf("REAR  speed=%.1f loss=%.2f impact=%.2f target=%.3f panel=%.3f plastic=%.3f shortened=%d contacts=%d box=%d%n",
                    speed, rr.loss, rr.impact, rr.target, rr.panel, rr.plastic, rr.shortened,
                    rr.contacts, rr.boxContacts);
        }
        System.out.println("FRONT SPEED PROBE OK");
    }

    private static Result run(double speed, boolean front) {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        SoftBodyWorld world = body.world;
        world.gravityScale = 0.0;
        world.groundY = -100.0;
        for (SoftNode node : world.nodes) {
            node.localX = node.position.x;
            node.localY = node.position.y - body.baseY;
            node.localZ = node.position.z;
            node.targetX = node.position.x;
            node.targetY = node.position.y;
            node.targetZ = node.position.z;
            node.velocity.set(0.0, 0.0, 0.0);
        }
        if (front) {
            world.addCollisionBox(body.front + 0.10, 0.0, -3.0,
                    body.front + 0.70, 3.2, 3.0);
        } else {
            world.addCollisionBox(-body.front - 0.70, 0.0, -3.0,
                    -body.front - 0.10, 3.2, 3.0);
        }
        world.damageUpdateEnabled = true;
        world.crashGateArmed = true;
        int substeps = Math.max(48, Math.min(60, world.recommendedSubsteps(0.05)));
        double truckX = 0.0;
        double dir = front ? 1.0 : -1.0;
        Result out = new Result();

        for (int tick = 0; tick < 36; tick++) {
            double shift = dir * speed * 0.05;
            truckX += shift;
            for (SoftNode node : world.nodes) {
                node.targetX = node.localX + truckX;
                double[] allowed = world.sweepRigidFollow(node, shift, 0.0, 0.0);
                node.position.x += allowed[0];
                node.position.y += allowed[1];
                node.position.z += allowed[2];
                node.previous.set(node.position);
            }
            world.rigidVelX = dir * speed;
            world.step(0.05, substeps);
            double wallLoss = front ? world.lastFrontWallSpeedLoss : world.lastRearWallSpeedLoss;
            if (out.loss <= 0.0 && wallLoss > 0.0) {
                out.loss = wallLoss;
                out.impact = front ? world.lastFrontWallImpact : world.lastRearWallImpact;
                out.target = front
                        ? 0.050 + 0.00080 * out.loss * out.loss
                        : 0.035 + 0.00055 * out.loss * out.loss;
                world.setCrushPlasticLimits(
                        front ? Math.min(world.globalPlasticLimit, out.target) : world.frontPlasticLimit,
                        front ? world.rearPlasticLimit : Math.min(world.globalPlasticLimit, out.target));
            }
            out.contacts = Math.max(out.contacts,
                    front ? world.lastStepFrontWallContacts : world.lastStepRearWallContacts);
            out.boxContacts = Math.max(out.boxContacts,
                    front ? world.lastStepFrontBoxContacts : world.lastStepRearBoxContacts);
        }

        for (SoftNode node : world.nodes) {
            double d = Math.sqrt(node.plasticX * node.plasticX
                    + node.plasticY * node.plasticY + node.plasticZ * node.plasticZ);
            if ((front && "front".equals(node.group0)) || (!front && "tailgate".equals(node.group0))) {
                out.panel = Math.max(out.panel, d);
            }
        }
        for (var beam : world.beams) {
            if (beam.originalRestLength - beam.restLength > 0.004) {
                out.shortened++;
            }
        }
        out.plastic = world.plasticDepth();
        return out;
    }

    private static final class Result {
        double loss;
        double impact;
        double target;
        double panel;
        double plastic;
        int shortened;
        int contacts;
        int boxContacts;
    }
}
