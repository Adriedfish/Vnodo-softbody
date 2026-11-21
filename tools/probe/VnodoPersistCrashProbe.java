import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftBeam;
import com.bamng.core.SoftBodySnapshot;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;

/** Crash a T15, save the shell, then restore it into a fresh VehicleBody. */
public final class VnodoPersistCrashProbe {
    public static void main(String[] args) throws Exception {
        VehicleBody original = crashBody();
        double oldMaxPlastic = original.world.plasticDepth();
        int oldSagNodes = countSag(original);
        byte[] data = SoftBodySnapshot.saveVehicleState(original.world);

        VehicleBody restored = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        SoftBodySnapshot.loadVehicleState(restored.world, data);
        restored.world.damage.recountDamageState();
        double newMaxPlastic = restored.world.plasticDepth();
        int newSagNodes = countSag(restored);
        int oldBroken = original.world.damage.brokenBeamCount();
        int newBroken = restored.world.damage.brokenBeamCount();

        check(Math.abs(oldMaxPlastic - newMaxPlastic) < 1.0e-9, "plastic depth changed");
        check(oldSagNodes == newSagNodes, "sag node count changed");
        check(oldBroken == newBroken, "broken beam count changed");
        double maxDelta = 0.0;
        for (int i = 0; i < original.world.nodes.size(); i++) {
            SoftNode a = original.world.nodes.get(i);
            SoftNode b = restored.world.nodes.get(i);
            maxDelta = Math.max(maxDelta, Math.abs(a.plasticX - b.plasticX));
            maxDelta = Math.max(maxDelta, Math.abs(a.plasticY - b.plasticY));
            maxDelta = Math.max(maxDelta, Math.abs(a.plasticZ - b.plasticZ));
        }
        check(maxDelta < 1.0e-9, "node plastic arrays differ");
        System.out.printf("PERSIST CRASH OK body=%d->%d plastic=%.3f sagNodes=%d broken=%d bytes=%d maxDelta=%.1e%n",
                original.world.bodyId, restored.world.bodyId,
                newMaxPlastic, newSagNodes, newBroken, data.length, maxDelta);
    }

    private static VehicleBody crashBody() {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        body.world.gravityScale = 0.0;
        body.world.groundY = -100.0;
        for (SoftNode node : body.world.nodes) {
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
        body.world.addCollisionBox(body.front + 0.10, 0.0, -3.0,
                body.front + 0.70, 3.2, 3.0);
        body.world.damageUpdateEnabled = true;
        body.world.crashGateArmed = true;
        int substeps = Math.max(48, Math.min(60, body.world.recommendedSubsteps(0.05)));
        for (int i = 0; i < 60; i++) {
            body.world.step(0.05, substeps);
        }
        return body;
    }

    private static int countSag(VehicleBody body) {
        int count = 0;
        for (SoftNode node : body.world.nodes) {
            if (Math.abs(node.plasticY) > 0.005) {
                count++;
            }
        }
        return count;
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
