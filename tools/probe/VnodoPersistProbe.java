import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftBeam;
import com.bamng.core.SoftBodySnapshot;
import com.bamng.core.SoftNode;
import com.bamng.core.SoftTriangle;
import com.bamng.core.VehicleBody;

/** Verifies that a Vnodo shell survives a brand-new VehicleBody instance. */
public final class VnodoPersistProbe {
    public static void main(String[] args) throws Exception {
        VehicleBody original = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        SoftNode savedNode = original.world.nodes.get(12);
        savedNode.plasticX = -0.18;
        savedNode.plasticY = -0.07;
        savedNode.plasticZ = 0.04;
        savedNode.wallDented = true;
        savedNode.wallImpactSpeed = 17.5;

        SoftBeam savedBeam = original.world.damage.beams().get(8);
        savedBeam.restLength = savedBeam.originalRestLength - 0.11;
        savedBeam.plasticStrain = 0.11;
        savedBeam.broken = true;
        savedBeam.deformed = true;
        savedBeam.yieldCount = 3;

        SoftTriangle savedTriangle = original.world.damage.triangles().get(4);
        savedTriangle.broken = true;
        savedTriangle.collision = false;
        savedTriangle.aero = false;

        byte[] data = SoftBodySnapshot.saveVehicleState(original.world);
        int oldBodyId = original.world.bodyId;

        // A world reload creates a new VehicleBody and therefore a new bodyId.
        VehicleBody restored = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        int newBodyId = restored.world.bodyId;
        SoftBodySnapshot.loadVehicleState(restored.world, data);

        SoftNode node = restored.world.nodes.get(12);
        check(Math.abs(node.plasticX + 0.18) < 1.0e-9, "node plasticX");
        check(Math.abs(node.plasticY + 0.07) < 1.0e-9, "node plasticY");
        check(Math.abs(node.plasticZ - 0.04) < 1.0e-9, "node plasticZ");
        check(node.wallDented, "wall flag");
        check(Math.abs(node.wallImpactSpeed - 17.5) < 1.0e-9, "impact speed");

        SoftBeam beam = restored.world.damage.beams().get(8);
        check(Math.abs(beam.originalRestLength - beam.restLength - 0.11) < 1.0e-9, "beam shortening");
        check(beam.broken && beam.deformed, "beam damage flags");
        check(beam.yieldCount == 3, "beam yield count");

        SoftTriangle triangle = restored.world.damage.triangles().get(4);
        check(triangle.broken && !triangle.collision && !triangle.aero, "triangle damage flags");

        restored.world.damage.recountDamageState();
        check(restored.world.damage.brokenBeamCount() >= 1, "beam count restored");
        check(restored.world.damage.brokenTriangleCount() >= 1, "triangle count restored");

        check(oldBodyId != newBodyId, "test must use a different runtime body id");
        System.out.printf("PERSIST OK oldBody=%d newBody=%d bytes=%d brokenBeams=%d%n",
                oldBodyId, newBodyId, data.length,
                restored.world.damage.brokenBeamCount());
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
