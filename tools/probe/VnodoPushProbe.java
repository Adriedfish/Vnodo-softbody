import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftBodyWorld;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;

/**
 * In-game case: Vettura has already stopped the truck at the wall, the crash
 * window is open, and the only thing left is the client's front impulse.
 * Measures how much of that reaches the cab and bed.
 */
public final class VnodoPushProbe {
    private VnodoPushProbe() {}

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
        world.damageUpdateEnabled = true;

        double hy = 0.0; int fy = 0; for (SoftNode n : world.nodes) { if ("front".equals(n.group0)) { hy += n.position.y; fy++; } } hy = fy > 0 ? hy / fy : 0.6;
        int hits = 0;
        for (SoftNode node : world.nodes) {
            if ("wheel".equals(node.group0) || "tire".equals(node.group0)) {
                continue;
            }
            double dx = node.position.x - body.front;
            double dy = node.position.y - hy;
            double dz = node.position.z;
            if (dx * dx + dy * dy + dz * dz > 0.64) {
                continue;
            }
            node.velocity.x -= 9.1;
            hits++;
        }
        System.out.printf("push nodes=%d%n", hits);

        int substeps = Math.max(48, Math.min(60, world.recommendedSubsteps(0.05)));
        for (int i = 0; i < 40; i++) {
            world.step(0.05, substeps);
        }

        double maxFrontMove = 0.0;
        double maxCabMove = 0.0;
        double maxBedMove = 0.0;
        double maxFrontPlastic = 0.0;
        for (SoftNode node : world.nodes) {
            double mx = node.position.x - node.targetX;
            double mz = node.position.z - node.targetZ;
            double move = Math.sqrt(mx * mx + mz * mz);
            double plastic = Math.sqrt(node.plasticX * node.plasticX
                    + node.plasticZ * node.plasticZ);
            if ("front".equals(node.group0)) {
                maxFrontMove = Math.max(maxFrontMove, move);
                maxFrontPlastic = Math.max(maxFrontPlastic, plastic);
            } else if ("body".equals(node.group0)) {
                maxCabMove = Math.max(maxCabMove, move);
            } else if ("bed".equals(node.group0)) {
                maxBedMove = Math.max(maxBedMove, move);
            }
        }
        int shortened = 0;
        double maxShorten = 0.0;
        for (com.bamng.core.SoftBeam beam : world.beams) {
            double delta = beam.originalRestLength - beam.restLength;
            if (delta > 0.004) {
                shortened++;
                maxShorten = Math.max(maxShorten, delta);
            }
        }
        System.out.printf("PUSH frontMove=%.3f frontPlastic=%.3f cabMove=%.3f bedMove=%.3f shortened=%d maxShorten=%.3f%n",
                maxFrontMove, maxFrontPlastic, maxCabMove, maxBedMove, shortened, maxShorten);
    }
}


