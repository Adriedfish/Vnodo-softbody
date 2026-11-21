import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.antaurora.vettura.vehicle.crush.CrushFrame;
import com.bamng.core.SoftBeam;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;
import com.bamng.core.VnodoCollisionField;

/**
 * Headless check that a real T15 wall impact produces a curved (banana)
 * plastic fold and that the same Vnodo field used by Vettura's
 * crushField()->bodyBoxes()->bent()->hull() chain moves the bumper corner in
 * Y as well as X.  No Minecraft client is started.
 */
public final class VnodoFoldProbe {
    public static void main(String[] args) {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        var world = body.world;
        world.gravityScale = 0.0;
        world.groundY = -100.0;
        for (SoftNode node : world.nodes) {
            // Same rest pose mapping as BamngVetturaPhysicsClient.init().
            double bodyY = node.rest.y - body.baseY;
            double bodyX = node.rest.z;
            double bodyZ = -node.rest.x;
            node.targetX = bodyX * -1.0;
            node.targetY = node.rest.y;
            node.targetZ = bodyZ * -1.0;
            node.position.set(node.targetX, node.targetY, node.targetZ);
            node.previous.set(node.position);
            if (!node.anchored) {
                node.velocity.set(0.0, 0.0, 28.0);
            }
        }
        world.addCollisionBox(-3.0, 0.0, body.front + 0.10,
                3.0, 3.2, body.front + 0.70);
        world.damageUpdateEnabled = true;
        world.crashGateArmed = true;
        int substeps = Math.max(48, Math.min(60, world.recommendedSubsteps(0.05)));
        for (int i = 0; i < 40; i++) {
            world.step(0.05, substeps);
        }

        double maxSag = 0.0;
        int sagNodes = 0;
        for (SoftNode node : world.nodes) {
            if (Math.abs(node.plasticY) > 0.005) {
                sagNodes++;
                maxSag = Math.max(maxSag, Math.abs(node.plasticY));
            }
        }
        int shortened = 0;
        for (SoftBeam beam : world.beams) {
            if (beam.originalRestLength - beam.restLength > 0.004) {
                shortened++;
            }
        }

        VnodoCollisionField field = new VnodoCollisionField(body, VehicleLayout.T15);
        field.update(0.0, 0.0, 0.0, 0.0f);
        CrushFrame frame = field.frame();
        float[] moved = new float[3];
        frame.move(field.field(), 2.40, -0.88, 0.04, moved);
        double fieldMax = 0.0;
        for (float v : field.field()) {
            fieldMax = Math.max(fieldMax, Math.abs(v));
        }
        System.out.printf("fold shortened=%d sagNodes=%d maxSag=%.4f fieldMax=%.4f%n",
                shortened, sagNodes, maxSag, fieldMax);
        double move = Math.sqrt(moved[0] * moved[0] + moved[1] * moved[1] + moved[2] * moved[2]);
        System.out.printf("bumper corner dx=%.4f dy=%.4f dz=%.4f |move|=%.4f deformed=%s version=%d%n",
                moved[0], moved[1], moved[2], move, field.hasDeformation(), field.version());
        if (sagNodes == 0 || maxSag < 0.02) {
            throw new IllegalStateException("no banana sag in headless wall impact");
        }
        if (move < 0.02) {
            throw new IllegalStateException("collision corner did not follow the curved fold");
        }
        System.out.println("VNODO FOLD PROBE OK");
    }
}
