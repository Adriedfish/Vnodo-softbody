import com.antaurora.vettura.vehicle.VehicleLayout;
import com.antaurora.vettura.vehicle.physics.VehicleSpec;
import com.bamng.core.SoftNode;
import com.bamng.core.VehicleBody;

/** Body/cab plastic must never run to the whole-vehicle envelope again. */
public final class VnodoPlasticCapProbe {
    private VnodoPlasticCapProbe() {}

    public static void main(String[] args) {
        VehicleBody body = VehicleBody.create(VehicleLayout.T15, VehicleSpec.T15, 0.82);
        SoftNode cab = null;
        for (SoftNode node : body.world.nodes) {
            if (!node.anchored) {
                continue;
            }
            if ("body".equals(node.group0)) {
                node.plasticX = 2.0;
                cab = node;
            }
        }
        if (cab == null) {
            throw new AssertionError("no cab node");
        }
        body.world.capCrushPlastic();
        for (SoftNode node : body.world.nodes) {
            double d = Math.sqrt(node.plasticX * node.plasticX
                    + node.plasticY * node.plasticY + node.plasticZ * node.plasticZ);
            if (d > 0.72 + 1.0e-6) {
                throw new AssertionError("plastic escaped cap: " + d);
            }
        }
        double d = Math.sqrt(cab.plasticX * cab.plasticX
                + cab.plasticY * cab.plasticY + cab.plasticZ * cab.plasticZ);
        if (d > body.world.chassisPlasticLimit + 1.0e-9) {
            throw new AssertionError("cab plastic " + d + " over chassis cap");
        }
        System.out.printf("PLASTIC CAP OK cab=%.3f chassis=%.3f front=%.3f%n",
                d, body.world.chassisPlasticLimit, body.world.frontPlasticLimit);
    }
}
